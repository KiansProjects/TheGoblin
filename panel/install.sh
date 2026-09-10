#!/bin/bash
# TheGoblin installer for Pelican Panel
# Server directory = /mnt/server

set -e

apt-get update -y
apt-get install -y --no-install-recommends curl jq git tar xz-utils unzip ca-certificates

mkdir -p /mnt/server/bin /mnt/server/output
cd /mnt/server

## ---------------------------------------------------------------
## 1) yt-dlp (standalone binary, brings its own Python)
## ---------------------------------------------------------------
if [ -z "${YTDLP_VERSION}" ] || [ "${YTDLP_VERSION}" == "latest" ]; then
    YTDLP_URL="https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux"
else
    YTDLP_URL="https://github.com/yt-dlp/yt-dlp/releases/download/${YTDLP_VERSION}/yt-dlp_linux"
fi

echo "-> yt-dlp"
curl -fL -o bin/yt-dlp "${YTDLP_URL}"
chmod +x bin/yt-dlp

## ---------------------------------------------------------------
## 2) jellyfin-ffmpeg (portable build, practically static)
## ---------------------------------------------------------------
if [ -z "${FFMPEG_VERSION}" ] || [ "${FFMPEG_VERSION}" == "latest" ]; then
    FFMPEG_VERSION=$(curl -sSL https://api.github.com/repos/jellyfin/jellyfin-ffmpeg/releases/latest \
        | jq -r '.tag_name' | sed 's/^v//')
fi
echo "-> ffmpeg ${FFMPEG_VERSION}"

curl -fL -o ffmpeg.tar.xz \
    "https://github.com/jellyfin/jellyfin-ffmpeg/releases/download/v${FFMPEG_VERSION}/jellyfin-ffmpeg_${FFMPEG_VERSION}_portable_linux64-gpl.tar.xz"
tar -xJf ffmpeg.tar.xz -C bin
rm -f ffmpeg.tar.xz
chmod +x bin/ffmpeg bin/ffprobe

## ---------------------------------------------------------------
## 3) Deno as the JS runtime
##
## yt-dlp needs a JavaScript runtime to solve YouTube's player challenges.
## Without one the log says "JS runtimes: none" and some videos stop
## returning format URLs entirely. Deno is a single binary and simply ends
## up in bin/ after unpacking.
## ---------------------------------------------------------------
if [ "${INSTALL_DENO}" != "0" ]; then
    if [ -z "${DENO_VERSION}" ] || [ "${DENO_VERSION}" == "latest" ]; then
        DENO_URL="https://github.com/denoland/deno/releases/latest/download/deno-x86_64-unknown-linux-gnu.zip"
    else
        DENO_URL="https://github.com/denoland/deno/releases/download/${DENO_VERSION}/deno-x86_64-unknown-linux-gnu.zip"
    fi

    echo "-> deno"
    if curl -fL -o deno.zip "${DENO_URL}"; then
        unzip -o -q deno.zip -d bin
        rm -f deno.zip
        chmod +x bin/deno
    else
        echo "!! deno could not be downloaded, carrying on without it"
    fi
else
    echo "-> deno skipped"
fi

## ---------------------------------------------------------------
## 4) Build TheGoblin
##
## The checkout is also where the two files that ship alongside the jar come
## from - the console wrapper and the config template. Copying them out of it
## rather than writing them here means they can never drift from the code
## they belong to. Both are taken before the checkout is removed again.
## ---------------------------------------------------------------
if [ -n "${GOBLIN_REPO}" ]; then
    echo "-> TheGoblin from ${GOBLIN_REPO}"
    rm -rf .src
    git clone --depth 1 --branch "${GOBLIN_BRANCH:-main}" "${GOBLIN_REPO}" .src

    rm -rf .classes
    mkdir -p .classes
    javac -d .classes $(find .src/src -name "*.java")
    jar --create --file goblin.jar \
        --main-class space.perrys.goblin.Goblin \
        -C .classes .

    [ -f .src/goblin.properties.example ] && cp -f .src/goblin.properties.example .
    [ -f .src/panel/goblin-console.sh ] && cp -f .src/panel/goblin-console.sh .

    rm -rf .classes .src
    echo "-> goblin.jar built"
elif [ -f goblin.jar ]; then
    echo "-> goblin.jar is already here, left untouched"
else
    echo "!! No GOBLIN_REPO set and no goblin.jar present."
    echo "!! Upload the jar to /home/container/goblin.jar with the file manager."
fi

## ---------------------------------------------------------------
## 5) Configuration template
##
## goblin.properties carries the TMDb key and the SFTP target for --upload.
## Only the example is written, never the real file: a goblin.properties
## holding the sample host would make --upload dial 192.168.1.50, and a
## reinstall would overwrite credentials you had already filled in.
##
## The TMDb key does not need this file - the TMDB_API_KEY variable of this
## egg covers it, and an empty tmdb.api_key line here does not shadow it.
## ---------------------------------------------------------------
if [ ! -f goblin.properties.example ]; then
    cat > goblin.properties.example <<'PROPEOF'
# Configuration for TheGoblin.
# Copy this file to goblin.properties and fill it in. goblin.properties is
# read from the working directory (/home/container).

# TMDb key for the show ID, year and artwork.
# Only needed if you would rather not use this egg's TMDB_API_KEY variable;
# this file takes priority over it. Leaving the line empty is fine.
tmdb.api_key =

# SFTP target for --upload.
# Key authentication only - a password would be visible in plain text
# in this file and in the process list.
sftp.host = 192.168.1.50
sftp.port = 22
sftp.user = perry
sftp.key  = /home/container/.ssh/id_ed25519
sftp.base = /srv/media/shows
PROPEOF
fi

echo "-> goblin.properties.example written"

## ---------------------------------------------------------------
## 6) Console wrapper
##
## It is the egg's startup command, so without it the server does not come
## up at all. Section 4 copies it out of the checkout; the only way to get
## here without one is an install with no GOBLIN_REPO.
## ---------------------------------------------------------------
if [ -f goblin-console.sh ]; then
    chmod +x goblin-console.sh
    echo "-> goblin-console.sh installed"
else
    echo "!! goblin-console.sh is missing - the server will not start."
    echo "!! It lives at panel/goblin-console.sh in the repository. Either set"
    echo "!! GOBLIN_REPO and reinstall, or upload it to /home/container and"
    echo "!! make it executable."
fi

echo "----------------------------------------"
echo "Installation finished."
echo "yt-dlp: $(bin/yt-dlp --version 2>/dev/null || echo unknown)"
echo "deno:   $(bin/deno --version 2>/dev/null | head -1 || echo 'not installed')"
echo ""
echo "For --upload: rename goblin.properties.example to goblin.properties"
echo "and fill in the SFTP target. The TMDb key already comes from the egg."
echo "----------------------------------------"
