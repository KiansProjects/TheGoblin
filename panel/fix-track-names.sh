#!/usr/bin/env bash
#
# Gives every audio and subtitle track in a library the same name in Jellyfin.
#
# The one-off counterpart to 'goblin tracks', for the machine the files
# actually live on. That machine has ffmpeg - it is a media server - but it
# does not necessarily have a Java runtime, and installing one to correct a
# few metadata fields is a poor trade. The rules are the ones in
# Tracks.java; that file is the reference, this is the copy that travels.
#
# Jellyfin assembles the line in the track picker out of the stream itself:
#
#   [title] - language - profile|codec - channel layout - default - external
#
# so the only part a file controls is the title. Three things go wrong, and
# this corrects those three:
#
#   - A title nobody wrote. Jellyfin falls back to the container's handler
#     name, and anything off YouTube says "ISO Media file produced by Google
#     Inc." there.
#   - A title that only repeats the rest. "Stereo" says nothing the channel
#     layout does not. Dropped only when every word in it is already among
#     the derived attributes, so "Commentary" stays.
#   - A missing language. Without it the first field disappears.
#
# Two audio tracks marked default, or none, is corrected to the first one.
#
#   ./fix-track-names.sh /srv/media/movies            # only report
#   ./fix-track-names.sh --apply /srv/media/movies /srv/media/shows
#   ./fix-track-names.sh --apply --lang deu /srv/media/shows/Tatort
#   ./fix-track-names.sh --apply --name-tracks /srv/media/movies
#
# --name-tracks names every track after its channel layout instead of leaving
# it to the attributes: "Surround 5.1 - English - AAC" rather than
# "English - AAC - 5.1". Jellyfin puts the title first and drops every
# attribute the title already contains, so that is the only way the word
# "Surround" can appear at all. A title that says something the attributes
# cannot - Commentary, a cut - is kept either way.
#
# Nothing is written without --apply. A file that needs nothing is never
# opened for writing. The correction is a remux with -c copy, so nothing is
# re-encoded, but the file is written once more beside itself and moved over:
# the largest file has to fit on the disk twice. Owner, group and mode are
# carried over, so a run as root does not hand the library to root. The
# modification time is not: Jellyfin re-reads a file only when that time has
# changed, so keeping it would hide the correction from the server.

set -uo pipefail

apply=0
name_tracks=0
want_lang=eng
roots=()

while [ $# -gt 0 ]; do
    case "$1" in
        --apply)   apply=1 ;;
        --name-tracks)    name_tracks=1 ;;
        --no-name-tracks) name_tracks=0 ;;
        --lang)    want_lang=$(printf '%s' "${2:-}" | tr 'A-Z' 'a-z'); shift ;;
        --no-lang) want_lang= ;;
        -h|--help) sed -n '2,45p' "$0" | cut -c3-; exit 0 ;;
        -*)        echo "Unknown option: $1" >&2; exit 2 ;;
        *)         roots+=("$1") ;;
    esac
    shift
done

if [ ${#roots[@]} -eq 0 ]; then
    echo "Usage: $0 [--apply] [--lang <code>|--no-lang] [--name-tracks] <folder|file> ..." >&2
    exit 2
fi

for tool in ffprobe ffmpeg; do
    command -v "$tool" >/dev/null || { echo "$tool is not installed." >&2; exit 1; }
done

# Words that carry nothing Jellyfin does not already print.
filler="audio track tracks sound surround channel channels ch stream"

# What ffmpeg writes when nobody set a handler name, and what Jellyfin ignores.
default_handler() { [ "$1" = audio ] && echo soundhandler || echo subtitlehandler; }

consistent=0; listed=0; rewritten=0; failed=0; unreadable=0

# One field out of one ffprobe -of compact line, whatever its spelling.
#
# Matroska keeps its tags in capitals, so a file remuxed out of an MP4 carries
# TAG:HANDLER_NAME where the MP4 had tag:handler_name. Jellyfin looks these up
# without regard for case and shows the value either way; a lookup here that
# insists on lowercase calls the file clean and leaves the junk on screen.
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

# The spellings of one tag actually present on this stream, so clearing it can
# name them. Falls back to the canonical one when the tag is not there at all.
spellings() {
    local line=$1 key=$2 pair name found=
    local IFS='|'
    for pair in $line; do
        name=${pair%%=*}
        case "$name" in tag:*) ;; *) continue ;; esac
        if [ "${name,,}" = "tag:$key" ]; then
            found="$found ${name#tag:}"
        fi
    done
    [ -z "$found" ] && found=" $key"
    printf '%s' "$found"
}

# Lowercase words, keeping the dot that holds "5.1" together.
words() {
    printf '%s' "$1" | tr 'A-Z' 'a-z' | tr -c 'a-z0-9.' ' ' | tr -s ' '
}

# Whether the title only repeats what Jellyfin prints anyway. $2 is the
# derived vocabulary, space separated.
repeats_only() {
    local title=$1 derived=" $2 " word saw=0
    for word in $(words "$title"); do
        word=${word#.}; word=${word%.}
        [ -z "$word" ] && continue
        case "$derived" in *" $word "*) saw=1 ;; *) return 1 ;; esac
    done
    [ "$saw" = 1 ]
}

# The name a track would carry if it were named after what it is. Empty for a
# track there is no obvious word for - a subtitle, or a layout nobody names.
canonical() {
    local channels=$1 layout=$2 plain
    case "$channels" in
        1) printf 'Mono';   return ;;
        2) printf 'Stereo'; return ;;
    esac
    [ -z "$layout" ] && return
    # ffprobe writes 5.1(side) for one of the two ways to arrange six
    # speakers. That distinction belongs in a codec, not in a track name.
    plain=${layout%%(*}
    case "$plain" in *.*) printf 'Surround %s' "$plain" ;; esac
}

fix_file() {
    local f=$1 probe line
    probe=$(ffprobe -v error \
        -show_entries 'stream=index,codec_type,codec_name,profile,channels,channel_layout:stream_tags:stream_disposition=default' \
        -of 'compact=p=0:nk=0' "file:$f" 2>/dev/null </dev/null)
    if [ $? -ne 0 ] || [ -z "$probe" ]; then
        echo "  unreadable, left alone: $f"
        unreadable=$((unreadable + 1))
        return
    fi

    local -a args=() notes=() audio=()
    local audio_defaults=0

    while IFS= read -r line; do
        [ -z "$line" ] && continue
        local type idx codec profile layout channels title name handler lang isdef
        type=$(field "$line" codec_type)
        case "$type" in audio|subtitle) ;; *) continue ;; esac

        idx=$(field "$line" index)
        codec=$(field "$line" codec_name)
        profile=$(field "$line" profile)
        layout=$(field "$line" channel_layout)
        channels=$(field "$line" channels)
        title=$(field "$line" tag:title)
        name=$(field "$line" tag:name)
        handler=$(field "$line" tag:handler_name)
        lang=$(field "$line" tag:language)
        isdef=$(field "$line" disposition:default)

        if [ "$type" = audio ]; then
            audio+=("$idx")
            [ "$isdef" = 1 ] && audio_defaults=$((audio_defaults + 1))
        fi

        # 1. The title, in the order Jellyfin resolves it.
        local shown=$title
        [ -z "$shown" ] && shown=$name
        local redundant= junk= why= wanted= key spelling
        if [ -n "$shown" ]; then
            local derived="$filler $(words "$layout") $(words "$codec") $(words "$profile") $(words "$lang")"
            case "$channels" in
                ''|0|N/A) ;;
                *) derived="$derived $channels.0 $((channels - 1)).1" ;;
            esac
            if repeats_only "$shown" "$derived"; then
                redundant=1; why="\"$shown\" repeats what is derived anyway"
            fi
        elif [ -n "$handler" ] \
             && [ "${handler,,}" != "$(default_handler "$type")" ]; then
            junk=1; why="\"$handler\" is the container's handler name"
        fi

        [ "$name_tracks" = 1 ] && wanted=$(canonical "$channels" "$layout")

        if [ -n "$shown" ] && [ -z "$redundant" ]; then
            # A name the attributes cannot give - Commentary, a cut, an audio
            # description. Left as it is, scheme or no scheme: replacing it
            # with "Surround 5.1" throws away the only thing worth reading.
            :
        elif [ -n "$wanted" ]; then
            if [ "$title" != "$wanted" ] || [ -n "$name" ] || [ -n "$junk" ]; then
                args+=(-metadata:s:"$idx" "title=$wanted")
                for key in name handler_name; do
                    for spelling in $(spellings "$line" "$key"); do
                        args+=(-metadata:s:"$idx" "$spelling=")
                    done
                done
                notes+=("$(printf '%-8s %2s  title -> "%s"' "$type" "$idx" "$wanted")")
            fi
        elif [ -n "$redundant" ] || [ -n "$junk" ]; then
            for key in title name handler_name; do
                for spelling in $(spellings "$line" "$key"); do
                    args+=(-metadata:s:"$idx" "$spelling=")
                done
            done
            notes+=("$(printf '%-8s %2s  title -> none, %s' "$type" "$idx" "$why")")
        fi

        # 2. The language.
        case "$(printf '%s' "$lang" | tr 'A-Z' 'a-z')" in
            ''|und|undefined|unknown)
                if [ -n "$want_lang" ]; then
                    args+=(-metadata:s:"$idx" language="$want_lang")
                    notes+=("$(printf '%-8s %2s  language -> %s' "$type" "$idx" "$want_lang")")
                else
                    notes+=("$(printf '%-8s %2s  no language' "$type" "$idx")")
                fi
                ;;
        esac
    done <<EOF
$probe
EOF

    # 3. Exactly one default audio track. Only the broken cases - which of two
    #    sensible candidates should be the default is not this script's call.
    if [ ${#audio[@]} -gt 0 ] && [ "$audio_defaults" != 1 ]; then
        local i
        for i in "${audio[@]}"; do
            if [ "$i" = "${audio[0]}" ]; then
                args+=(-disposition:"$i" default)
            else
                args+=(-disposition:"$i" 0)
            fi
        done
        notes+=("$(printf '%-8s %2s  %s tracks marked default -> only this one' \
            audio "${audio[0]}" "$audio_defaults")")
    fi

    if [ ${#notes[@]} -eq 0 ]; then
        consistent=$((consistent + 1))
        return
    fi

    echo "  ${f##*/}"
    printf '    %s\n' "${notes[@]}"
    listed=$((listed + 1))

    [ ${#args[@]} -eq 0 ] && return
    [ "$apply" = 0 ] && return

    local ext=${f##*.} tmp
    tmp="$f.tracks.$ext"
    local -a extra=()
    case "$(printf '%s' "$ext" | tr 'A-Z' 'a-z')" in
        mp4|m4v|mov) extra=(-movflags +faststart) ;;
    esac

    if ffmpeg -nostdin -hide_banner -loglevel error -y -i "file:$f" -map 0 -c copy \
            "${args[@]}" "${extra[@]}" "file:$tmp"; then
        # Onto the new file before it takes the old one's place, or a rewrite
        # as root hands the library to root. Not the modification time -
        # Jellyfin reads a file again only when that has changed.
        chown --reference="$f" "$tmp" 2>/dev/null
        chmod --reference="$f" "$tmp" 2>/dev/null
        mv -f "$tmp" "$f"
        rewritten=$((rewritten + 1))
    else
        rm -f "$tmp"
        echo "    failed, left as it was"
        failed=$((failed + 1))
    fi
}

[ "$apply" = 0 ] && echo "Dry run: nothing is written." && echo

for root in "${roots[@]}"; do
    if [ -f "$root" ]; then
        fix_file "$root"
        continue
    fi
    if [ ! -d "$root" ]; then
        echo "No such file or directory: $root" >&2
        continue
    fi
    # Both ffprobe and ffmpeg read standard input, and standard input here is
    # the list of file names still to come. Without cutting them off they eat
    # the front of the next path and that file is reported as unreadable.
    while IFS= read -r -d '' f <&3; do
        fix_file "$f"
    done 3< <(find "$root" -type f \
                  \( -iname '*.mkv' -o -iname '*.mp4' -o -iname '*.m4v' -o -iname '*.mov' \) \
                  ! -name '.*' ! -name '*.tracks.*' -print0 | sort -z)
done

echo
echo "$listed to correct, $consistent already consistent."
[ "$unreadable" -gt 0 ] && echo "$unreadable could not be read and were left alone."
if [ "$apply" = 0 ]; then
    [ "$listed" -gt 0 ] && { echo; echo "Nothing was touched. Run again with --apply."; }
else
    echo "$rewritten rewritten."
    [ "$failed" -gt 0 ] && echo "$failed failed and were left as they were."
fi
[ "$failed" -gt 0 ] && exit 1
exit 0
