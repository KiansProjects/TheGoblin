#!/bin/bash
# Turns the Panel console into the Goblin prompt.
#
# The installer copies this file to /home/container. A change here therefore
# only reaches a server on reinstall - update_from_git below rebuilds the jar
# on every start, but never this script: bash reads a running script lazily by
# byte offset, so replacing it underneath itself corrupts the running shell.
cd /home/container
export PATH="/home/container/bin:${PATH}"
export TMDB_API_KEY
export YTDLP_ARGS

JAR=/home/container/goblin.jar

# Pulls the current state from the repo and rebuilds. If anything fails the
# existing jar stays put and the server starts anyway - a broken push should
# not take your server down.
update_from_git() {
    if [ -z "${GOBLIN_REPO}" ]; then
        return 0
    fi
    if [ "${AUTO_UPDATE}" = "0" ]; then
        echo "Auto-update is off, using the existing jar."
        return 0
    fi

    local branch="${GOBLIN_BRANCH:-main}"
    echo "Fetching ${branch} from ${GOBLIN_REPO} ..."

    rm -rf .src .classes
    if ! git clone --quiet --depth 1 --branch "${branch}" "${GOBLIN_REPO}" .src; then
        echo "Clone failed. Starting with the existing jar."
        rm -rf .src
        return 0
    fi

    local rev
    rev=$(git -C .src rev-parse --short HEAD 2>/dev/null || echo "?")

    mkdir -p .classes
    if ! javac -d .classes $(find .src/src -name "*.java"); then
        echo "Build failed at ${rev}. Starting with the existing jar."
        rm -rf .src .classes
        return 0
    fi

    jar --create --file goblin.jar.new \
        --main-class space.perrys.goblin.Goblin \
        -C .classes .
    mv -f goblin.jar.new "${JAR}"
    rm -rf .src .classes
    echo "Built from ${branch} @ ${rev}"
}

run() {
    local cmd="$1"
    shift
    case "${cmd}" in
        shows|movie|chapters|playlist|concat|audio|tidy|cbz|queue)
            if [ ! -f "${JAR}" ]; then
                echo "goblin.jar is missing."
                return
            fi
            # 'queue' with no arguments becomes the worker and reads the
            # console itself until 'quit'. This loop is suspended meanwhile,
            # so the two never read the same line.
            java -jar "${JAR}" "${cmd}" "$@"
            ;;
        help|--help|-h)
            echo "shows <url> <name> [options]      split a video into episodes"
            echo "movie <url> <title> [options]     store a video as a single movie"
            echo "playlist <url> <name>             print ready-made shows lines"
            echo "playlist <url> <name> --episodes  one video per episode"
            echo "concat <url> <title> [--movie]    join a playlist into one file"
            echo "audio <url> [--musicbrainz]       store the audio track as music"
            echo "chapters <url> [--formats]        show chapters or available formats"
            echo "tidy <folder> [--apply]           sort an inbox into the library"
            echo "cbz <folder> [--apply]            repack .cbr comics as .cbz"
            echo "queue                             work through commands one at a time"
            echo "queue add <command>               append one job"
            echo "rebuild                           rebuild from the repo"
            echo "ls [path]                         list a directory, default here"
            echo "pwd                               show the working directory"
            echo "disk [path]                       show used space, default output"
            echo "update                            update yt-dlp"
            echo "stop                              shut the server down"
            ;;
        rebuild) update_from_git ;;
        # A path makes this the way to check where you actually are before
        # blaming a command for not finding a folder.
        ls)      ls -lh "${1:-.}" | head -100 ;;
        pwd)     pwd ;;
        disk)    du -sh "${1:-output}" ;;
        update)  bin/yt-dlp -U ;;
        stop|exit|quit) echo "Bye."; exit 0 ;;
        *)       echo "Unknown command: ${cmd} (help for the list)" ;;
    esac
}

# xargs splits the line honouring quotes without executing it. That makes
# titles with spaces work without needing eval.
split() {
    printf '%s' "$1" | xargs -n1 printf '%s\n' 2>/dev/null
}

update_from_git

echo "TheGoblin ready."
echo "Type 'help' for the commands."

if [ -n "${AUTO_COMMAND}" ]; then
    echo ":/home/container$ ${AUTO_COMMAND}"
    mapfile -t args < <(split "${AUTO_COMMAND}")
    [ ${#args[@]} -gt 0 ] && run "${args[@]}"
fi

while IFS= read -r line; do
    [ -z "${line//[[:space:]]/}" ] && continue
    mapfile -t args < <(split "${line}")
    [ ${#args[@]} -eq 0 ] && continue
    run "${args[@]}"
done
