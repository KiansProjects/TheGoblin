#!/usr/bin/env bash
#
# Runs both per-show fixes - the real episode title from TMDb, and the
# audio/subtitle track corrections - over every show folder in a shows
# library, one after another, in one call.
#
# Calls tmdb-titles.sh and fix-track-names.sh rather than duplicating their
# logic, so both need to sit right next to this script.
#
#   ./fix-shows.sh --tmdb-key <key> /path/to/shows
#   ./fix-shows.sh --tmdb-key <key> --apply --lang deu /path/to/shows
#
# --tmdb-key can also come from TMDB_API_KEY in the environment. Every show
# is a subfolder of the given root. One without a "[tmdbid-N]" suffix on
# its own folder name just gets its titles stripped instead of filled -
# see tmdb-titles.sh - and still gets the track fix regardless. A folder
# whose name contains "[imdbid-...]" is skipped entirely, both steps.
#
# Nothing is written without --apply.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
titles_script="$here/tmdb-titles.sh"
tracks_script="$here/fix-track-names.sh"

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    sed -n '2,18p' "$0" | cut -c3-
    exit 0
fi

if [[ ! -f "$titles_script" || ! -f "$tracks_script" ]]; then
    echo "Needs tmdb-titles.sh and fix-track-names.sh next to this script:" >&2
    echo "  $titles_script" >&2
    echo "  $tracks_script" >&2
    exit 1
fi
chmod +x "$titles_script" "$tracks_script" 2>/dev/null || true

apply=false
lang=""
tmdb_key="${TMDB_API_KEY:-}"
root=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --apply) apply=true; shift ;;
        --tmdb-key) tmdb_key="$2"; shift 2 ;;
        --lang) lang="$2"; shift 2 ;;
        -*) echo "Unknown option: $1" >&2; exit 2 ;;
        *) root="$1"; shift ;;
    esac
done

if [[ -z "$root" ]]; then
    echo "Usage: $0 --tmdb-key <key> [--apply] [--lang <code>] <shows folder>" >&2
    exit 2
fi
if [[ ! -d "$root" ]]; then
    echo "Not a directory: $root" >&2
    exit 2
fi

export TMDB_API_KEY="$tmdb_key"

apply_flag=()
$apply && apply_flag=(--apply)

lang_flag=()
[[ -n "$lang" ]] && lang_flag=(--lang "$lang")

shows=0
skipped=0
failed=0

while IFS= read -r -d '' show <&3; do
    name="$(basename "$show")"

    # An "[imdbid-...]" folder is left alone entirely - not goblin's own
    # naming, and not this script's to touch.
    if [[ "${name,,}" == *"[imdbid-"* ]]; then
        echo "Skipping $name ([imdbid-...] folder, left alone)"
        echo
        skipped=$((skipped + 1))
        continue
    fi

    shows=$((shows + 1))
    echo "================ $name ================"

    echo "--- episode titles ---"
    if ! "$titles_script" "${apply_flag[@]}" "$show"; then
        echo "  titles step failed for this show, continuing"
        failed=$((failed + 1))
    fi

    echo "--- audio/subtitle tracks ---"
    if ! "$tracks_script" "${apply_flag[@]}" "${lang_flag[@]}" "$show"; then
        echo "  track fix failed for this show, continuing"
        failed=$((failed + 1))
    fi

    echo
done 3< <(find "$root" -mindepth 1 -maxdepth 1 -type d -print0 | sort -z)

echo "$shows shows processed, $skipped skipped ([imdbid-...]).$( [[ $failed -gt 0 ]] && echo " $failed steps failed - see above." )"
if $apply; then
    :
else
    echo "Dry run throughout - re-run with --apply once this looks right."
fi
