"""Live Discord scenario: Docker fixtures drive the normal Lighthouse executable."""

import os
import tempfile
import time


def run_discord(
    machine: QemuMachine,
    registry: str,
    topic: str,
    start_all: Callable[[], None],
    subtest: Callable[[str], AbstractContextManager[None]],
) -> None:
    validate_only = os.environ.get("DISCORD_TEST_VALIDATE_ONLY") == "1"
    required = ["DISCORD_TEST_BOT_TOKEN", "DISCORD_TEST_CHANNEL_ID"]
    if not validate_only and any(not os.environ.get(key) for key in required):
        raise RuntimeError("Set DISCORD_TEST_BOT_TOKEN and DISCORD_TEST_CHANNEL_ID")
    if not validate_only:
        assert os.environ["DISCORD_TEST_CHANNEL_ID"].isdigit(), "Invalid channel ID"
    seconds = int(os.environ.get("DISCORD_TEST_SECONDS", "0"))
    assert seconds >= 0, "DISCORD_TEST_SECONDS must be nonnegative"
    h = Harness(machine, registry, topic)

    start_all()
    for unit in ["docker.service", "docker-registry.service", "ntfy-sh.service"]:
        machine.wait_for_unit(unit)
    machine.wait_for_open_port(5000)
    machine.wait_for_open_port(2586)
    machine.succeed("curl -fsS --retry 5 https://github.com -o /dev/null")

    with subtest("Create real containers with outdated bases and a Garage tag policy"):
        # These small stand-ins deliberately lack the remote image's layers and digest.
        # Explicit base labels let the normal checker discover that mismatch. Metadata
        # comes from the real registries; layers are only downloaded when Update is clicked.
        h.build_content_image("old-base", "old base layers")
        bases = [
            ("nginx:stable", "web", 0, 1),
            ("ghcr.io/home-assistant/home-assistant:2025.1.0", "home-assistant", 1, 27),
            ("dxflrs/garage:v2.0.0", "garage", 27, 130),
        ]
        for base, name, first, end in bases:
            machine.succeed(f"docker tag old-base:latest {shlex.quote(base)}")
            h.build_content_image(
                name,
                f"outdated {name} application layers",
                base=base,
                destination="/application",
                labels={"lighthouse.base": base, "lighthouse.enabled": "true"},
            )
            for index in range(first, end):
                container = (
                    "demo-fail-once" if index == 0 else f"demo-{name}-{index:03}"
                )
                policy = (
                    "--label lighthouse.tag-check.strategy=semver "
                    if name == "garage"
                    else ""
                )
                machine.succeed(
                    f"docker create --name {container} {policy}{name}:latest"
                )
        # A broken base exercises normal checker error reporting too.
        machine.succeed(
            "docker create --name demo-broken --label lighthouse.enabled=true "
            f"--label lighthouse.base={registry}/missing:1 old-base:latest"
        )
        h.build_updater_image("updater")
        h.write_file(
            "/tmp/fixtures/updater/update",
            "#!/bin/busybox sh\n"
            "/bin/busybox sleep 3\n"
            'echo "$@" >> /out/attempts\n'
            'case " $* " in\n'
            '  *" demo-fail-once "*)\n'
            "    if [ ! -e /out/failed-once ]; then\n"
            "      /bin/busybox touch /out/failed-once\n"
            '      echo "Deliberate first-attempt failure; retry to succeed" >&2\n'
            "      exit 1\n"
            "    fi;;\n"
            "esac\n"
            'echo "$@" >> /out/updated\n',
        )
        h.build(
            "updater",
            "FROM scratch\nCOPY busybox /bin/busybox\nCOPY update /bin/update\n",
            {},
        )
        h.push("updater", "updater:latest")
        machine.succeed("mkdir -p /tmp/out")

    # Transfer credentials as a file: succeed() logs commands, so never interpolate
    # tokens into those commands. Nothing secret enters a Nix derivation or store path.
    with tempfile.NamedTemporaryFile(
        mode="w", prefix="lighthouse-discord-", suffix=".env"
    ) as env:
        keys = required + ["GITHUB_TOKEN"]
        if validate_only:
            env.write("DISCORD_TEST_VALIDATE_ONLY=1\n")
            keys = ["GITHUB_TOKEN"]
        for key in keys:
            value = os.environ.get(key)
            if value:
                assert "\n" not in value and "\r" not in value, f"Invalid {key}"
                escaped = value.replace("\\", "\\\\").replace('"', '\\"')
                env.write(f'{key}="{escaped}"\n')
        env.flush()
        machine.copy_from_host(env.name, "/run/lighthouse-test.env")

    with subtest("Run the real checker and enrich its notifications"):
        machine.succeed("systemctl start lighthouse")
        machine.wait_until_succeeds(
            "journalctl -u lighthouse --no-pager | grep -q 'Sleeping until next check'",
            timeout=300,
        )
        journal = machine.succeed("journalctl -u lighthouse --no-pager")
        for expected in [
            "Checking participating containers",
            "Collected metadata image=nginx:stable",
            "Collected metadata image=ghcr.io/home-assistant/home-assistant:2025.1.0",
            "Found GitHub release",
            "Collected metadata image=dxflrs/garage:",
        ]:
            assert expected in journal, f"Missing {expected} in journal"
        assert "Could not fetch Docker Hub metadata" not in journal, journal
        assert "Could not fetch release notes" not in journal, journal
        assert "Could not read image config for metadata" not in journal, journal
        assert "Could not send digest update notifications" not in journal, journal
        assert "Could not send tag update notifications" not in journal, journal
        machine.succeed("test -s /var/lib/lighthouse/lighthouse-updates.json")

    if validate_only:
        n = h.notifications()
        updates = n.starting_with("Remote:")
        assert len(updates) == 3, n
        assert any(
            "dxflrs/garage" in body
            for body in n.starting_with("Manual update required")
        ), n
        assert len(n.titled("Lighthouse Error (Discord-e2e-VM)")) == 1, n
        # Even in validation mode, exercise the real updater container and failure/retry.
        machine.succeed(f"curl -sf -d 'Update all containers' {shlex.quote(topic)}")
        machine.wait_until_succeeds("test -s /tmp/out/attempts", timeout=600)
        machine.wait_until_succeeds(
            f"curl -sf {h.poll_url} | grep -q 'Updater failed with exit code 1'",
            timeout=120,
        )
        machine.succeed(f"curl -sf -d 'Update all containers' {shlex.quote(topic)}")
        machine.wait_until_succeeds("test -s /tmp/out/updated", timeout=120)
        assert len(machine.succeed("cat /tmp/out/updated").split()) == 130
        return

    machine.wait_until_succeeds(
        "journalctl -u lighthouse --no-pager | grep -q 'Discord bot connected'",
        timeout=60,
    )
    print(
        "Open your Discord test channel to inspect the messages and use the controls."
    )
    print(
        f"Stopping after {seconds}s."
        if seconds
        else "Press Ctrl-C here to stop the VM."
    )
    print(
        "130 containers span two control messages. Clear all selections to try Nothing selected."
    )
    print(
        "Include demo-fail-once to see a failure, then retry. Base pulls may take a few minutes."
    )
    print(
        "The updater only records the selected VM container names; your server's Docker is untouched."
    )
    try:
        if seconds:
            time.sleep(seconds)
        else:
            while True:
                time.sleep(1)
    except KeyboardInterrupt:
        print("Stopping the Discord e2e VM.")
    finally:
        print(
            machine.succeed(
                "cat /tmp/out/attempts /tmp/out/updated 2>/dev/null || true"
            )
        )
        machine.succeed("systemctl stop lighthouse; rm -f /run/lighthouse-test.env")
