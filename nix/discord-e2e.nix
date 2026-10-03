# Manual live test. Build the driver, then run it outside the Nix build sandbox.
# Credentials are copied into the running VM by the Python scenario, never evaluated by Nix.
{ pkgs, lighthouse }:
let
  registry = "localhost:5000";
  topic = "http://localhost:2586/lighthouse";
in
{
  name = "lighthouse-discord-e2e";

  nodes.machine = {
    virtualisation = {
      docker.enable = true;
      memorySize = 3072;
      diskSize = 12288;
      restrictNetwork = false;
    };
    # eth0 is QEMU's user network; eth1 belongs to the isolated test network.
    networking = {
      interfaces.eth0.ipv4.addresses = [
        {
          address = "10.0.2.15";
          prefixLength = 24;
        }
      ];
      defaultGateway = "10.0.2.2";
      nameservers = [ "10.0.2.3" ];
    };
    services.dockerRegistry = {
      enable = true;
      port = 5000;
    };
    # Also allows validating fixtures and metadata without Discord credentials.
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
        "docker-registry.service"
        "ntfy-sh.service"
      ];
      environment.RUST_LOG = "lighthouse=debug";
      serviceConfig = {
        EnvironmentFile = "/run/lighthouse-test.env";
        StateDirectory = "lighthouse";
        ExecStart = pkgs.writeShellScript "lighthouse-discord-e2e" ''
          args=(
            --check-on-start
            '--check-times=0 0 1 1 *'
            --check-tag-updates
            --require-label
            --hostname=Discord-e2e-VM
            --insecure-registry=${registry}
            --data-dir=/var/lib/lighthouse
            --bot-updater-entrypoint=/bin/update
            --bot-updater-docker-image=${registry}/updater:latest
            --bot-updater-mount=/tmp/out:/out
          )
          if [[ -n "''${DISCORD_TEST_VALIDATE_ONLY:-}" ]]; then
            args+=(--ntfy ${topic})
          else
            args+=(--bot-channel-id="$DISCORD_TEST_CHANNEL_ID" "$DISCORD_TEST_BOT_TOKEN")
          fi
          exec ${pkgs.lib.getExe lighthouse} "''${args[@]}"
        '';
      };
    };
  };

  testScript =
    builtins.readFile ../tests/e2e.py
    + "\n"
    + builtins.readFile ../tests/discord_e2e.py
    + ''
      run_discord(machine, ${builtins.toJSON registry}, ${builtins.toJSON topic}, start_all, subtest)
    '';
}
