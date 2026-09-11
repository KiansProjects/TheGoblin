# TheGoblin

Splits a YouTube video into individual episode files along its chapters and stores them the way Jellyfin's scanner expects them.

## Requirements

- Java 21+
- `yt-dlp` and `ffmpeg` on the PATH
- optional, for `cbz` only: one of `unar`, `unrar`, `7zz`, `7z` or `bsdtar`

The project has no external Java dependencies — `./build.sh` is enough, Maven is optional (`mvn package`).

## Installation

```bash
./build.sh
sudo ln -s "$PWD/goblin" /usr/local/bin/goblin
```

### On a Pelican server

`panel/` holds a Pelican egg that runs TheGoblin as a server of its own:

| File | What it is |
| --- | --- |
| `panel/egg-thegoblin.json` | import this under Admin → Eggs |
| `panel/install.sh` | the install script, embedded verbatim in the egg |
| `panel/goblin-console.sh` | the startup command, turns the console into the prompt |

The installer fetches `yt-dlp` and `jellyfin-ffmpeg` as portable binaries, adds
Deno as the JavaScript runtime yt-dlp needs for YouTube's player challenges,
and builds the jar straight from `GOBLIN_REPO`. `goblin-console.sh` then
rebuilds on every server start, so a push is deployed by restarting. A build
that fails leaves the previous jar in place and starts anyway.

Two things do not update themselves: `goblin-console.sh` and
`goblin.properties.example` are copied out of the checkout during the install,
not on every start. A change to either needs a reinstall.

Set `AUTO_COMMAND` to `queue` if you want the console to be the queue described
below rather than a one-command-at-a-time prompt.

*Not verified: none of this has been run through an actual Panel. The scripts
parse (`bash -n`) and the egg is valid JSON whose embedded install script is
byte-identical to `panel/install.sh` — that is the whole of what was checked.*

## Usage

```bash
# First have a look at what is in there
goblin chapters https://www.youtube.com/watch?v=...

# Then split it
goblin shows https://www.youtube.com/watch?v=... "Ninjago" \
    --out /srv/media/shows --season 1
```

### Options

| Option | Meaning |
|---|---|
| `-o, --out <path>` | target directory, defaults to the current directory |
| `-s, --season <n>` | season number, default 1 |
| `-e, --start-episode <n>` | number of the first episode, default 1 |
| `--year <year>` | release year, overrides TMDb |
| `--tmdb-id <id>` | pin the show ID instead of searching |
| `--no-tmdb` | no database lookup, no artwork |
| `--reencode` | cut exactly instead of rounding to keyframes |
| `--keep` | keep the whole video after cutting |
| `--dry-run` | only show what would happen |

## Result

```
Ninjago (2011) [tmdbid-12345]/
  poster.jpg
  backdrop.jpg
  Season 01/
    Ninjago S01E01 - Way of the Ninja.mp4
    Ninjago S01E02 - The Golden Weapon.mp4
```

## Show database

With a free TMDb key TheGoblin fetches the show ID, the year it first aired, a poster and a backdrop image. The key goes into `goblin.properties`:

```
tmdb.api_key = your_key
```

Failing that the environment variable `TMDB_API_KEY` is read; the file takes priority. `goblin.properties` is listed in `.gitignore` — only `goblin.properties.example` is in the repo.

The ID ends up in the folder name so that Jellyfin does not have to guess the show itself. Without a key everything else runs unchanged.

## How the chapters are found

1. If YouTube recognised the timestamps as chapters, `yt-dlp` uses them directly — that is the reliable case.
2. Otherwise the description is searched. First only lines starting with a timestamp (the usual chapter list). If that finds nothing, the search runs more loosely, e.g. `Episode 1 - Way of the Ninja - 0:00`.

Lines like `New videos at 10:00 AM every Saturday` or `Live at 8:15 PM` are filtered out — otherwise you would end up with an episode called "AM every Saturday" in your media library.

The last section always runs to the end of the video. For compilation videos with credits or ads at the end, a `goblin chapters` beforehand is worth it.

## Cut accuracy

By default the cut happens without re-encoding (`-c copy`). That takes seconds instead of minutes, but it has a quirk worth knowing: a video stream is only decodable from a keyframe onwards, so every section starts at the keyframe **before** the requested time. The section therefore comes out longer than specified — on YouTube typically by up to 2 seconds, because a keyframe sits there roughly every 2 seconds.

Measured on a test video with keyframes on a 2-second grid, cutting from 11.0 s over 5.0 s:

| Mode | Result |
|---|---|
| `-c copy` | 6.08 s |
| `--reencode` | 5.00 s |

The first section is always correct, because it starts at 0:00 and a keyframe sits there. Nothing is lost, there is just the tail of the previous section attached at the front.

If that bothers you: `--reencode`. Then the cut is frame-accurate. Only the picture is re-encoded, the audio is copied.

## Audio tracks

For music that only exists on YouTube — your own recordings, fan projects, netlabel releases, podcasts, talks:

```
audio <url> --artist "Name" --album "Album" --out output/music
```

Stored in the usual layout for music collections, the one Navidrome, Jellyfin and Plex understand:

```
Artist/
  Album/
    01 - Title.mp3
```

Without `--artist` and `--album` the channel and the playlist title are used. Tags and cover art are embedded.

| Option | Meaning |
|---|---|
| `--format` | mp3 (default), m4a, opus, flac, wav, or `best` |
| `--quality <0-10>` | 0 is the best level, applies to mp3 only |
| `--chapters` | split one long video into individual tracks along its chapters |
| `--artist`, `--album` | override what would come from the metadata |
| `--musicbrainz` | look the album up and embed its MusicBrainz IDs |
| `--mbid <id>` | pin the MusicBrainz release instead of searching |
| `--trim` | cut music-video outros back to the album length |
| `--trim-tolerance <s>` | how much overlength is allowed, default 4 |
| `--dry-run` | only show where it would write |

### MusicBrainz IDs

For movies and shows the database ID travels in the folder name, `[tmdbid-12345]`.
Music does not work that way: Jellyfin ignores file and folder names there and
identifies albums through the **tags inside the files**. TMDb is no help either,
it covers film and television only — the database Jellyfin uses for music is
MusicBrainz.

```
audio <url> --artist "Daft Punk" --album "Discovery" --musicbrainz
```

That looks the release up and writes `MusicBrainz Album Id`, `Release Group Id`
and `Artist Id` into every track afterwards, with ffmpeg, copying the streams —
nothing is re-encoded and the embedded cover art survives. The layout on disk
stays exactly as before.

No API key is needed. MusicBrainz asks for an identifying User-Agent and at most
one request per second instead; a run makes one lookup.

Two things worth knowing:

* **The search takes the first hit**, and a YouTube upload title is a fuzzy
  thing to match on. The hit is printed before the download starts, so you can
  see what it picked — and `--mbid <release-id>` pins it when the guess is wrong.
* **`--artist` and `--album` are required** for the IDs to land. Without them
  the folder name is a yt-dlp pattern that is only resolved during the download,
  so TheGoblin does not know where the files will end up. It says so and skips
  the tagging rather than guessing.

Much of what this command is for — own recordings, fan projects, netlabel
releases, podcasts — has no MusicBrainz entry at all. Then nothing is found,
nothing is written, and the download is unaffected.

### Music videos that run long

A music video is not the album track. It carries a spoken intro, a fade that
runs on, applause, a closing card — anywhere from two seconds to a minute that
does not belong on the record. In a collection that shows up as a run time
disagreeing with every other copy of the song.

`--trim` measures against MusicBrainz, which knows how long the release version
is, so the surplus is a measured quantity rather than a guess:

```
audio <playlist-url> --artist "Linkin Park" --album "Meteora" --trim
```

It implies `--musicbrainz` — the track lengths come from the same lookup. Per
file:

1. The file is matched to a release track **by title**, not by position: a
   playlist's order is only as good as whoever assembled it. `01 - Numb
   (Official Video).mp3` and `Numb` match because the comparison drops case,
   punctuation and the usual decoration. When the title says nothing, the
   number in the file name is the fallback, and that is reported as such.
2. Files no more than `--trim-tolerance` seconds over the release length are
   left alone. Four seconds by default, which covers encoders and a fade.
3. Dead air at the **start** is cut, but never more of it than the surplus
   covers — see below.
4. The rest comes off the end: at the **trailing silence** when there is one
   near the expected end, otherwise at the release length exactly. Cutting at
   the silence ends the file on the last note instead of mid-fade.

The cut copies the streams, so nothing is re-encoded and the tags and cover art
survive.

**On the silence at the start.** Rips often open with a second or three of
nothing before the music comes in. That gets removed — but only when the file
is over-length anyway, and only up to the surplus. A file that is eight seconds
too long keeps a thirty-second lead: a lead that big means the file is short at
the back or the track was matched to the wrong song, and in both cases cutting
the front would take the first beat with it. Silence shorter than half a second
is the natural gap before the first beat and stays. Only silence that starts at
the very beginning counts; a quiet passage twelve seconds in is music.

Files that are *shorter* than the release version are never touched — that is a
radio edit or a different mix, and the run says so. Same for a track MusicBrainz
has no length for.

*Written and tested against faked ffprobe/ffmpeg output; the matching and the
parsing have unit coverage, but no run against a real download has happened yet.*

**On flac and wav:** YouTube serves Opus or AAC, so already lossy. Converting to flac makes the files bigger, not better — the lost information does not come back. If you want to work without further loss, use `--format best`: then the original track stays as it is, with no re-encoding.

## Joining multi-part videos

For material a channel uploaded in several parts — long let's plays, talks, documentaries:

```
concat <playlist-url> "Title" --out output
```

The parts are downloaded in playlist order, trimmed at the transitions and joined into one file.

### What gets cut

**Closing credits at the end of each part.** Detected through a silence that runs to the end of the video, or a black frame at the end. An outro with music and picture running through cannot be found this way — `--outro <seconds>` gives a fixed deduction for that, or `--no-outro`.

**Duplicate start.** If a part begins with the end of the previous one, the duplicated stretch is removed. What gets compared are the loudness envelopes of both audio tracks, over the normalised correlation coefficient — coarse enough to survive encoding differences, fine enough for second-accurate hits.

On one test transition the real match scored 0.999, the best false match 0.80. The threshold is set to 0.90 because of that.

### Options

| Option | Meaning |
|---|---|
| `--outro <sec>` | fixed deduction at the end instead of detection |
| `--no-outro` | do not cut the credits |
| `--overlap <sec>` | search window for the overlap, default 90 |
| `--no-overlap` | do not trim the starts |
| `--min-overlap <sec>` | shortest overlap, default 1 |
| `--max-overlap <sec>` | longest plausible overlap, default 30 |
| `--min-score <0-1>` | threshold for a match, default 0.90 |
| `--keep-parts` | keep the individual parts after joining |
| `--movie` | store the result as a movie, see below |
| `--year <year>` | release year, overrides TMDb (needs `--movie`) |
| `--tmdb-id <id>` | pin the movie ID instead of searching (needs `--movie`) |
| `--no-tmdb` | no database lookup, no artwork (needs `--movie`) |
| `--dry-run` | list the parts and the target path |

The upper bound on overlaps is not a cosmetic detail: with periodic music the loudness envelope repeats, and then the same start fits in several places. Without a bound the wrong match occasionally wins.

Before joining, every part is re-encoded so that the cuts sit frame-accurately and all sections share the same parameters. On long playlists that takes accordingly long.

### A movie split into parts

Channels like Marvel HQ or LEGO regularly upload a whole film as four or five
consecutive videos. Joined back together that is a movie, and it should be
stored as one:

```
concat <playlist-url> "The Lego Ninjago Movie" --movie --out output/movies
```

With `--movie` the result does not land as a flat file but in the same layout
the `movie` command produces — one folder per film, with the year and the TMDb
ID in the name, poster and backdrop next to it:

```
output/movies/
  The Lego Ninjago Movie (2017) [tmdbid-324849]/
    The Lego Ninjago Movie (2017).mkv
    poster.jpg
    backdrop.jpg
```

That ID is the point: without it Jellyfin has to guess the film from the file
name, and a joined YouTube upload is exactly the case where it guesses wrong.
`--tmdb-id` pins the match when the search picks the wrong film, `--year`
overrides the year, `--no-tmdb` skips the lookup entirely.

If TMDb finds nothing, or no key is configured, the folder is simply built
without the brackets — `The Lego Ninjago Movie/The Lego Ninjago Movie.mkv`.
Nothing breaks.

**Without `--movie` nothing changes.** The join stays a flat file, because
`concat` also serves let's plays and talks — running a movie search on those
would occasionally stamp a wrong TMDb ID onto the folder, and a wrong ID is
worse than none.

### Limits

The log output shows for every part what was detected. If nothing is listed for a transition although you expect an overlap, a larger `--overlap` or a lower `--min-score` helps — the latter carefully though, below 0.85 false matches pile up.

## Movies

Store a video as a whole movie, without cutting:

```
movie <url> "Movie title" --out output/movies
```

Result:

```
Movies/
  Movie title (2017) [tmdbid-12345]/
    Movie title (2017).mp4
    poster.jpg
    backdrop.jpg
```

TMDb is queried through the movie search here, not the show search. `--tmdb-id` pins the match, `--year` overrides the year.

Options as with `shows`: `--best`, `-f`, `--container`, `--dry-run`, `--no-tmdb`, `--verbose`. There is no `--snap` and no `--reencode` here, because nothing is cut — the download lands as a file unchanged.

If the console wrapper only lets `shows` through:

```
shows <url> "Movie title" --movie --out output/movies
```

## Playlists

Three shapes of link are accepted and two of them are rewritten first, with a
line saying so:

| You paste | It reads |
| --- | --- |
| `/show/VLPLxxxx` | `/playlist?list=PLxxxx` |
| `/watch?v=…&list=PLxxxx` | `/playlist?list=PLxxxx` |
| `/watch_videos?video_ids=a,b,c` | left alone — see below |

The first is what the share sheet of a series hands out; the `VL` is a wrapper
around the playlist id. The second is what copying a video *out of* a playlist
gives you, where the list is what was meant and the single video is an accident
of which one happened to be open. yt-dlp resolves neither into the playlist, so
they are rewritten here rather than in each caller.

`watch_videos` is deliberately untouched: those carry their video ids in the
link itself, and reading them straight out of it keeps the order guaranteed.
That also makes it the way to treat a handful of loose videos as one playlist —
paste the ids in the order you want them.


For a playlist with one video per season:

```
playlist <playlist-url> "Ninjago"
```

That reads the playlist flat and prints a ready-made `shows` line for every video, with the season number counting up and the title as a comment above it. Nothing is downloaded in the process.

Options: `--out <path>`, `--season <n>` for the first season number, `--extra "<options>"` for what gets appended to every line (default `--snap --reencode`).

Look the lines over before running them — whether the order of the playlist really matches the season order is something only the channel knows.

Lets the console wrapper only through `chapters`, this works too:

```
chapters <playlist-url> --playlist "Ninjago"
```

### One video per episode

Playlists where every video is a single episode:

```
playlist <url> "Name" --episodes --season 1 --out output/shows
```

Every video is downloaded once and stored as one episode — no cutting, no chapters. The numbering follows the order of the playlist.

Files are named just `Name S01E01.mp4` by default. Jellyfin fetches the episode title from TMDb via the number, and YouTube titles are mostly unusable for that. With `--titles` the video title is appended anyway.

Two things that matter on long playlists:

**Existing files are skipped.** If the run breaks off at episode 30, you call the same command again and it carries on from there. With `--overwrite` everything is downloaded again instead.

**One broken video does not stop the rest.** Failures are counted and reported at the end, the remaining episodes run through.

If a playlist contains several seasons, you split it up with `--from` and `--to` (1-based, both inclusive):

```
playlist <url> "Name" --episodes --season 1 --from 1 --to 26
playlist <url> "Name" --episodes --season 2 --from 27 --to 46
```

Where the boundaries lie is shown by the listing without `--episodes` — the title of every video is there with its position.

Anonymous playlists of the form `watch_videos?video_ids=...` are read straight from the link, without the detour through YouTube's playlist resolution.

### Season and episode from the title

If the playlist is out of order or mixes seasons, `--from-title` reads the numbers from the video title instead of from the position:

```
playlist <url> "Name" --episodes --from-title --out output/shows
```

Every episode then lands in the right season folder, wherever it sits in the list. Among others `S01E02`, `s1e2`, `Season 2 Episode 5`, `Staffel 4 Folge 3` and `3x12` are recognised, as is the order the official channels use, `FULL EPISODE 5 | Season 2`, where the episode comes first. If the title names only an episode number, the season comes from `--season` — which is why the reversed order matters: without it a nine-season playlist reads as nine times season 1 and files 448 videos into 64 names.

Videos without a recognisable number are **skipped and listed at the end** — not guessed at. A sequentially numbered fallback would otherwise overwrite a correctly detected episode. Mostly those are trailers or compilations that do not belong in the season anyway.

Always check with `--dry-run` first: the output shows the planned path including the season folder for every video.

### Resuming a run

A run that stops halfway is picked up by the next one: an episode whose file is already there is skipped rather than fetched again. With `--upload` the file is deleted locally the moment it is on the server, so that check alone would find nothing and a playlist of hundreds would start over. The remote directory is therefore listed as well — once per season folder, not once per episode — and an episode already on the server is skipped too.

A listing that fails, or a season folder that does not exist yet, counts as empty. The worst that costs is one episode fetched twice.

### When the channel counts differently than TMDb

`--from-title` believes the numbers in the title. Official channels often do not use the same ones as TMDb. The playlist *All Pokemon episodes (in order)* splits the first 118 dubbed episodes into a season of 52 and a season of 60, where TMDb has 82 and 36 — so from around its `Season 1 | EPISODE 46` onwards every number points at a different episode than the one in the video, and Jellyfin writes the wrong title under each.

The episode titles, on the other hand, are the real ones. `--match-titles` throws the numbers away and looks the title up in the season list on TMDb:

```
playlist <url> "Name" --episodes --match-titles --titles --out output/shows
```

Both sides are folded down to lowercase letters and digits first, so an accent, a curly apostrophe or an em dash where TMDb has a plain hyphen makes no difference; beyond that one character in ten may differ. Season and episode number then come from TMDb, and with `--titles` so does the spelling of the title.

Four differences get their own handling rather than being paid for out of that budget, because a short title cannot afford them and a long one can — which is length deciding the match, not meaning:

* **Part numbers.** TMDb writes the halves of a two-parter as `Gaining Groudon (1)` and `The Scuffle of Legends (2)`, uploaders usually do not. Folded that is a trailing ` 1`, two characters.
* **A leading article.** `The Tent Situation` against TMDb's `A Tent Situation`, `Chikorita Rescue` against `The Chikorita Rescue`. Three characters for a word that says nothing about which episode is meant.
* **One word of two spellings.** `Sick Days` against `Sick Daze`, `Play with Fire` against `Playing with Fire` — every other word identical, and the odd one out either within two characters or the start of the longer. Everything else matching exactly is what makes this safe: `Round One - Begin!` against `Round Two - Begin!` is three characters apart with no shared start, and stays out.
* **`vs.` against `Versus`**, which TMDb spells out.

Each is a tier of its own, in that order, below an exact match and above anything merely similar. The tie rule applies to all of them: two episodes that differ only by their article are still two episodes, and a video naming neither is reported rather than filed by coin flip.

Two things are deliberately not guessed at and are listed at the end of the dry run instead:

* a video whose title matches no episode well enough, **or matches two equally well** — two episodes of a long-running show can share a title, and there is no way to tell from the title which one is meant,
* a video that would land on an episode another video already took.

`--verbose` prints the three episodes each skipped video came closest to, with their scores. Two entries with the same score are a tie — TMDb carries that title twice and nothing in the title says which one is meant. `nothing on TMDb resembles this title` is the other answer, and it means TMDb holds that episode under a different name altogether, not a different spelling.

For those, `--verbose` also lists the episodes on TMDb that no video claimed, in the seasons the run touched. A video that matched nothing and an episode nobody claimed are usually the same episode under two names, and the two lists put them side by side.

`--match-titles` needs the episode list, so it cannot be combined with `--no-tmdb`, and it overrides `--from-title`. It costs about one request per season before the first download.

## Finding boundaries automatically

Timestamps in descriptions are typed by hand and are often a second or two off. Instead of measuring every one of them:

```
shows <url> "Name" --snap --reencode
```

TheGoblin then searches in a window around every timestamp for the actual picture change. Two signals, in this order:

1. **Black frame** — almost always present at episode transitions. The cut lands at the end of the black stretch, meaning at the first frame of the new episode.
2. **Hard scene change** — if there is no black frame.

If it finds nothing, the original timestamp stays. The log shows per boundary what was found:

```
Searching for boundaries (window 5 s) ...
  11:00 -> 11:02  (+2.31 s, black frame)
  22:00 -> 22:01  (+1.04 s, scene change)
```

The window width can be given: `--snap 10` searches ±10 seconds. Larger means more tolerance towards bad timestamps, but also more risk of catching a scene change *inside* the episode.

The first section always stays at 0:00.

**Use it together with `--reencode`.** Without it the cut still snaps to the keyframe before, and the exact boundary would be given away again.

## When the video has no chapters at all

`--snap` refines boundaries, it does not find them — it searches a window
*around a timestamp that already exists*. A feature-length upload of several
episodes has none, so there is nothing to refine.

What is known in that case is how long each episode of the season runs:

```
shows <url> "Star Wars Rebels" --runtimes -s 1 -e 1 --reencode
```

TheGoblin asks TMDb for the season, lays the run times end to end, and takes as
many episodes as fit into the file. How many that is comes out of the
arithmetic rather than out of a flag — one episode too many and every boundary
after it sits past the end of the video.

```
TMDb run times: 4 episodes (1 to 4), 88:00 together, the file runs 90:00.
  2:00 unaccounted for - title cards, transitions, and TMDb rounding to whole minutes.
```

**Those numbers are estimates and stay estimates.** TMDb rounds to whole
minutes and describes the broadcast version, not somebody's compilation of it.
So `--runtimes` turns `--snap` on by itself, with a 90 second window instead of
the usual few seconds; `--snap <seconds>` overrides that.

### Why the errors do not add up

Laid end to end, a rounding error of half a minute per episode would put the
eighth boundary four minutes off — far outside any window worth searching. So
with `--runtimes` each boundary is searched for **from the previous one that
was actually found**, not from the theoretical sum:

```
Searching for boundaries, each from the last one found (window 90 s) ...
  22:00 -> 22:41  (+41.00 s, black frame)
  44:41 -> 45:23  (+42.00 s, black frame)
```

The second line starts from 22:41, not from 44:00. Each boundary therefore
carries one episode's worth of error rather than the sum of all of them.

An episode TMDb has no run time for stops the run there and says so, rather
than shifting everything after it by an unknown amount.

*Verified against fixture seasons: the parsing, the minute-to-second
conversion, the ordering, how many episodes fit, starting at an episode other
than the first, the stop at a missing run time, and the arithmetic showing the
rolling correction beats the naive sum. **The TMDb call itself has never been
run** — the API is unreachable from where this was written, so what is tested
is the parsing and the maths around the request, not the request.*

## Custom timestamps

If the chapters in the video are wrong, you can pass your own list — same format as a YouTube description, one line per section:

```
0:00 Way of the Ninja
11:00 The Golden Weapon
22:00 King of Shadows
33:00 Weapons of Destiny
```

```
shows <url> "Name" --chapters chapters.txt
```

If all timestamps are off by the same amount, `--offset <seconds>` is enough. It shifts every boundary from the second one on; the first stays at 0 so that the beginning does not get cut off.

## Download format

H.264 with AAC in mp4 is preferred. Otherwise YouTube serves VP9 or AV1 in webm, which many clients cannot play directly — then the server transcodes, and with AV1, for lack of a hardware decoder, entirely on the CPU.

## Ideas for later

- `goblin playlist <url>` for whole playlists, one episode per video instead of per chapter
- detection of whether an episode already exists, instead of overwriting blindly
- `--map` with a file mapping chapter number to episode number, for cases where TMDb counts differently than the video
- if you want it as a real command: Quarkus with `quarkus-picocli` and a native image gives you a binary without JVM startup time

## Quality and codecs

By default H.264 with AAC in mp4 is downloaded. Practically every client plays that directly — but YouTube serves H.264 only up to 1080p at most, and on older uploads often only 720p. Higher resolutions exist there only as VP9 or AV1.

Have a look at what the video actually offers first:

```
chapters <url> --formats
```

If nothing above 720p is listed, the video simply does not exist in better quality.

If higher resolutions do exist in other codecs:

```
shows <url> "Name" --best
```

`--best` takes the best available combination regardless of codec and stores the result as mkv, because VP9 and Opus sit more reliably in that than in mp4.

The price: not every client plays VP9 and AV1 directly, and then the server transcodes. Without a hardware decoder for AV1 that lands entirely on the CPU.

If you want it more precise, set the selector yourself:

```
shows <url> "Name" -f "bv*[height<=1080]+ba" --container mkv
```

The syntax is yt-dlp's.

## Tidying a folder that arrived some other way

The other commands leave a tidy library because they know what they downloaded.
`tidy` is for everything else — a zip somebody unpacked into the music folder,
thirty-four comics dropped in a heap, a season of episodes with release-group
names.

### The inbox

The way it is meant to be used: one folder you throw things into, one command
that puts them away.

```
tidy input
```

The first run creates `input/` with a subfolder per kind and stops. Drop your
unsorted files into whichever fits, run it again, and each subfolder is sorted
into its own place in the library:

| Inbox | Lands in |
| --- | --- |
| `input/comics` | `books/Comics/` |
| `input/books` | `books/Books/` |
| `input/music` | `music/` |
| `input/shows` | `shows/` |
| `input/movies` | `movies/` |

With `--upload` the sorted files go straight on to the media server over SFTP
and the local copy is removed, exactly as the download commands do it — the
remote path mirrors the local one below `sftp.base`:

```
tidy input --apply --upload
```

The drop folders themselves are never deleted, so the inbox is there for the
next heap.

The destinations are overridable in `goblin.properties` when your library
spells them differently:

```
tidy.comics = books/Comics
tidy.books  = books/Books
tidy.music  = music
tidy.shows  = shows
tidy.movies = movies
```

### One folder at a time

```
tidy <folder> --type comics|music|shows|movies
```

Same thing for a single kind. The target is that kind's destination unless
`--out` says otherwise — `-o .` sorts in place. Every run prints the target
before it does anything, so check that line.

| Option | Meaning |
| --- | --- |
| `-t, --type <kind>` | comics, books, music, shows or movies |
| `-o, --out <path>` | target, defaults to that kind's destination |
| `--apply` | actually move; without it nothing happens |
| `--remote` | the files are on the SFTP target, comics and books only |
| `--convert` | repack `.cbr` as `.cbz` first, comics only |
| `--titles` | issue titles from ComicVine in the file name, comics only |
| `--flat` | no folder per book, books only |
| `--by <field>` | group books by `author`, `publisher` or `none` |
| `--no-database` | no lookup at all |
| `--upload` | copy the result on to the SFTP target |
| `--keep-local` | with `--upload`, keep the local copy |
| `--log <file>` | where the undo log goes |

**Nothing moves without `--apply`.** The plain run prints what it would do and
stops. Read it before you let it loose.

`--convert` repacks any `.cbr` as `.cbz` first, which is what lets the next
step read its `ComicInfo.xml` instead of guessing from the file name. See
[Repacking .cbr as .cbz](#repacking-cbr-as-cbz).

### Where it gets its answers

Three sources, in this order — and the database is last, not first:

1. **What the file says about itself.** A `.cbz` is a zip and usually holds a
   `ComicInfo.xml` naming series, issue and year. An audio file carries tags.
   Both were written by whoever produced the file and survive every rename on
   the way to you. No API key, no network.
2. **What the file name says.** Weaker, but it is all a video file offers.
   `Ninjago.S01E02.Home.1080p.WEB-DL.x264.mkv` gives up series, season, episode
   and title; the release notes are cut off at the first `1080p`, `x264` or
   `BluRay`.
3. **What TMDb says**, for film and television only — to add the ID and the
   year, never to identify something from nothing. One lookup per distinct
   series name, not per file. Without a key it sorts anyway, just without IDs.
   `--no-database` skips it entirely.

### What comes out

| `--type` | Result |
| --- | --- |
| `comics` | `Saga (2012)/Saga #012 (2013).cbz` |
| `music` | `Linkin Park/Meteora/07 - Faint.mp3` |
| `shows` | `Ninjago (2011) [tmdbid-38693]/Season 01/Ninjago S01E01 - Way of the Ninja.mkv` |
| `movies` | `Inception (2010) [tmdbid-27205]/Inception (2010).mkv` |

The comic folder carries the **earliest** year seen for that series, the file
carries the issue's own — otherwise one run would scatter across `Saga (2012)`
and `Saga (2013)`.

Subtitles follow their episode and are renamed with it, including the language
marker: `Show.S01E01.Title.en.srt` beside a video with release tags in its name
becomes `Show S01E01 - Title.en.srt`.

### What it refuses to do

Guessing is where a tidier does damage, so it declines rather than inventing:

* **A file it cannot identify stays exactly where it is** and is listed at the
  end. An unsorted file is a small annoyance; a file filed under the wrong
  series is one you have to hunt for.
* **A name made only of digits is not a series.** A scan called
  `1234567890.cbz` is a barcode, and it is left alone.
* **Folder names only count below the folder being tidied.** Sorting `music/`
  will not decide that `music` is the artist and `dump` the album.
* **Nothing is overwritten.** Two files wanting the same name means the second
  is skipped and reported.

### Getting back

Every move is appended to `goblin-tidy.log` in the target folder as
`from<TAB>to`, oldest first. That is the way back:

```
tac goblin-tidy.log | while IFS=$'\t' read -r from to; do mkdir -p "$(dirname "$from")"; mv "$to" "$from"; done
```

`--log <file>` puts it somewhere else. An inbox run appends all four kinds to
the same log, so one reversal undoes the whole run.

With `--upload` the log still records where each file went locally, but the
local copy is gone by then — the way back there is the media server. Add
`--keep-local` if you want both.

### Pointing it at a library on the same machine

The files usually live in another container. Both are directories on the host,
so run it there against the volume rather than trying to reach across:

```
java -jar goblin.jar tidy /var/lib/pelican/volumes/<uuid>/media/books/Comics --type comics
```

*Exercised against fixture trees for all four types, including the moving, the
log and the subtitles. Never yet run against a real library.*

### Sorting them where they lie, over SFTP

The other way round: leave the files on the media server and reach across.

```
tidy media/books/Comics --type comics --remote
tidy media/books/Comics --type comics --remote --apply
```

The path is relative to `sftp.base`, and `--out` moves the sorted folders
somewhere else below the same base. Comics only — music has to have its tags
read, which means whole files, and video is identified from its name anyway.

Nothing is transferred. SFTP has a rename the server carries out itself, so a
shelf is sorted by moving names around, not gigabytes. The one thing that does
have to be read is the `ComicInfo.xml` inside each archive, and that is read
without fetching the archive:

A zip is built to be read from the end. The record in its last bytes says where
the table of contents is, the table of contents says where one entry sits, and
that entry is a couple of kilobytes. Three small byte ranges instead of thirty
megabytes. On the fixtures that is **1.9 % of the bytes**, and on real comics it
is a good deal less, because the tail that has to be read is a fixed 64 KiB
whatever the file weighs.

A server that will not serve byte ranges is not a failure — that file falls back
to being identified by its name, the same as a `.cbr` or a `.pdf`.

Renames go into `goblin-tidy-remote.log` as `from<tab>to`, oldest first.

*Verified: the zip reading against six fixtures, including the entry sitting
last in the archive, sitting in a subfolder, and a zip comment behind the
directory; the listing parser against the shapes curl returns, including names
with spaces and brackets; the path quoting. **Not verified: any of it against a
real SFTP server.** There is no `sshd` in the environment this was written in,
so the wire itself has never carried one of these commands.*

## One name for every track

Jellyfin does not store the line it shows in the track picker. It builds it
again on every page, out of the stream itself:

```
[title] - language - profile|codec - channel layout - default - external
```

An attribute is dropped when the title already contains it, compared as a
substring. "Profile or codec" is meant literally - a profile that is not `lc`
replaces the codec name, so AAC-LC reads as `AAC` and HE-AAC reads as `HE-AAC`
and nothing else. `Default` is not metadata at all, it is the disposition flag,
translated into the server's language.

Which leaves little to go wrong, and always the same three things when it does:

```
goblin tracks input
goblin tracks /srv/media/shows --apply --lang eng
```

* **A title nobody wrote.** Jellyfin looks for the title in the `title` tag,
  then `name`, then falls back to the container's handler name unless that is
  the default `SoundHandler`. A handler name is a technical field, which is why
  files off YouTube introduce themselves as
  `ISO Media file produced by Google Inc.` in the track list. Removing it makes
  Jellyfin print the derived attributes instead - the same ones it prints for
  every other file.
* **A title that only repeats the rest.** `Stereo` and `Surround 5.1` say
  nothing the channel layout does not. A title is dropped only when *every*
  word in it is already among the derived attributes, so `Commentary`,
  `Audio Description` and the name of a cut stay.
* **A missing language.** Without it the first field disappears, and a library
  where some tracks read `English - AAC - Stereo` and others read `AAC -
  Stereo` is exactly the unevenness this is for. `--lang` fills in the ones
  that state nothing; without it they are only listed.

Two audio tracks marked as default, or none at all, is corrected to the first
one. Which of two sensible candidates should be the default is not - that is a
decision from whoever built the file.

Nothing is written without `--apply`, and a file that needs none of this is
never opened for writing. The correction is a remux with `-c copy`, so nothing
is re-encoded and the picture is untouched, but a second copy is written beside
the original and moved over: a 16 GiB film needs 16 GiB free while it is
rewritten. MP4 files come back with `+faststart`.

The modification time is carried over onto the corrected file, so a library
that sorts by "date added" does not put a whole shelf back at the top. The
creation time on the file system cannot be kept - it belongs to the file that
was just written.

Jellyfin reads all of this when it next scans the library. "Automatically
refresh metadata from the internet: Never" does not stop that - the setting is
about the databases, not about the file.

### Downloads do it on their own

Every finished download goes through the same correction before it is uploaded
- `shows`, `movie`, `concat` and `playlist --episodes` alike - so the library
stays even without anyone remembering a flag. A file that needs nothing is not
rewritten, but a file off YouTube always does: it carries Google's handler name
as its track title.

The one thing that cannot be read off a video is its language, so it is
configured rather than guessed:

```
track.language = eng
```

in `goblin.properties`, next to the limits and for the same reason. Left empty,
the language is not touched and only the title is corrected. `goblin tracks`
falls back to the same setting when it is run without `--lang`.

This costs one extra pass over each finished file - `-c copy`, nothing
re-encoded, but the file is written once more. Next to downloading it, that is
not much; on the cut path it is one more copy per episode.

## Comic metadata and covers

Comics have no TMDb — that database is film and television. The one the scene's
`ComicInfo.xml` files come from is **ComicVine**, and `tidy` asks it when a key
is present:

```
comicvine.api_key = your_key
```

Failing that the environment variable `COMICVINE_API_KEY` is read; the file
takes priority. Without a key nothing changes — comics are sorted from what the
files say about themselves, exactly as before.

What the lookup is worth:

* **The folder year stops being a guess.** Without it the year comes from the
  earliest issue you happen to own, so a run starting at #4 lands in the wrong
  year. ComicVine knows when the series started.
* **The spelling gets fixed.** Whatever the files call it, the folder gets the
  name the database uses, so `Cap America` and `Captain America` stop being two
  series.
* **A cover.** `cover.jpg` goes into the series folder.
* **`series.json`** goes next to it — publisher, start year, description,
  ComicVine id, in the ComicRack format that readers pick up.

Both are written **beside** the issues, never into them. Putting metadata inside
each archive would mean rewriting every file, and a rewrite that goes wrong
costs a comic; this costs one small file per series.

`--titles` additionally puts the issue title from the database into the file
name, `Winter Soldier #001 (2012) - The Longest Winter.cbz`. Off by default,
because it renames every file you own.

Two things about this API that cost a run if you miss them, both handled here:
it refuses a request without a User-Agent of its own, and the rate limit is 200
requests an hour. A series is therefore looked up **once**, not once per issue,
and `--titles` costs one further request per series.

*Verified: the parsing against saved responses covering the awkward shapes — a
missing `original_url`, an empty `image`, a null `cover_date`, a `1.MU` issue
number, HTML in the description; the series matching, which picks the right one
of three series called "Captain America" by year; that the generated
`series.json` parses. **Not verified: a single live call.** ComicVine is blocked
from the environment this was written in, so neither the URLs nor the key
handling have ever been exercised against the real API.*

## Books

```
tidy media/books/Books --type books
tidy media/books/Books --type books --remote --apply
```

A book is not a comic, and until this existed it was treated as one: `.pdf`
and `.epub` counted as comic extensions, so a textbook went through the
issue-number guesser and was filed under a series named after its entire
download-site file name. They are their own kind now.

The same three sources, in the same order:

1. **What the file says about itself.** An EPUB keeps its metadata in an `.opf`
   inside the archive — title, author, publisher, date, ISBN. That is a zip
   entry like a comic's `ComicInfo.xml`, so it is read the same way, including
   over SFTP without fetching the book.
2. **What the file name says.** The download sites write
   `Title -- Authors -- Year -- Publisher -- ISBN -- hash -- Anna's Archive`.
   The fields after the authors are not read by position, because their order
   varies — each is examined for what it is, so an ISBN is found wherever it
   sits.
3. **What the database says.** Open Library, chosen over the alternatives
   because it needs **no API key**. An ISBN makes it a lookup rather than a
   search, and a file name from those sites nearly always carries one.

That last point is what the database is really for here. Those sites strip the
punctuation out of a title: a colon and a full stop both become an underscore,
which is why your file says `IT-Berufe_ Schülerband_ Grundstufe 1_ Jahr` and
there is no way to reverse it — two different characters, one replacement. The
ISBN survives intact, and the database has the title as it is printed.

```
Jürgen Gratzke/
  IT-Berufe: Schülerband (2020)/
    cover.jpg
    IT-Berufe: Schülerband.pdf
```

### Which field the shelf is grouped by

The top level is a real choice, not a default worth defending. A novel is
looked for under its author; a textbook is bought and looked for under its
publisher and series. So it is one flag:

```
tidy media/books/Books --type books --by publisher
```

```
Author/Title (Year)/…        --by author       (the default)
Westermann/Title (Year)/…    --by publisher
Title (Year)/…               --by none
```

Set it once in `goblin.properties` instead, because it is a property of the
shelf rather than of the run:

```
books.group = publisher
```

A book missing the chosen field keeps its title folder and loses the level
above it, rather than being filed under an invented "Unknown" — one folder of
strays is easier to deal with than a folder that lies.

`--flat` drops the per-book folder on top of that, giving `Westermann/Title
(Year).pdf`.

Two files of the same book — the `.epub` and the `.pdf` — share an ISBN, so
they resolve to one entry and land beside each other rather than in two folders
that differ by a comma.

### Covers

A cover comes down for every book whose ISBN is known and lands beside it as
`cover.jpg`, or as `Title (Year).jpg` with `--flat`. That is independent of the
grouping — it follows the book, wherever the book goes.

The ISBN is the whole mechanism, so the covers arrive for exactly the books
that have one: from the file name on anything from a download site, from the
`.opf` on an EPUB, and not at all on a bare `Der Schwarm.epub`. Open Library
answers a book it has no cover for with a one-pixel placeholder rather than a
404, so anything implausibly small is discarded — a shelf of identical grey
squares is worse than no covers.

*Verified: the file-name reading against the real shapes, including a field
order where publisher and year share one field and an ISBN written with
hyphens; the `.opf` parsing against a namespace prefix that is not `dc`, CDATA,
`urn:isbn`, XML entities, a duplicated author and a contributor that must not
become one; reading a real EPUB both from disk and **through the same byte-range
reader SFTP uses**; the Open Library parsing against saved responses. **Not
verified: a single live call.** openlibrary.org is blocked from the environment
this was written in.*

## A queue instead of six terminals

```
queue                          work through the list, one job at a time
queue add <command...>         put a job in it
queue list                     the list and what became of each job
queue remove <n>               drop one
queue clear [--done|--all]     drop what is waiting, what succeeded, or everything
```

Downloads are long and the services behind them are not infinitely patient.
Starting six at once is how you find a rate limit. `queue` turns a list of work
into one job at a time, with `--delay <seconds>` between them when you want to
go easy on something.

**The queue is a file, and that is the point.** `goblin-queue.tsv` in the
working directory, one job per line with its state. A container that restarts
mid-download comes back to the same list — and the job it was on is marked
pending again rather than silently lost, because a job left as `running` can
only mean the last run was killed.

A job that fails is one job that failed. It is marked `failed`, the reason is
printed, and the queue carries on; `clear --done` therefore keeps the failures
so you can look at them and put them back.

### The console is the input

The worker reads standard input while it works, on its own thread. **On a
Pelican server the console is exactly that**, so this is the natural startup
command for the egg:

```
java -jar goblin.jar queue
```

Paste a command into the server console and it joins the queue; paste five and
they run one after another while you close the tab. Alongside commands it takes
`list`, `pause`, `resume` and `quit` — `pause` lets the running job finish and
starts nothing new, `quit` stops after it.

Child processes are started without a standard input of their own, so ffmpeg
cannot eat what you type. (It reads stdin for keystroke commands otherwise, and
inherits it from us.)

Jobs run inside the same process, so there is no second JVM per job. `queue`
cannot queue itself.

### Adding from somewhere else

`queue add` appends a line, so a second shell can add work while the worker
runs. Both sides read the file before they write it and write it through a
temporary file, which is enough for one person adding jobs by hand. It is not a
lock: two processes writing in the same millisecond can still lose an entry.

*Verified: the command-line splitting and its round trip through the file (20
cases), the worker running four real jobs including one that failed without
taking the queue with it, the recovery of an interrupted job, `clear` keeping
failures, and the console path end to end — typed in, queued, run, `list`,
`quit`. Never yet run for days on the real server.*

## Repacking .cbr as .cbz

```
cbz <folder>          list what would be converted
cbz <folder> --apply  convert, then remove the .cbr
cbz <folder> --keep   convert and keep the .cbr as well
```

Both formats hold the same thing — one issue's pages in an archive. The
difference is that a `.cbz` is a zip, and `tidy` can read the `ComicInfo.xml`
out of a zip with nothing but Java's standard library. RAR needs a decoder Java
does not have, so a `.cbr` falls through to the weakest identification source
there is: its file name. The same issue as a `.cbz` is sorted from what the
publisher wrote inside it.

So this is worth running before `tidy comics`, not after — or as part of it:

```
tidy input --convert --apply
tidy <folder> --type comics --convert --apply
```

which repacks first and then sorts the results from what they say about
themselves. A dry run says how many files it concerns but converts nothing, so
the plan it prints is the one for the files as they stand.

Unpacking is left to an external program, the way yt-dlp and ffmpeg do the rest
of the heavy lifting. The first of `unar`, `unrar`, `7zz`, `7z`, `bsdtar` that
is installed is used; the run names which one. Without any of them only one
case still works, and it is a common one: **plenty of `.cbr` files are zips
that somebody renamed.** Those are recognised by their first four bytes and
need no unpacking at all.

What it will not do:

* **Overwrite an existing `.cbz`.** The `.cbr` is left alone and reported.
* **Delete anything it has not read back.** The new archive is written under a
  `.part` name, reopened, and its entry count compared against the pages that
  went in. Only then does it take its final name and the original go.
* **Carry the clutter along.** `__MACOSX`, `Thumbs.db`, `desktop.ini`,
  `.DS_Store` and AppleDouble `._` files are dropped; a single folder that
  every page shares is stripped, so the pages sit at the root where readers
  expect them.

Pages are stored, not deflated — they are JPEGs already — and ordered the way a
page number orders, so page 10 does not land between 1 and 2.

*Verified against fixture archives: the ordering, the junk filtering, the
folder stripping, the round trip through `ComicInfo.xml`, the refusal to
overwrite, and the renamed-zip case end to end. **The RAR path itself has never
been run** — no extractor was installed where this was written, so what is
tested is everything around the external command, not the command.*

## Uploading straight to another server

Instead of collecting files in the server directory, TheGoblin can push every finished file away over SFTP and delete the local copy. Useful when the media library sits on a different machine than the goblin runs on.

Put `goblin.properties` in the working directory (template: `goblin.properties.example`):

```
sftp.host = 192.168.1.50
sftp.port = 22
sftp.user = perry
sftp.key  = /home/container/.ssh/id_ed25519
sftp.base = /srv/media
```

Then append `--upload` to `shows`, `movie`, `playlist --episodes`, `concat` or
`audio`.

**The remote path mirrors the local one.** What gets appended to `sftp.base` is
the path of the file relative to the working directory — `--out` included. One
`sftp.base` therefore serves every media type at once:

| Command | `--out` | ends up in |
| --- | --- | --- |
| `shows`, `playlist --episodes` | `shows` | `/srv/media/shows/Series (Year) [tmdbid-N]/Season 01/` |
| `movie`, `concat --movie` | `movies` | `/srv/media/movies/Title (Year) [tmdbid-N]/` |
| `audio` | `music` | `/srv/media/music/Artist/Album/` |

Missing folders are created on the target. Artwork and, for `audio`, every
track of the album travel along.

Two details worth knowing:

* **`audio --upload` needs `--artist` and `--album`.** Without them the folder
  name is a yt-dlp pattern that is only resolved during the download, so the
  files cannot be located afterwards. It says so and skips the upload.
* An `--out` pointing outside the working directory (an absolute path
  elsewhere) has no meaningful position below `sftp.base`. There the remote
  path falls back to being relative to `--out` itself.
* **Do not repeat `sftp.base` in `--out`.** With `sftp.base = /media`, the
  right value is `--out shows`, not `--out media/shows` — the latter writes to
  `/media/media/shows`. The two get concatenated, they do not overlap. The run
  says so on the first upload when it spots the doubled folder, and the
  `Uploading to … -> /media` line at the start always shows the base in use.

Implemented through `curl`, which is present in the yolk anyway and can do SFTP. An `sftp` binary would have to be installed first.

Put the public key next to the private one (`id_ed25519.pub`), then TheGoblin
finds it itself.

### Password instead of a key

Where the target does not accept keys, `sftp.password` authenticates with a
password instead. The key wins if both are set.

It is never passed on the command line — curl reads it from stdin through
`--config -`, so it does not show up in `/proc/<pid>/cmdline`, which any process
in the same container can read. What remains is the file: the password sits in
`goblin.properties` in plain text, and against a Panel's built-in SFTP it is the
panel *account* password, not a credential limited to one directory. A key
avoids both, and the run prints a warning when a password is used.

One trap: `goblin.properties` is a Java properties file, so a backslash is an
escape character there. A password containing `\` must be written `\\`, or the
backslash is silently swallowed before TheGoblin ever sees it.

### The host key

curl verifies the target's SSH host key against `known_hosts`, and a fresh
container has none. The upload then fails with **code 60**, "SSH remote key was
not OK", before anything is transferred. The curl command line has no option to
point at a `known_hosts` file, so the host key is pinned by hash instead:

```
sftp.hostkey_sha256 = SHA256:dSbT7xQ2mK9pLvN4wR8zY1aC3eF5gH7jK9mP0qS2uW4
```

Get it from a machine that can reach the target:

```bash
ssh-keyscan -p 22 the-host 2>/dev/null | ssh-keygen -lf -
```

The `SHA256:` prefix may stay, it is stripped. Against a Panel's built-in SFTP
there is no ambiguity about which key to pin: Wings loads exactly one host key
and offers only that one.

`sftp.insecure = true` skips the check instead. It trusts whatever answers on
that host and port, so it is only reasonable on a network you control — the run
prints a warning when it is on, and the hash wins if both are set.

**Failed uploads delete nothing.** If the target is unreachable, the file stays local and the run carries on — you can push it up by hand later. With `--keep-local` the copy stays in place as a rule.

The server's disk limit therefore only bounds what is currently being worked on, not the total amount.

## Brakes

A whole season is tens of gigabytes moving through one disk in a tight loop.
Each episode is written twice — the separate video and audio streams, then the
muxed file — read again for the upload, and written once more at the target. On
a single machine that also carries a panel, its database and other servers,
that is enough to make everything on it unresponsive.

Three limits in `goblin.properties` deal with that. They are configuration
rather than command line options on purpose: a flag you have to remember is a
flag you forget on the run that hurts.

```
limit.rate            = 5M
limit.pause           = 5
limit.upload_failures = 3
```

| | |
| --- | --- |
| `limit.rate` | cap for downloads and uploads, bytes per second, K/M suffix understood by both yt-dlp and curl. Empty means no limit. |
| `limit.pause` | seconds between two episodes, so the disk can flush |
| `limit.upload_failures` | give up after this many failed uploads in a row. **Default 3, in effect even without a config file.** 0 turns it off. |

The last one matters most. A failed upload leaves the file on disk, so without
it a run whose target is unreachable keeps downloading through the whole
playlist and fills the disk — which, on a single machine, takes the panel and
its database down with it.

The guard covers `playlist --episodes`, the bulk path. `shows`, `movie` and
`concat` upload too few files at a time for it to apply.

## Disk space

The download runs through a working directory that TheGoblin creates in the current directory — not in `/tmp`. In a Wings container `/tmp` is a tmpfs of a few hundred megabytes, and video plus audio track plus the muxed file blows through that immediately.

Reckon with about three times the video size in free space: separate video and audio files, the muxed mp4, plus the cut episodes. So for a 170 MB video, around 700 MB.

`GOBLIN_TMP` sets a different working directory, for instance on a mount with more room.

## When YouTube blocks

Some videos require a particular player client or a signed-in session. Recognisable by the video playing in the browser while yt-dlp reports `This video is not available`.

Two adjustments, both without a code change:

**Extra arguments** through the environment variable `YTDLP_ARGS` — appended to every yt-dlp call:

```
--extractor-args "youtube:player_client=web_safari,default"
```

To see what actually happens: `chapters <url> --verbose`. That prints the full yt-dlp command and passes its messages through instead of swallowing them with `--no-warnings`. With it you can see whether your extra arguments arrive and which player clients yt-dlp tried.

**Cookies**: if a `cookies.txt` sits in the working directory, TheGoblin uses it automatically. Export in Netscape format, e.g. through a browser extension.

A cookie file is a signed-in session of your account — treat it like a password, and expect that YouTube dislikes automated downloading with an account. To start with, try the player-client variant first.
