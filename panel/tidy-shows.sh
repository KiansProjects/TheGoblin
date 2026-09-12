#!/usr/bin/env bash
#
# Sorts loose "S01E02"-style rip files into Season NN subfolders, named the
# way Jellyfin expects: "<Show> SxxEyy - Title.ext".
#
# The general-purpose counterpart to drawn-together-extras.sh: a real
# S01E02 marker already says exactly which episode a file is, so there is
# no hand-written mapping to keep here - only reading the marker and
# filing the result. The show's name is read from the target folder
# itself, so point this at the show's own library folder:
#
#   ./tidy-shows.sh "/path/to/Some Show (2004) [tmdbid-1234]"
#   ./tidy-shows.sh --apply "/path/to/Some Show (2004) [tmdbid-1234]"
#
# "(2004) [tmdbid-1234]" is stripped off to get the plain show name; point
# it at a folder without that suffix and the folder's own name is used as
# given. Runs recursively, so it is safe to point at a folder that mixes
# already-sorted and still-loose files - anything without a bare "S01E02"
# or "1x02" token between dots is left alone and reported, and a file
# already at its target path is left alone too.
#
# The titles the ripper's file names carry ("hottub", "gaybash") have no
# spaces or capitals to recover - this keeps them as-is rather than
# guessing at word boundaries. Jellyfin fills in the real episode title
# from TMDb once the season and episode number are right regardless of
# what the file name itself says.
#
# Nothing is written without --apply.
set -euo pipefail

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    sed -n '2,25p' "$0" | cut -c3-
    exit 0
fi

apply=false
if [[ "${1:-}" == "--apply" ]]; then
    apply=true
    shift
fi

dir="${1:?Usage: $0 [--apply] <show folder>}"

show="$(basename "$dir")"
show="${show% \[tmdbid-*\]}"
show="${show% (*)}"

video_ext_pattern='\.(mkv|mp4|avi|m4v|mov|ts|wmv|mpg|mpeg)$'

moved=0
skipped=0
in_place=0

while IFS= read -r -d '' file <&3; do
    name="$(basename "$file")"
    [[ "$name" =~ $video_ext_pattern ]] || continue
    ext="${name##*.}"
    base="${name%.*}"

    IFS='.' read -r -a tokens <<< "$base"

    marker_idx=-1
    season=""
    episode=""
    for i in "${!tokens[@]}"; do
        tok="${tokens[$i]}"
        if [[ "$tok" =~ ^[Ss]([0-9]{1,2})[Ee]([0-9]{1,3})$ ]]; then
            season="${BASH_REMATCH[1]}"
            episode="${BASH_REMATCH[2]}"
            marker_idx=$i
            break
        fi
        if [[ "$tok" =~ ^([0-9]{1,2})[Xx]([0-9]{1,3})$ ]]; then
            season="${BASH_REMATCH[1]}"
            episode="${BASH_REMATCH[2]}"
            marker_idx=$i
            break
        fi
    done

    if [[ $marker_idx -lt 0 ]]; then
        echo "no S01E02 marker, left alone: $name"
        skipped=$((skipped + 1))
        continue
    fi

    season_num=$((10#$season))
    episode_num=$((10#$episode))

    title_tokens=("${tokens[@]:$((marker_idx + 1))}")
    title="${title_tokens[*]}"

    season_dir="$dir/$(printf 'Season %02d' "$season_num")"
    dest_name="$show S$(printf '%02d' "$season_num")E$(printf '%02d' "$episode_num")"
    [[ -n "$title" ]] && dest_name="$dest_name - $title"
    dest="$season_dir/$dest_name.$ext"

    if [[ "$(realpath -m "$file")" == "$(realpath -m "$dest")" ]]; then
        in_place=$((in_place + 1))
        continue
    fi

    echo "$name"
    echo "    -> $(basename "$season_dir")/$(basename "$dest")"

    if $apply; then
        mkdir -p "$season_dir"
        if [[ -e "$dest" ]]; then
            echo "    exists, skipped"
            continue
        fi
        mv -n -- "$file" "$dest"
    fi
    moved=$((moved + 1))
done 3< <(find "$dir" -type f -print0)

echo
echo "$in_place already in the right place."
echo "$skipped without a recognisable S01E02/1x02 marker, left alone."
if $apply; then
    echo "Moved $moved files."
else
    echo "$moved to move. Dry run, nothing moved. Re-run with --apply."
fi
