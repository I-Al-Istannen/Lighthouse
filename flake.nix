{
  description = "Lighthouse - watches for docker base image updates";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    crane.url = "github:ipetkov/crane";
  };

  outputs =
    {
      self,
      nixpkgs,
      crane,
    }:
    let
      forAllSystems = nixpkgs.lib.genAttrs nixpkgs.lib.systems.flakeExposed;
      pkgsFor = forAllSystems (system: nixpkgs.legacyPackages.${system});
      craneLibFor = forAllSystems (system: crane.mkLib pkgsFor.${system});
      buildArgs = forAllSystems (
        system:
        let
          craneLib = craneLibFor.${system};
          commonArgs = {
            src = craneLib.cleanCargoSource ./.;
            strictDeps = true;
          };
        in
        commonArgs
        // {
          # Dependencies are built separately, so code changes do not rebuild them.
          cargoArtifacts = craneLib.buildDepsOnly commonArgs;
        }
      );
    in
    {
      packages = forAllSystems (
        system:
        let
          pkgs = pkgsFor.${system};
          craneLib = craneLibFor.${system};
          lighthouse = craneLib.buildPackage (
            buildArgs.${system}
            // {
              # Tests that need network access are #[ignore]d, the rest runs in the sandbox
              meta.mainProgram = "lighthouse";
            }
          );
        in
        {
          inherit lighthouse;
          default = lighthouse;

          dockerImage = pkgs.dockerTools.buildLayeredImage {
            name = "ghcr.io/i-al-istannen/lighthouse";
            tag = "latest";
            contents = [ pkgs.cacert ];
            config = {
              Entrypoint = [ (pkgs.lib.getExe lighthouse) ];
              WorkingDir = "/";
              Volumes."/data" = { };
              Env = [
                "SSL_CERT_FILE=${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt"
                "HOME=/root"
              ];
              Labels."org.opencontainers.image.source" = "https://github.com/I-Al-Istannen/Lighthouse";
            };
          };
        }
        // nixpkgs.lib.optionalAttrs (system == "x86_64-linux") {
          # Only the driver is built. The networked scenario is run manually, never by checks.
          discord-e2e =
            (pkgs.testers.runNixOSTest (import ./nix/discord-e2e.nix { inherit pkgs lighthouse; })).driver;
        }
      );

      checks = forAllSystems (
        system:
        let
          pkgs = pkgsFor.${system};
          craneLib = craneLibFor.${system};
          lighthouse = self.packages.${system}.lighthouse;
          mkCheck =
            name: nativeBuildInputs: command:
            pkgs.runCommand "lighthouse-${name}" { inherit nativeBuildInputs; } ''
              ${command}
              touch "$out"
            '';
        in
        # The VM test needs KVM, which only the x86_64 CI runners reliably have
        nixpkgs.lib.optionalAttrs (system == "x86_64-linux") {
          e2e = pkgs.testers.runNixOSTest (import ./nix/e2e.nix { inherit pkgs lighthouse; });
        }
        // {
          inherit lighthouse;
          actionlint = mkCheck "actionlint" [ pkgs.actionlint ] ''
            actionlint ${./.github/workflows}/*.yml
          '';
          zizmor = mkCheck "zizmor" [ pkgs.zizmor ] ''
            zizmor --offline --pedantic ${./.github/workflows}/*.yml
          '';
          clippy = craneLib.cargoClippy (
            buildArgs.${system}
            // {
              cargoClippyExtraArgs = "--all-targets -- --deny warnings";
            }
          );
          fmt = craneLib.cargoFmt { inherit (buildArgs.${system}) src; };
        }
      );

      formatter = forAllSystems (system: pkgsFor.${system}.nixfmt);
    };
}
