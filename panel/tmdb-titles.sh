#!/usr/bin/env bash
#
# Replaces whatever title a file carries with the real one from TMDb,
# using only the "S01E02" already in its name.
#
# The counterpart to tidy-shows.sh, for after that one has sorted files by
# number but left the ripper's own title fragment in place ("hottub",
# "gaybash") - this looks up the real title and renames on top of it. Only
# the S01E02 in the name matters; whatever follows it is discarded and
# rebuilt, so it works whether the file still carries the raw title or one
# from an earlier run of this same script.
#
# Reads the TMDb ID from the folder name's "[tmdbid-N]" suffix. The API key
# comes from TMDB_API_KEY, or tmdb.api_key in ./goblin.properties. One
# request per season, not per episode - every file in the same season
# shares it.
#
#   ./tmdb-titles.sh "/path/to/Some Show (2004) [tmdbid-1234]"
#   ./tmdb-titles.sh --apply "/path/to/Some Show (2004) [tmdbid-1234]"
#
# Needs jq to read TMDb's response. Nothing is written without --apply.
set -euo pipefail

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    sed -n '2,20p' "$0" | cut -c3-
    exit 0
fi

apply=false
if [[ "${1:-}" == "--apply" ]]; then
    apply=true
    shift
fi

dir="${1:?Usage: $0 [--apply] <show folder>}"

if ! command -v jq >/dev/null 2>&1; then
    echo "jq is required to read TMDb's response (apt install jq / dnf install jq)." >&2
    exit 1
fi

api_key="${TMDB_API_KEY:-}"
if [[ -z "$api_key" && -f goblin.properties ]]; then
    api_key="$(sed -n 's/^[[:space:]]*tmdb\.api_key[[:space:]]*=[[:space:]]*//p' goblin.properties | head -1)"
fi
if [[ -z "$api_key" ]]; then
    echo "No TMDb API key - set TMDB_API_KEY, or put tmdb.api_key in ./goblin.properties." >&2
    exit 1
fi

folder_name="$(basename "$dir")"
if [[ ! "$folder_name" =~ \[tmdbid-([0-9]+)\] ]]; then
    echo "No [tmdbid-N] in the folder name, nothing to look episodes up against: $folder_name" >&2
    exit 1
fi
tmdb_id="${BASH_REMATCH[1]}"

show="${folder_name% \[tmdbid-*\]}"
show="${show% (*)}"

video_ext_pattern='\.(mkv|mp4|avi|m4v|mov|ts|wmv|mpg|mpeg)$'

declare -A season_json

renamed=0
skipped=0
already=0

while IFS= read -r -d '' file <&3; do
    name="$(basename "$file")"
    [[ "$name" =~ $video_ext_pattern ]] || continue
    [[ "$name" =~ [Ss]([0-9]{1,2})[Ee]([0-9]{1,3}) ]] || continue

    season_num=$((10#${BASH_REMATCH[1]}))
    episode_num=$((10#${BASH_REMATCH[2]}))

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
        echo "no TMDb title for S$(printf '%02d' "$season_num")E$(printf '%02d' "$episode_num"), left alone: $name"
        skipped=$((skipped + 1))
        continue
    fi

    # The same illegal-character stripping Naming.sanitize does.
    clean_title="$(printf '%s' "$title" | tr -d '\000-\037' \
        | sed -e 's#[/\\:*?"<>|]##g' -e 's/  */ /g' -e 's/^ *//' -e 's/ *$//' -e 's/\.*$//')"

    ext="${name##*.}"
    dest="$(dirname "$file")/$show S$(printf '%02d' "$season_num")E$(printf '%02d' "$episode_num") - ${clean_title}.$ext"

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
echo "$already already have their TMDb title."
echo "$skipped without a TMDb title for that number, left alone."
if $apply; then
    echo "Renamed $renamed files."
else
    echo "$renamed to rename. Dry run, nothing renamed. Re-run with --apply."
fi
