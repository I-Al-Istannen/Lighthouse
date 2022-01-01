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
updated. Enter **Lighthouse**.

<div align="center">
  <img align="middle" src="https://github.com/I-Al-Istannen/Lighthouse/blob/master/media/update-notification.png?raw=true" width="640">
</div>

## Features
Lighthouse periodically inspects all running containers, extracts their image
and the base image you provided as a label. It verifies the base image is
up to date using the docker registry and then *also verifies*, that your
container image is actually based on the most up-to-date version of the base
image.

If Lighthouse detects that the local reference base image is outdated, it will
automatically update it to ensure all checks work correctly. If the container
is based on an outdated image, it will notify you in discord.

### As bulletpoints
- Watch labeled containers
- Keep a reference copy of the container's base image up-to-date
- Notify you through a discord webhook if a container is based on an oudated
  base image


## Usage
```
Watches for docker base image updates

USAGE
  lighthouse [OPTIONS] URL

PARAMETERS
  URL  Discord webhook URL

OPTIONS
  --check-interval-seconds CHECK-INTERVAL-SECONDS  Check interval in seconds
  --mention-user-id MENTION-USER-ID                Discord user id to mention
  --mention-text MENTION-TEXT                      Text to send in Discord
```

### Example
```
docker run                                      \
  --rm                                          \
  -v /var/run/docker.sock:/var/run/docker.sock  \
  --restart always                              \
  ghcr.io/i-al-istannen/lighthouse:latest       \
  <discord webhook url>                         \
  --mention-user-id 12345678                    \
  --mention-text "A wild update appeared!"
```

You also need to ensure you label all your containers with `lighthouse.base`,
e.g. `lighthouse.base=nginx:stable`.


## How it works

### Finding the base image of a container
Sadly, docker does no longer build a "Parent" chain, which makes it impossible
to reliably find the correct base image. Therefore, **Lighthouse** requires you
to tag containers with their base image.

### Checking for updates
The docker registry API allows you to check whether an image is up-to-date, but
you have no idea what image version your container is based on! To combat this,
**Lighthouse** always keeps a *reference copy* of the base images on hand and
compares their *layers* with the layers of your container's image. If any layer
is not present in your container, the base image likely was updated in the
meantime.

An up-to-date base image must be available locally, as the registry only serves
the hashes of gzip-encoded layers which can not be mapped to the decompressed
layers in the container image. As updates are only checked against the local
copy for this reason, the local copy needs to be up-to-date, which
**Lighthouse** automatically manages for you.

### Providing information about updates
Once an update is found, **Lighthouse** will fetch up-to-date image information
from docker hub, to ensure the notification message is useful.

----

Logo based on <a href="https://www.flaticon.com/authors/smashicons" title="Smashicons">Smashicons</a> from <a href="https://www.flaticon.com/" title="Flaticon">www.flaticon.com</a>
