# Contributing

## Building and testing

```sh
nix build .#dockerImage
docker load < result
nix flake check
```

To run the manual Discord e2e VM:

```sh
nix build .#discord-e2e
export DISCORD_TEST_BOT_TOKEN='your bot token'
export DISCORD_TEST_CHANNEL_ID='your channel ID'
./result/bin/nixos-test-driver
```

It runs until Ctrl-C and is excluded from `nix flake check`.
