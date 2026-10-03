# End-to-end test: a VM with a docker daemon, a local registry and an ntfy server.
#
# Lighthouse runs as a service with --check-on-start, so every restart is one check run.
{ pkgs, lighthouse }:
let
  registry = "localhost:5000";
  topic = "http://localhost:2586/lighthouse";
in
{
  name = "lighthouse-e2e";

  nodes.machine = {
    virtualisation = {
      docker.enable = true;
      memorySize = 2048;
      diskSize = 4096;
    };

    services.dockerRegistry = {
      enable = true;
      port = 5000;
    };

    services.ntfy-sh = {
      enable = true;
      settings = {
        base-url = "http://localhost:2586";
        listen-http = ":2586";
      };
    };

    environment.systemPackages = [
      pkgs.curl
      pkgs.skopeo
      pkgs.pkgsStatic.busybox
    ];

    systemd.services.lighthouse = {
      after = [
        "docker.service"
        "ntfy-sh.service"
      ];
      environment.RUST_LOG = "lighthouse=debug";
      serviceConfig = {
        ExecStart = pkgs.lib.escapeShellArgs [
          (pkgs.lib.getExe lighthouse)
          "--ntfy"
          "--check-on-start"
          # Checks are triggered by restarts during the test
          "--check-times=0 0 1 1 *"
          "--check-tag-updates"
          "--insecure-registry=${registry}"
          "--data-dir=/var/lib/lighthouse"
          "--bot-updater-entrypoint=/bin/update"
          "--bot-updater-docker-image=${registry}/updater:latest"
          "--bot-updater-mount=/tmp/out:/out"
          topic
        ];
        StateDirectory = "lighthouse";
      };
    };
  };

  # Keep the scenario in a standalone Python file for editor support.
  testScript = builtins.readFile ../tests/e2e.py + ''
    run(machine, ${builtins.toJSON registry}, ${builtins.toJSON topic}, start_all, subtest)
  '';
}
