# TheGoblin

Splits a YouTube video into individual episode files along its chapters and stores them the way Jellyfin's scanner expects them.

## Requirements

- Java 21+
- `yt-dlp` and `ffmpeg` on the PATH

The project has no external Java dependencies — `./build.sh` is enough, Maven is optional (`mvn package`).

## Installation

```bash
./build.sh
sudo ln -s "$PWD/goblin" /usr/local/bin/goblin
```

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

Every episode then lands in the right season folder, wherever it sits in the list. Among others `S01E02`, `s1e2`, `Season 2 Episode 5`, `Staffel 4 Folge 3` and `3x12` are recognised.  If the title names only an episode number, the season comes from `--season`.

Videos without a recognisable number are **skipped and listed at the end** — not guessed at. A sequentially numbered fallback would otherwise overwrite a correctly detected episode. Mostly those are trailers or compilations that do not belong in the season anyway.

Always check with `--dry-run` first: the output shows the planned path including the season folder for every video.

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
