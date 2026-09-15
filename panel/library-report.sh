#!/usr/bin/env bash
#
# Writes an AsciiDoc inventory of a media library: every show and every film
# it holds, what they are encoded at, which audio languages they carry, and -
# for shows - which episodes are not there.
#
# The library is read as Jellyfin lays it out and as goblin's tidy writes it:
# "shows/Name (Year) [tmdbid-N]/Season 01/Name S01E01 - Title.mkv" and
# "movies/Title (Year) [tmdbid-N]/Title (Year).mkv". A folder without a
# "[tmdbid-N]" is listed apart rather than guessed at, since without the id
# there is nothing to compare the episodes against.
#
# What "missing" means: TMDb says how many episodes each season has, and this
# says which of those numbers no file claims. A season with no file at all is
# reported as the whole season rather than as thirteen separate gaps. The
# other direction is reported too - an episode number TMDb does not have,
# which is usually a special filed in the wrong season.
#
# The API key comes from TMDB_API_KEY, or tmdb.api_key in ./goblin.properties.
# One request per show, not per season. Without a key, or without jq, the
# episode counts are left out and the rest is still written.
#
#   ./library-report.sh /srv/media
#   ./library-report.sh --out /tmp/library.adoc /srv/media
#
# $FFPROBE names the binary to read the files with; it should be the one
# Jellyfin uses, for the same reason fix-track-names.sh wants it.
#
# Nothing is written anywhere but the output file.
set -uo pipefail

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    sed -n '2,28p' "$0" | cut -c3-
    exit 0
fi

out=/tmp/media-report.adoc
if [[ "${1:-}" == "--out" ]]; then
    out=${2:?--out needs a path}
    shift 2
fi

root="${1:?Usage: $0 [--out <file>] <media folder>}"
[[ -d "$root" ]] || { echo "Not a directory: $root" >&2; exit 2; }

ffprobe_bin=${FFPROBE:-ffprobe}
command -v "$ffprobe_bin" >/dev/null || { echo "$ffprobe_bin is not installed." >&2; exit 1; }

have_jq=true
command -v jq >/dev/null 2>&1 || have_jq=false

api_key="${TMDB_API_KEY:-}"
if [[ -z "$api_key" && -f goblin.properties ]]; then
    api_key="$(sed -n 's/^[[:space:]]*tmdb\.api_key[[:space:]]*=[[:space:]]*//p' goblin.properties | head -1)"
fi

use_tmdb=true
if [[ -z "$api_key" ]]; then
    echo "No TMDb API key - the report will say what is there, not what is missing." >&2
    use_tmdb=false
elif ! $have_jq; then
    echo "No jq (apt install jq) - the report will say what is there, not what is missing." >&2
    use_tmdb=false
fi

# One field out of an ffprobe "compact" line, by key.
field() {
    local line=$1 key=$2 pair name
    local IFS='|'
    for pair in $line; do
        name=${pair%%=*}
        if [[ "${name,,}" == "$key" ]]; then
            printf '%s' "${pair#*=}"
            return
        fi
    done
}

# Reads one file into the two accumulators the caller declared: resolutions
# and languages, both used as sets keyed by their own value.
declare -A resolutions=()
declare -A languages=()
unreadable=0

read_file() {
    local f=$1 probe line type w h lang
    probe=$("$ffprobe_bin" -v error \
        -show_entries 'stream=codec_type,width,height:stream_tags=language' \
        -of 'compact=p=0:nk=0' "file:$f" 2>/dev/null </dev/null)
    if [[ -z "$probe" ]]; then
        unreadable=$((unreadable + 1))
        return
    fi
    while IFS= read -r line; do
        [[ -z "$line" ]] && continue
        type=$(field "$line" codec_type)
        case "$type" in
            video)
                w=$(field "$line" width)
                h=$(field "$line" height)
                [[ -n "$w" && -n "$h" && "$w" != "N/A" ]] && resolutions["${w}x${h}"]=1
                ;;
            audio)
                lang=$(field "$line" tag:language)
                [[ -z "$lang" || "$lang" == "N/A" ]] && lang=und
                languages["$lang"]=1
                ;;
        esac
    done <<< "$probe"
}

# The keys of an associative array, sorted, comma separated - "-" when empty.
joined() {
    local -n arr=$1
    local out="" key
    if [[ ${#arr[@]} -eq 0 ]]; then
        printf '%s' "-"
        return
    fi
    while IFS= read -r key; do
        [[ -n "$out" ]] && out="$out, "
        out="$out$key"
    done < <(printf '%s\n' "${!arr[@]}" | sort -V)
    printf '%s' "$out"
}

# "1 2 3 5 9 10 11" -> "1-3, 5, 9-11". A run of numbers reads as a run.
ranges() {
    local numbers=$1 n start prev="" out=""
    for n in $numbers; do
        if [[ -z "$prev" ]]; then
            start=$n
        elif [[ $n -ne $((prev + 1)) ]]; then
            [[ -n "$out" ]] && out="$out, "
            out="$out$start"
            [[ $start -ne $prev ]] && out="$out-$prev"
            start=$n
        fi
        prev=$n
    done
    if [[ -n "$prev" ]]; then
        [[ -n "$out" ]] && out="$out, "
        out="$out$start"
        [[ $start -ne $prev ]] && out="$out-$prev"
    fi
    printf '%s' "$out"
}

# An AsciiDoc cell may not carry a bare "|".
cell() {
    printf '%s' "${1//|/\\|}"
}

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
shows_rows=$tmp/shows.adoc
movies_rows=$tmp/movies.adoc
gaps=$tmp/gaps.adoc
strays=$tmp/strays.adoc
: > "$shows_rows"; : > "$movies_rows"; : > "$gaps"; : > "$strays"

tmdb_id_of() {
    local name=$1
    [[ "$name" =~ \[tmdbid-([0-9]+)\] ]] && printf '%s' "${BASH_REMATCH[1]}"
}

show_count=0
movie_count=0

# ---------------------------------------------------------------- shows ----
if [[ -d "$root/shows" ]]; then
    while IFS= read -r -d '' dir <&3; do
        name=$(basename "$dir")
        id=$(tmdb_id_of "$name")

        resolutions=(); languages=(); unreadable=0
        declare -A seen=()      # "season/episode" -> 1
        declare -A seasons=()   # season -> number of files
        files=0; unnumbered=0

        while IFS= read -r -d '' f <&4; do
            files=$((files + 1))
            read_file "$f"
            base=$(basename "$f")
            if [[ "$base" =~ [Ss]([0-9]{1,2})[Ee]([0-9]{1,3}) ]]; then
                season=$((10#${BASH_REMATCH[1]}))
                episode=$((10#${BASH_REMATCH[2]}))
                seen["$season/$episode"]=1
                seasons[$season]=$(( ${seasons[$season]:-0} + 1 ))
            else
                unnumbered=$((unnumbered + 1))
            fi
        done 4< <(find "$dir" -type f \
                      \( -iname '*.mkv' -o -iname '*.mp4' -o -iname '*.m4v' \
                         -o -iname '*.avi' -o -iname '*.mov' \) -print0 | sort -z)

        [[ $files -eq 0 ]] && continue
        show_count=$((show_count + 1))
        echo "  $name ($files files)" >&2

        expected=""
        missing=""
        if $use_tmdb && [[ -n "$id" ]]; then
            json=$(curl -sS "https://api.themoviedb.org/3/tv/$id?api_key=$api_key" 2>/dev/null)
            if jq -e . >/dev/null 2>&1 <<< "$json"; then
                total=0
                while IFS=$'\t' read -r season count; do
                    [[ -z "$season" ]] && continue
                    total=$((total + count))
                    have=""
                    for ((e = 1; e <= count; e++)); do
                        [[ -z "${seen[$season/$e]+x}" ]] && have="$have $e"
                    done
                    if [[ ${seasons[$season]:-0} -eq 0 ]]; then
                        missing="$missing* Season $(printf '%02d' "$season") - all $count episodes"$'\n'
                    elif [[ -n "$have" ]]; then
                        missing="$missing* Season $(printf '%02d' "$season") - $(ranges "$have")"$'\n'
                    fi
                done < <(jq -r '.seasons[]? | select(.season_number > 0)
                                | "\(.season_number)\t\(.episode_count)"' <<< "$json")
                expected=$total

                # The other direction: a number no season of this show has.
                over=""
                while IFS= read -r key; do
                    season=${key%%/*}; episode=${key##*/}
                    [[ "$season" == 0 ]] && continue
                    count=$(jq -r --argjson s "$season" \
                        '.seasons[]? | select(.season_number == $s) | .episode_count' <<< "$json")
                    [[ -z "$count" ]] && count=0
                    [[ $episode -gt $count ]] \
                        && over="$over* S$(printf '%02d' "$season")E$(printf '%02d' "$episode") - TMDb has no such episode"$'\n'
                done < <(printf '%s\n' "${!seen[@]}" | sort -t/ -k1,1n -k2,2n)
                missing="$missing$over"
            fi
        fi

        counted=${#seen[@]}
        held="$counted"
        [[ -n "$expected" ]] && held="$counted of $expected"
        note=""
        [[ $unnumbered -gt 0 ]] && note="$unnumbered without S/E"
        [[ $unreadable -gt 0 ]] && note="${note:+$note, }$unreadable unreadable"

        {
            printf '| %s\n' "$(cell "$name")"
            printf '| %s\n' "$held"
            printf '| %s\n' "$(cell "$(joined resolutions)")"
            printf '| %s\n' "$(cell "$(joined languages)")"
            printf '| %s\n\n' "$(cell "${note:--}")"
        } >> "$shows_rows"

        if [[ -n "$missing" ]]; then
            {
                printf '=== %s\n\n' "$(cell "$name")"
                printf '%s\n' "$missing"
            } >> "$gaps"
        fi
        if [[ -z "$id" ]]; then
            printf '* shows/%s\n' "$(cell "$name")" >> "$strays"
        fi
        unset seen seasons
    done 3< <(find "$root/shows" -mindepth 1 -maxdepth 1 -type d -print0 | sort -z)
fi

# --------------------------------------------------------------- movies ----
if [[ -d "$root/movies" ]]; then
    while IFS= read -r -d '' dir <&3; do
        name=$(basename "$dir")
        id=$(tmdb_id_of "$name")

        resolutions=(); languages=(); unreadable=0
        files=0
        while IFS= read -r -d '' f <&4; do
            files=$((files + 1))
            read_file "$f"
        done 4< <(find "$dir" -type f \
                      \( -iname '*.mkv' -o -iname '*.mp4' -o -iname '*.m4v' \
                         -o -iname '*.avi' -o -iname '*.mov' \) -print0 | sort -z)

        [[ $files -eq 0 ]] && continue
        movie_count=$((movie_count + 1))
        echo "  $name ($files files)" >&2

        note=""
        [[ $files -gt 1 ]] && note="$files files"
        [[ $unreadable -gt 0 ]] && note="${note:+$note, }$unreadable unreadable"

        {
            printf '| %s\n' "$(cell "$name")"
            printf '| %s\n' "$(cell "$(joined resolutions)")"
            printf '| %s\n' "$(cell "$(joined languages)")"
            printf '| %s\n\n' "$(cell "${note:--}")"
        } >> "$movies_rows"

        [[ -z "$id" ]] && printf '* movies/%s\n' "$(cell "$name")" >> "$strays"
    done 3< <(find "$root/movies" -mindepth 1 -maxdepth 1 -type d -print0 | sort -z)
fi

# --------------------------------------------------------------- output ----
{
    printf '= Media library\n'
    printf ':toc: left\n'
    printf ':toclevels: 2\n\n'
    printf '%s, read from `%s`.\n' "$(date '+%Y-%m-%d %H:%M')" "$root"
    printf '%s, %s.\n\n' \
        "$show_count $([[ $show_count -eq 1 ]] && echo show || echo shows)" \
        "$movie_count $([[ $movie_count -eq 1 ]] && echo film || echo films)"
    if ! $use_tmdb; then
        printf 'NOTE: No TMDb lookup was made, so nothing is said about episodes '
        printf 'that are not there.\n\n'
    fi

    if [[ -s "$shows_rows" ]]; then
        printf '== Shows\n\n'
        printf '[cols="4,1,2,2,2",options="header"]\n|===\n'
        printf '| Show | Episodes | Resolution | Audio | Note\n\n'
        cat "$shows_rows"
        printf '|===\n\n'
    fi

    if [[ -s "$movies_rows" ]]; then
        printf '== Films\n\n'
        printf '[cols="4,2,2,2",options="header"]\n|===\n'
        printf '| Film | Resolution | Audio | Note\n\n'
        cat "$movies_rows"
        printf '|===\n\n'
    fi

    if [[ -s "$gaps" ]]; then
        printf '== What is not there\n\n'
        printf 'Episode numbers TMDb lists and no file claims.\n\n'
        cat "$gaps"
    fi

    if [[ -s "$strays" ]]; then
        printf '== Without a TMDb id\n\n'
        printf 'Nothing is said about what these are missing: without the id there is\n'
        printf 'nothing to compare them against.\n\n'
        cat "$strays"
        printf '\n'
    fi
} > "$out"

echo >&2
echo "Written to $out" >&2
