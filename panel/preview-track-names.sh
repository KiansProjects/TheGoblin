#!/usr/bin/env bash
#
# Shows what Jellyfin's track picker would print for every audio track in a
# library, without touching a single file. The read-only counterpart to
# fix-track-names.sh, for checking its work: does a title survive as shown,
# or does it drop out as redundant, leaving language first?
#
# Jellyfin assembles the line as:
#
#   [title] - language - profile|codec - channel layout - default - external
#
# This reproduces the title/language/codec/channel-layout part with the same
# redundancy check fix-track-names.sh uses, so the two never disagree about
# whether a title is dropped. It does not reproduce the trailing
# default/external badge - that does not depend on anything a title could
# hide, so it does not matter for what this script checks.
#
#   ./preview-track-names.sh /srv/media/shows
#   ./preview-track-names.sh /srv/media/shows/Pokemon\ \(1997\)\ \[tmdbid-60572\]
#
# $FFPROBE names the binary to use, and it should be the one Jellyfin uses:
# a track name kept in an MP4 udta box is reported by ffprobe 8.x and not by
# older builds, so a preview taken with an older one shows a line Jellyfin
# does not print.
#
#   FFPROBE=/opt/jellyfin/ffmpeg/ffprobe ./preview-track-names.sh /srv/media

set -uo pipefail

if [ $# -eq 0 ]; then
    echo "Usage: $0 <folder|file> ..." >&2
    exit 2
fi

ffprobe_bin=${FFPROBE:-ffprobe}
command -v "$ffprobe_bin" >/dev/null || { echo "$ffprobe_bin is not installed." >&2; exit 1; }

# Words that carry nothing Jellyfin does not already print. Kept in sync with
# fix-track-names.sh.
filler="audio track tracks sound surround channel channels ch stream"

field() {
    local line=$1 key=$2 pair name
    local IFS='|'
    for pair in $line; do
        name=${pair%%=*}
        if [ "${name,,}" = "$key" ]; then
            printf '%s' "${pair#*=}"
            return
        fi
    done
}

words() {
    printf '%s' "$1" | tr 'A-Z' 'a-z' | tr -c 'a-z0-9.' ' ' | tr -s ' '
}

repeats_only() {
    local title=$1 derived=" $2 " word saw=0
    for word in $(words "$title"); do
        word=${word#.}; word=${word%.}
        [ -z "$word" ] && continue
        case "$derived" in *" $word "*) saw=1 ;; *) return 1 ;; esac
    done
    [ "$saw" = 1 ]
}

canonical() {
    local channels=$1 layout=$2 plain
    case "$channels" in
        1) printf 'Mono';   return ;;
        2) printf 'Stereo'; return ;;
    esac
    [ -z "$layout" ] && return
    plain=${layout%%(*}
    case "$plain" in *.*) printf 'Surround %s' "$plain" ;; esac
}

lang_name() {
    case "${1,,}" in
        eng) echo English ;; deu|ger) echo German ;; jpn) echo Japanese ;;
        fra|fre) echo French ;; spa) echo Spanish ;; ita) echo Italian ;;
        por) echo Portuguese ;; rus) echo Russian ;; kor) echo Korean ;;
        chi|zho) echo Chinese ;; ''|und|undefined) echo "" ;;
        *) echo "$1" ;;
    esac
}

codec_name() {
    case "${1,,}" in
        aac) echo AAC ;; ac3) echo AC3 ;; eac3) echo "E-AC-3" ;;
        dts) echo DTS ;; flac) echo FLAC ;; mp3) echo MP3 ;;
        opus) echo Opus ;; truehd) echo TrueHD ;; *) echo "${1^^}" ;;
    esac
}

preview_file() {
    local f=$1 probe line
    probe=$("$ffprobe_bin" -v error -select_streams a \
        -show_entries 'stream=index,codec_name,channels,channel_layout:stream_tags' \
        -of 'compact=p=0:nk=0' "file:$f" 2>/dev/null </dev/null)
    [ -z "$probe" ] && return

    while IFS= read -r line; do
        [ -z "$line" ] && continue
        local codec layout channels title name lang shown derived
        codec=$(field "$line" codec_name)
        layout=$(field "$line" channel_layout)
        channels=$(field "$line" channels)
        title=$(field "$line" tag:title)
        name=$(field "$line" tag:name)
        lang=$(field "$line" tag:language)

        # The order Jellyfin resolves the title in: the title tag, then the
        # name tag it falls back to.
        shown=$title
        [ -z "$shown" ] && shown=$name
        if [ -n "$shown" ]; then
            derived="$filler $(words "$layout") $(words "$codec") $(words "$lang")"
            case "$channels" in
                ''|0|N/A) ;;
                *) derived="$derived $channels.0 $((channels - 1)).1" ;;
            esac
            derived="$derived $(words "$(canonical "$channels" "$layout")")"
            repeats_only "$shown" "$derived" && shown=""
        fi

        local lname cname chname parts=()
        lname=$(lang_name "$lang")
        cname=$(codec_name "$codec")
        chname=$(canonical "$channels" "$layout")

        [ -n "$shown" ] && parts+=("$shown")
        [ -n "$lname" ] && parts+=("$lname")
        [ -n "$cname" ] && parts+=("$cname")
        [ -n "$chname" ] && parts+=("$chname")

        # Not "${parts[*]}" with IFS=' - ': bash joins on the first character
        # of IFS and the rest of it is never seen.
        local shownline= part
        for part in "${parts[@]}"; do
            [ -n "$shownline" ] && shownline="$shownline - "
            shownline="$shownline$part"
        done
        printf '%-90s  %s\n' "${f##*/}" "$shownline"
    done <<EOF
$probe
EOF
}

for root in "$@"; do
    if [ -f "$root" ]; then
        preview_file "$root"
        continue
    fi
    if [ ! -d "$root" ]; then
        echo "No such file or directory: $root" >&2
        continue
    fi
    while IFS= read -r -d '' f <&3; do
        preview_file "$f"
    done 3< <(find "$root" -type f \
                  \( -iname '*.mkv' -o -iname '*.mp4' -o -iname '*.m4v' -o -iname '*.mov' \) \
                  ! -name '.*' ! -name '*.tracks.*' -print0 | sort -z)
done
