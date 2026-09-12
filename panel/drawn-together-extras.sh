#!/usr/bin/env bash
#
# Sorts Drawn Together's DVD bonus features into Season 00, matched by hand
# against TMDb's specials list.
#
# The disc numbers its extras "x01".."x30" instead of with a season and
# episode, and past x16 that numbering does not even run in the same order
# TMDb does - eight character-profile interviews sit between the karaoke
# tracks and the deleted-scenes/promo material. There is no formula for that,
# so the mapping below is transcribed straight from
# themoviedb.org/tv/4336-drawn-together/season/0. If your rip's extras are
# named or ordered differently, check the numbers before applying.
#
# x28-x30 are "season N promo" - TMDb keeps only one entry, "Network Promos"
# (special 29), for all of it, so all three land there with the season
# number kept in the title to tell the files apart.
#
#   ./drawn-together-extras.sh "/path/to/Drawn Together (2004) [tmdbid-4336]"
#   ./drawn-together-extras.sh --apply "/path/to/Drawn Together (2004) [tmdbid-4336]"
#
# Nothing is written without --apply.
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

dir="${1:?Usage: $0 [--apply] <path to the Drawn Together folder>}"

# The extras may already sit loose inside a "Season 00" folder, or still be
# beside "Season 01" at the top of the show. Pointed at the former, do not
# nest another "Season 00" inside it.
if [[ "$(basename "$dir")" =~ ^Season\ [0-9]+$ ]]; then
    season_dir="$dir"
else
    season_dir="$dir/Season 00"
fi

# file number -> "TMDb special number|title"
declare -A specials=(
    [1]="5|Black Chick's Tongue [Sing-along]"
    [2]="6|Ling-Ling Lament [Sing-along]"
    [3]="7|Bully Song [Sing-along]"
    [4]="8|Ling-Ling Battle Song [Sing-along]"
    [5]="9|La La Labia [Sing-along]"
    [6]="10|Shit Sandwich [Sing-along]"
    [7]="11|Sunshine [Sing-along]"
    [8]="12|School House Rock [Sing-along]"
    [9]="13|God Is Watching [Sing-along]"
    [10]="14|Crashy Smashy [Sing-along]"
    [11]="23|Pledging Days [Sing-along]"
    [12]="24|No Easy Way Out [Sing-along]"
    [13]="25|Scumma Bumma [Sing-along]"
    [14]="26|Drawn Together Babies [Sing-along]"
    [15]="27|Face the Balls [Sing-along]"
    [16]="28|Fire the Load [Sing-along]"
    [17]="15|Creator's Confessional"
    [18]="16|Captain Hero"
    [19]="17|Foxxy Love"
    [20]="18|Ling Ling"
    [21]="19|Princess Clara"
    [22]="20|Toot Braunstein"
    [23]="21|Wooldoor Sockbat"
    [24]="22|Xandir"
    [25]="3|Character Intros and Deleted Scenes"
    [26]="4|Previously On Drawn Together"
    [27]="2|Writer's Rant Hidden Feature"
    [28]="29|Network Promos (Season 1)"
    [29]="29|Network Promos (Season 2)"
    [30]="29|Network Promos (Season 3)"
)

moved=0
for n in $(seq 1 30); do
    padded=$(printf '%02d' "$n")
    # Anchored on both sides by dots, not a loose substring - "x26" is also
    # inside every file's own "x264tvv" release tag.
    src=$(find "$dir" -maxdepth 1 -type f -iname "*.x${padded}.*" -print -quit)
    if [[ -z "$src" ]]; then
        echo "x${padded}: no file found, skipped"
        continue
    fi

    entry="${specials[$n]}"
    special="${entry%%|*}"
    title="${entry#*|}"
    ext="${src##*.}"
    dest="$season_dir/Drawn Together S00E$(printf '%02d' "$special") - ${title}.$ext"

    echo "$(basename "$src")"
    echo "    -> $(basename "$season_dir")/$(basename "$dest")"

    if $apply; then
        mkdir -p "$season_dir"
        mv -n -- "$src" "$dest"
    fi
    moved=$((moved + 1))
done

echo
if $apply; then
    echo "Moved $moved files."
else
    echo "Dry run, nothing moved. Re-run with --apply."
fi
