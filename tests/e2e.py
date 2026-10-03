"""End-to-end scenario for the NixOS VM test in nix/e2e.nix.

The test driver executes this file and then calls `run` with its globals.
"""

import json
import shlex
from collections.abc import Callable
from contextlib import AbstractContextManager
from typing import TypedDict

from test_driver.machine import QemuMachine


class Notification(TypedDict, total=False):
    """The ntfy message fields the scenario inspects."""

    message: str
    title: str


class Notifications:
    """Every message published to the topic so far."""

    def __init__(self, events: list[Notification]) -> None:
        self.events = events

    def __repr__(self) -> str:
        return repr(self.events)

    def starting_with(self, prefix: str) -> list[str]:
        return [
            body
            for e in self.events
            if (body := e.get("message", "")).startswith(prefix)
        ]

    def titled(self, title: str) -> list[str]:
        return [e.get("message", "") for e in self.events if e.get("title") == title]


def containers(body: str) -> list[str]:
    """The sorted container names listed in a notification body."""
    line = next(line for line in body.splitlines() if line.startswith("Containers: "))
    return sorted(line.removeprefix("Containers: ").split(", "))


class Harness:
    def __init__(self, machine: QemuMachine, registry: str, topic: str) -> None:
        self.machine = machine
        self.registry = registry
        self.topic = topic

    def write_file(self, path: str, content: str) -> None:
        self.machine.succeed(f"printf %s {shlex.quote(content)} > {shlex.quote(path)}")

    def build(self, tag: str, dockerfile: str, files: dict[str, str]) -> None:
        """Build `tag` offline from a Dockerfile and text files in its context."""
        context = f"/tmp/fixtures/{tag}"
        self.machine.succeed(f"mkdir -p {context}")
        for name, content in {**files, "Dockerfile": dockerfile}.items():
            self.write_file(f"{context}/{name}", content)
        self.machine.succeed(
            "docker build --network=none --build-arg SOURCE_DATE_EPOCH=1 "
            f"-t {tag}:latest {context}"
        )

    def build_content_image(
        self,
        tag: str,
        content: str,
        *,
        base: str = "scratch",
        destination: str = "/content",
        labels: dict[str, str] | None = None,
    ) -> None:
        """Add one layer, so every distinct content has its own digest."""
        dockerfile = f'FROM {base}\nCOPY content {destination}\nCMD ["/content"]\n'
        for key, value in (labels or {}).items():
            dockerfile += f"LABEL {key}={json.dumps(value)}\n"
        self.build(tag, dockerfile, {"content": content})

    def build_updater_image(self, tag: str) -> None:
        """A static shell that records which containers it was asked to update."""
        context = f"/tmp/fixtures/{tag}"
        self.machine.succeed(
            f"mkdir -p {context}",
            f"cp -L $(command -v busybox) {context}/busybox",
        )
        self.write_file(
            f"{context}/update", '#!/bin/busybox sh\necho "$@" >> /out/updated\n'
        )
        self.machine.succeed(f"chmod +x {context}/update")
        self.build(
            tag,
            "FROM scratch\nCOPY busybox /bin/busybox\nCOPY update /bin/update\n",
            {},
        )

    def push(self, local: str, ref: str) -> None:
        """Publish a local image as `ref` in the registry.

        This goes through skopeo, so moving a remote tag leaves the local image
        Lighthouse compares against untouched.
        """
        self.machine.succeed(
            "skopeo --insecure-policy copy --dest-tls-verify=false "
            f"docker-daemon:{local}:latest docker://{self.registry}/{ref}"
        )

    def remote_digest(self, ref: str) -> str:
        return self.machine.succeed(
            "skopeo --insecure-policy inspect --tls-verify=false "
            f"docker://{self.registry}/{ref} --format '{{{{.Digest}}}}'"
        ).strip()

    def inspect(self, image: str, field: str) -> list[str]:
        return json.loads(
            self.machine.succeed(
                f"docker image inspect {image} --format '{{{{json .{field}}}}}'"
            )
        )

    @property
    def poll_url(self) -> str:
        return shlex.quote(f"{self.topic}/json?poll=1&since=all")

    def notifications(self) -> Notifications:
        out = self.machine.succeed(f"curl -sf {self.poll_url}")
        return Notifications([json.loads(line) for line in out.splitlines() if line])

    def check(self) -> Notifications:
        """Restart Lighthouse, which checks once on start, and wait for it to idle."""
        self.machine.succeed("systemctl restart lighthouse")
        invocation = self.machine.succeed(
            "systemctl show lighthouse --property=InvocationID --value"
        ).strip()
        assert invocation, "Lighthouse has no systemd invocation"
        journal = f"journalctl --no-pager _SYSTEMD_INVOCATION_ID={invocation}"
        for line in ["Sleeping until next check", "Listening for update requests"]:
            self.machine.wait_until_succeeds(
                f"{journal} | grep -q {shlex.quote(line)}", timeout=120
            )
        return self.notifications()


def run(
    machine: QemuMachine,
    registry: str,
    topic: str,
    start_all: Callable[[], None],
    subtest: Callable[[str], AbstractContextManager[None]],
) -> None:
    h = Harness(machine, registry, topic)

    start_all()
    for unit in ["docker.service", "docker-registry.service", "ntfy-sh.service"]:
        machine.wait_for_unit(unit)
    machine.wait_for_open_port(5000)
    machine.wait_for_open_port(2586)

    with subtest("Set up registry and containers"):
        for name in ["app-v1", "app-v2", "base-v1", "base-v2"]:
            h.build_content_image(name, name)
        h.build_updater_image("updater")

        h.push("app-v1", "app:latest")
        h.push("base-v1", "base:1")
        h.push("app-v1", "tagged:1.0")
        h.push("app-v2", "tagged:1.1")
        # A different variant, the semver strategy must not suggest it
        h.push("base-v1", "tagged:2.0-alpine")
        h.push("updater", "updater:latest")

        machine.succeed(
            "mkdir -p /tmp/out",
            f"docker pull {registry}/app:latest",
            f"docker pull {registry}/base:1",
            f"docker pull {registry}/tagged:1.0",
        )
        h.build_content_image(
            "derived",
            "application content",
            base=f"{registry}/base:1",
            destination="/application",
            labels={"lighthouse.base": f"{registry}/base:1"},
        )
        machine.succeed(
            f"docker create --name plain {registry}/app:latest",
            f"docker create --name ignored --label lighthouse.enabled=false {registry}/app:latest",
            "docker create --name derived derived:latest",
            "docker create --name derived-2 derived:latest",
            f"docker create --name versioned --label lighthouse.tag-check.strategy=semver {registry}/tagged:1.0",
            f"docker create --name broken --label lighthouse.base={registry}/missing:1 {registry}/app:latest",
        )

        # derived must be base:1 plus its own layers
        base_layers = h.inspect(f"{registry}/base:1", "RootFS.Layers")
        derived_layers = h.inspect("derived:latest", "RootFS.Layers")
        assert derived_layers[: len(base_layers)] == base_layers, derived_layers
        assert len(derived_layers) > len(base_layers), derived_layers

    with subtest("A current base plus application layers is up to date"):
        n = h.check()
        assert n.starting_with("Remote:") == [], n

        tags = n.starting_with("Manual update required")
        assert len(tags) == 1, tags
        assert "Current: 1.0 → New: 1.1" in tags[0], tags[0]
        assert containers(tags[0]) == ["versioned"], tags[0]

        errors = n.titled("Lighthouse Error")
        assert len(errors) == 1, errors
        assert "missing" in errors[0], errors[0]
        assert n.titled("Lighthouse Update") == [], n

    with subtest("New digests are grouped with exactly the affected containers"):
        h.push("app-v2", "app:latest")
        h.push("base-v2", "base:1")
        n = h.check()

        updates = n.starting_with("Remote:")
        assert len(updates) == 2, updates
        [app] = [u for u in updates if u.startswith(f"Remote: {registry}/app:latest\n")]
        [base] = [u for u in updates if u.startswith(f"Remote: {registry}/base:1\n")]
        assert containers(app) == ["plain"], app
        assert containers(base) == ["derived", "derived-2"], base
        assert "Images: derived:latest" in base, base
        # Reproducible images claim to be from 1970, that is no update time
        assert "1970" not in app, app
        assert len(n.starting_with("Manual update required")) == 1, n
        assert n.titled("Lighthouse Update") == ["Click to apply 2 update(s)"], n

    with subtest("Restarting suppresses announcements but restores actionable updates"):
        n = h.check()
        assert len(n.starting_with("Remote:")) == 2, n
        assert len(n.starting_with("Manual update required")) == 1, n
        assert n.titled("Lighthouse Update") == ["Click to apply 2 update(s)"] * 2, n
        machine.succeed("test -s /var/lib/lighthouse/lighthouse-updates.json")

    with subtest("A current local base still reveals outdated derived layers"):
        machine.succeed(f"docker pull {registry}/base:1")
        n = h.check()
        assert len(n.starting_with("Remote:")) == 2, n
        assert n.titled("Lighthouse Update") == ["Click to apply 2 update(s)"] * 3, n

    with subtest("The update action still applies all targets after repeated checks"):
        machine.succeed(f"curl -sf -d 'Update all containers' {shlex.quote(topic)}")
        machine.wait_until_succeeds("test -s /tmp/out/updated", timeout=120)
        updated = machine.succeed("cat /tmp/out/updated").split()
        assert sorted(updated) == ["derived", "derived-2", "plain"], updated

        machine.wait_until_succeeds(
            f"curl -sf {h.poll_url} | grep -q 'Updated 3 container'", timeout=60
        )
        # The updated images were pulled before running the updater
        for repo, tag in [("app", "latest"), ("base", "1")]:
            ref = f"{repo}:{tag}"
            digest = h.remote_digest(ref)
            repo_digests = h.inspect(f"{registry}/{ref}", "RepoDigests")
            assert f"{registry}/{repo}@{digest}" in repo_digests, repo_digests
        builders = machine.succeed(
            "docker ps -aq --filter label=lighthouse-builder-container"
        )
        assert builders.strip() == "", builders

    with subtest("Applying updates does not cause duplicate announcements"):
        n = h.check()
        assert len(n.starting_with("Remote:")) == 2, n
        assert len(n.starting_with("Manual update required")) == 1, n
