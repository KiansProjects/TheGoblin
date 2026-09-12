#!/usr/bin/env bash
#
# Cleans up whatever title a file carries, using only the "S01E02" already
# in its name - with a TMDb key, the real title; without one, just the
# ripper's fragment stripped out.
#
# The counterpart to tidy-shows.sh, for after that one has sorted files by
# number but left the ripper's own title fragment in place ("hottub",
# "gaybash"). Jellyfin does not need any of that in the file name at all -
# it shows the real episode title from its own TMDb lookup once season and
# episode are right, whatever the file is called. This is for the file
# name itself, for browsing outside Jellyfin. Only the S01E02 in the name
# matters; whatever follows it is discarded and rebuilt, so it works
# whether the file still carries the raw title or one from an earlier run
# of this same script.
#
# Reads the TMDb ID from the folder name's "[tmdbid-N]" suffix. The API key
# comes from TMDB_API_KEY, or tmdb.api_key in ./goblin.properties. One
# request per season, not per episode - every file in the same season
# shares it. No key, no jq on the machine, or TMDb has nothing for that
# episode - the title is just dropped instead ("Show S01E01.mkv") rather
# than failing.
#
#   ./tmdb-titles.sh "/path/to/Some Show (2004) [tmdbid-1234]"
#   ./tmdb-titles.sh --apply "/path/to/Some Show (2004) [tmdbid-1234]"
#
# Nothing is written without --apply.
set -euo pipefail

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    sed -n '2,22p' "$0" | cut -c3-
    exit 0
fi

apply=false
if [[ "${1:-}" == "--apply" ]]; then
    apply=true
    shift
fi

dir="${1:?Usage: $0 [--apply] <show folder>}"

have_jq=true
command -v jq >/dev/null 2>&1 || have_jq=false

api_key="${TMDB_API_KEY:-}"
if [[ -z "$api_key" && -f goblin.properties ]]; then
    api_key="$(sed -n 's/^[[:space:]]*tmdb\.api_key[[:space:]]*=[[:space:]]*//p' goblin.properties | head -1)"
fi

use_tmdb=true
if [[ -z "$api_key" ]]; then
    echo "No TMDb API key - dropping titles instead of looking them up."
    echo "Set TMDB_API_KEY (or tmdb.api_key in ./goblin.properties) for the real ones."
    echo
    use_tmdb=false
elif ! $have_jq; then
    echo "No jq (apt install jq / dnf install jq) - dropping titles instead of looking them up."
    echo
    use_tmdb=false
fi

folder_name="$(basename "$dir")"
if [[ "$folder_name" =~ \[tmdbid-([0-9]+)\] ]]; then
    tmdb_id="${BASH_REMATCH[1]}"
elif $use_tmdb; then
    echo "No [tmdbid-N] in the folder name, nothing to look episodes up against: $folder_name"
    echo "Dropping titles instead."
    echo
    use_tmdb=false
fi


# Strip any trailing "[...]" and "(...)" group, not only "[tmdbid-N]" - a
# folder tagged "[tmdb-N]" (missing the "id") or anything else would
# otherwise drag its whole bracket suffix into every episode's file name.
show="$folder_name"
show="${show% \[*\]}"
show="${show% (*)}"
# The same characters Naming.sanitize() strips on the Java side - a colon
# in a show name ("Batman: The Animated Series") is common and fatal on
# some filesystems (SMB shares, exFAT) if left in a file name.
show="$(printf '%s' "$show" | sed -e 's#[/\\:*?"<>|]##g' -e 's/  */ /g' -e 's/^ *//' -e 's/ *$//')"

video_ext_pattern='\.(mkv|mp4|avi|m4v|mov|ts|wmv|mpg|mpeg)$'

declare -A season_json

renamed=0
already=0

while IFS= read -r -d '' file <&3; do
    name="$(basename "$file")"
    [[ "$name" =~ $video_ext_pattern ]] || continue
    [[ "$name" =~ [Ss]([0-9]{1,2})[Ee]([0-9]{1,3}) ]] || continue

    season_num=$((10#${BASH_REMATCH[1]}))
    episode_num=$((10#${BASH_REMATCH[2]}))

    clean_title=""
    if $use_tmdb; then
        if [[ -z "${season_json[$season_num]+x}" ]]; then
            url="https://api.themoviedb.org/3/tv/${tmdb_id}/season/${season_num}?api_key=${api_key}"
            response="$(curl -sS "$url")"
            if ! jq -e . >/dev/null 2>&1 <<< "$response"; then
                echo "TMDb did not return usable JSON for season ${season_num}: $response" >&2
                exit 1
            fi
            if jq -e '.success == false' >/dev/null 2>&1 <<< "$response"; then
                echo "TMDb error: $(jq -r '.status_message' <<< "$response")" >&2
                exit 1
            fi
            season_json[$season_num]="$response"
        fi

        title="$(jq -r --argjson ep "$episode_num" \
            '.episodes[]? | select(.episode_number == $ep) | .name' \
            <<< "${season_json[$season_num]}")"

        if [[ -z "$title" || "$title" == "null" ]]; then
            echo "no TMDb title for S$(printf '%02d' "$season_num")E$(printf '%02d' "$episode_num"), left as just the number: $name"
        else
            # The same illegal-character stripping Naming.sanitize does.
            clean_title="$(printf '%s' "$title" | tr -d '\000-\037' \
                | sed -e 's#[/\\:*?"<>|]##g' -e 's/  */ /g' -e 's/^ *//' -e 's/ *$//' -e 's/\.*$//')"
        fi
    fi

    ext="${name##*.}"
    dest_name="$show S$(printf '%02d' "$season_num")E$(printf '%02d' "$episode_num")"
    [[ -n "$clean_title" ]] && dest_name="$dest_name - $clean_title"
    dest="$(dirname "$file")/$dest_name.$ext"

    if [[ "$(realpath -m "$file")" == "$(realpath -m "$dest")" ]]; then
        already=$((already + 1))
        continue
    fi

    echo "$name"
    echo "    -> $(basename "$dest")"

    if $apply; then
        if [[ -e "$dest" ]]; then
            echo "    exists, skipped"
            continue
        fi
        mv -n -- "$file" "$dest"
    fi
    renamed=$((renamed + 1))
done 3< <(find "$dir" -type f -print0)

echo
echo "$already already have their name."
if $apply; then
    echo "Renamed $renamed files."
else
    echo "$renamed to rename. Dry run, nothing renamed. Re-run with --apply."
fi
