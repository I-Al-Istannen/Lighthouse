<div align="center">
  <img align="middle" src="https://github.com/I-Al-Istannen/Lighthouse/blob/master/media/lighthouse.png?raw=true" height="200" width="200">
  <h1>Lighthouse</h1>
</div>

Are you a content docker user that created dockerfiles *based off of public
ones*? Maybe you created a `website` image that depends on `nginx` and also
adds some static content to it. This is neat and works quite well - but when
do you update it?

Existing tools like [Watchtower](https://github.com/containrrr/watchtower) or
[Diun](https://github.com/crazy-max/diun) can only check for updates *of
the running image* - but you are not running `nginx`, just something based off
of it! You need a way to get notified when the *base image* of yours is
updated. Enter *Lighthouse*.

<div align="center">
  <img align="middle" src="https://github.com/I-Al-Istannen/Lighthouse/blob/master/media/update-notification.png?raw=true" width="640">
</div>

## Features
Additionally to watching all containers for updates, *Lighthouse* also
periodically inspects all running containers, extracts their image and the base
image you provided as a label. It verifies the base image is up to date using
the docker registry and then *also verifies*, that your container image is
actually based on the most up-to-date version of the base image.

If *Lighthouse* detects that the local reference base image is outdated, it
will update the base image if you pass `--base-image-update pull_and_update`.
Otherwise it will always tell you that the container is outdated if your base
image is. If the container is based on an outdated image, it will notify you in
discord.

If *Lighthouse* finds a container without a `lighthouse.base` label, it will try
to find updates for the container's image instead. This ensures containers
running images unchanged will also be checked for updates - even without any
special label.

If you do not want to check all containers for updates, you can set
*Lighthouse* to opt-in mode using the `--require-label` flag. If it is set,
*Lighthouse* will only consider containers with the `lighthouse.enabled=true`
label.

The `lighthouse.enabled` flag is *absolute* and not influenced by
`--require-label`. If you set it to `false`, the container will *never* be
checked. If you set it to `true`, the container will *always* be checked.

To save you from many duplicate notifications, *Lighthouse* keeps an internal
database of all images it *successfully* notified you about. Unless you pass
the `--notify-again` flag, *Lighthouse* will only notify you once per image.
It might be a good idea to mount the `/data` directory in the container, so
this database is not lost when you recreate the container.

### As bulletpoints
- Watch labeled containers
- Keep a reference copy of the container's base image up-to-date
- Notify you through a discord webhook if a container is based on an oudated
  base image
- Reads image metadata from OCI labels, GitHub release notes and Docker Hub
- Supports checking for updates for images from arbitrary registries (if they
  conform to standards and/or you have credentials in your Docker config,
  including credential helpers)
- Supports notifications and update requests through ntfy
- Optionally checks for newer image tags

## Usage
```text
Watches for docker base image updates

Usage: lighthouse [OPTIONS] <URL|TOKEN>

Arguments:
  <URL|TOKEN>  Discord webhook URL, Discord bot token or ntfy topic URL

Options:
      --check-times <CRONTAB>
          Check times in cron syntax (https://crontab.guru) [default: "23 08 * * *"]
      --check-on-start
          Also check once right after starting
      --mention <MENTION>
          Discord mention (e.g. '<@userid>')
      --mention-text <TEXT>
          Text to send in Discord. '{IMAGES}' is replaced by the updated images
      --docker-config <PATH>
          Path to the docker config with registry credentials [default: ~/.docker/config.json]
      --hostname <NAME>
          The hostname to mention in notifications
      --base-image-update <STRATEGY>
          Whether to only pull unknown base images or also update outdated ones [default: only_pull_unknown] [possible values: only_pull_unknown, pull_and_update]
      --require-label
          Ignore containers without the 'lighthouse.enabled=true' label
      --notify-again
          Notify about an update every time it is found, not just once
      --check-tag-updates
          Check for newer version tags of containers with a 'lighthouse.tag-check.strategy' label
      --ntfy
          Use ntfy to send notifications and receive update requests
      --github-token <TOKEN>
          GitHub token for fetching release notes (raises the API rate limit) [env: GITHUB_TOKEN]
      --insecure-registry <HOST>
          Registry (host:port) to talk to over plain HTTP, can be repeated
      --data-dir <PATH>
          Directory for persistent state [default: data]
      --bot-updater-docker-image <IMAGE>
          The image to use for updating containers [default: docker]
      --bot-updater-mount <MOUNT>
          Mounts for the updater container ('source:dest[:options]'), can be repeated
      --bot-updater-entrypoint <PATH>
          The binary to call in the updater container. Enables updating from notifications
      --bot-channel-id <ID>
          The channel id the bot should send updates to
  -h, --help
          Print help (see more with '--help')
  -V, --version
          Print version
```

You also should set the `lighthouse.instance` label on the lighthouse
container. This allows is to find itself and update itself *last*.

The mention text can include the placeholder `{IMAGES}` which will be replaced
with a space-separated list of the images having updates available.

You can set the `LOG_LEVEL` environment variable to `DEBUG` to enable debug logging.

With `--check-tag-updates` and the `lighthouse.tag-check.strategy=semver` label,
Lighthouse reports newer version tags. Tag changes must be applied manually.

### As a discord bot

When passing a bot token instead of a webhook url, *Lighthouse* will act as a
full-fledged discord bot.
You can obtain a token by creating a bot on
https://discord.com/developers/applications.
With `--bot-updater-entrypoint`, *Lighthouse* shows container selection menus and
an "Update selected" button in Discord. Each button updates only the selected
containers in its message: it pulls their base images, then starts the updater
container and passes the selected container names as arguments.
The updater image can then automatically rebuild and restart all affected
containers, allowing you to apply updates right after seeing the notification
in discord.

### Example

<details>

<summary>Webhook mode</summary>

```yml
version: "3.9"
services:
  lighthouse:
    image: "ghcr.io/i-al-istannen/lighthouse:latest"
    volumes:
      # Lighthouse needs to talk to docker
      - /var/run/docker.sock:/var/run/docker.sock
      # Registry authentication is stored in the docker config.
      # Mount through whatever config file you need.
      - /root/.docker/config.json:/root/.docker/config.json
      # Persist known image config, so you do not get notified
      # multiple times for a single image, even if you recreate
      # the container.
      - data:/data
    command:
      # Include this name in the alert title
      - '--hostname=Yggdrasil'
      # Tag the user with id 12345678
      - '--mention=<@12345678>'
      # Include this text after the mention
      - '--mention-text=I got some news!'
      # Run every day at 06:13 (https://crontab.guru/#13_06_*_*_*)
      - '--check-times=13 06 * * *'
      # Post to this webhook
      - 'https://discord.com/api/webhooks/.....'
    restart: always

volumes:
  data: {}
```

</details>

<details>

<summary>Bot mode</summary>

```yml
version: "3.9"
services:
  lighthouse:
    image: "ghcr.io/i-al-istannen/lighthouse:latest"
    volumes:
      # Lighthouse needs to talk to docker
      - /var/run/docker.sock:/var/run/docker.sock
      # Registry authentication is stored in the docker config.
      # Mount through whatever config file you need.
      - /root/.docker/config.json:/root/.docker/config.json
      # Persist known image config, so you do not get notified
      # multiple times for a single image, even if you recreate
      # the container.
      - data:/data
    command:
      # Include this name in the alert title
      - '--hostname=Yggdrasil'
      # Tag the user with id 12345678
      - '--mention=<@12345678>'
      # Include this text after the mention
      - '--mention-text=I got some news!'
      # Run every day at 06:13 (https://crontab.guru/#13_06_*_*_*)
      - '--check-times=13 06 * * *'
      # Use this bot token
      - 'xxxxxxxxxxxxxxxxxxxxxxxx.xxxxxx.xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx'
      # The id of the channel the bot should send messages to
      - '--bot-channel-id=926553453583532043'

      # The script to run in the updater container
      - '--bot-updater-entrypoint=/work/rebuild-container.sh'
      # Use the docker image for the updater container (default)
      - '--bot-updater-docker-image=docker'

      # Mount the docker-compose files the updater container will rebuild into the
      # updater container
      - '--bot-updater-mount=/compose-files:/work'
      # Mount the docker socket into the updater so it can use compose
      - '--bot-updater-mount=/var/run/docker.sock:/var/run/docker.sock'
      # Mount the docker daemon config into the updater so it can auth to registries
      - '--bot-updater-mount=/root/.docker/config.json:/root/.docker/config.json'
    restart: always

volumes:
  data: {}
```
</details>

You also need to ensure you label all your derived containers with
`lighthouse.base`, e.g. `lighthouse.base=nginx:stable`.
If you are running a container unchanged (e.g. `docker run nginx:stable`) you
don't need to do anything, unless you set *Lighthouse* to opt-in.

----

It might make sense to structure your dockerfiles in the following way:
```Dockerfile
FROM nginx:stable
LABEL lighthouse.base=nginx:stable
```
This ensures the lighthouse tag is always present and up to date.

## How it works

### Finding the base image of a container
Sadly, docker does no longer build a "Parent" chain, which makes it impossible
to reliably find the correct base image. Therefore, *Lighthouse* requires you
to tag containers with their base image.

### Checking for updates
The docker registry API allows you to check whether an image is up-to-date, but
you have no idea what image version your container is based on! To combat this,
*Lighthouse* always keeps a *reference copy* of the base images on hand and
compares their *layers* with the layers of your container's image. If any layer
is not present in your container, the base image likely was updated in the
meantime.

An up-to-date base image must be available locally, as the registry only serves
the hashes of gzip-encoded layers which can not be mapped to the decompressed
layers in the container image. As updates are only checked against the local
copy for this reason, the local copy needs to be up-to-date, which
*Lighthouse* automatically manages for you.

### Providing information about updates
For new updates, *Lighthouse* reads the image's OCI labels for its version,
source repository and revision. GitHub sources provide matching release notes;
Docker Hub adds the upload time and uploader. Use `--github-token` to raise the
GitHub API rate limit.

----

Logo based on <a href="https://www.flaticon.com/authors/smashicons" title="Smashicons">Smashicons</a> from <a href="https://www.flaticon.com/" title="Flaticon">www.flaticon.com</a>
