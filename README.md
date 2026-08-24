# TheGoblin

Splits a YouTube video into individual episode files based on its chapters and stores them as expected by Jellyfin's scanner.

## Prerequisites

* Java 21+
* `yt-dlp` and `ffmpeg` in your PATH

The project has no external Java dependencies — `./build.sh` is sufficient, Maven is optional (`mvn package`).

## Installation

```bash
./build.sh
sudo ln -s "$PWD/goblin" /usr/local/bin/goblin

```

## Usage

```bash
# Check contents first
goblin chapters https://www.youtube.com/watch?v=...

# Then split
goblin series https://www.youtube.com/watch?v=... "Ninjago" \
    --out /srv/media/shows --season 1

```

### Options

| Option | Description |
| --- | --- |
| `-o, --out <path>` | Output directory, defaults to current directory |
| `-s, --season <n>` | Season number, defaults to 1 |
| `-e, --start-episode <n>` | First episode number, defaults to 1 |
| `--year <year>` | Release year, overrides TMDb |
| `--tmdb-id <id>` | Specify series ID manually instead of searching |
| `--no-tmdb` | Skip database lookup and artwork download |
| `--reencode` | Cut frame-accurately instead of rounding to keyframes |
| `--keep` | Keep the full video file after cutting |
| `--dry-run` | Only show what would happen |

## Output Structure

```
Ninjago (2011) [tmdbid-12345]/
  poster.jpg
  backdrop.jpg
  Season 01/
    Ninjago S01E01 - Way of the Ninja.mp4
    Ninjago S01E02 - The Golden Weapon.mp4

```

## Series Database

Using a free TMDb API key, TheGoblin retrieves the series ID, release year, poster, and background image:

```bash
export TMDB_API_KEY=your_key

```

The ID is added to the folder name so Jellyfin doesn't have to guess the series identity. Without a key, all other features work unchanged.

## How Chapters Are Detected

1. If YouTube recognized the timestamps as chapters, `yt-dlp` uses them directly — this is the most reliable method.
2. Otherwise, the description is parsed. It looks first for lines starting with a timestamp (the standard chapter list format). If none are found, it performs a looser search, e.g., `Episode 1 - Way of the Ninja - 0:00`.

Lines like `New videos at 10:00 AM every Saturday` or `Live um 20:15 Uhr` are filtered out — otherwise, you would end up with an episode named "AM every Saturday" in your media library.

The final section always runs until the end of the video. For compilations that include end credits or ads at the very end, running `goblin chapters` beforehand is recommended.

## Cutting Precision

By default, cutting is performed without re-encoding (`-c copy`). This takes seconds instead of minutes, but ffmpeg can only cut on keyframes — boundaries may be off by a few seconds, and the beginning of a clip might briefly freeze.

For back-to-back episodes, this is usually unnoticeable. If it bothers you, use `--reencode`. This guarantees precise cuts at the cost of CPU time and a slight loss in quality.

## Download Format

The tool prefers H.264 with AAC inside MP4. Otherwise, YouTube defaults to VP9 or AV1 in WebM, which many client devices cannot play natively — forcing the media server to transcode (and for AV1 without a hardware decoder, entirely on the CPU).

## Future Ideas

* `goblin playlist <url>` for entire playlists, mapping one episode per video instead of per chapter
* Detecting existing episodes to avoid blindly overwriting them
* `--map` with a file mapping chapter numbers to episode numbers, for cases where TMDb ordering differs from the video
* Native binary option: Quarkus with `quarkus-picocli` and Native Image for instant execution without JVM startup time

## Disk Space Requirements

Downloads are processed in a working directory created by TheGoblin in the current working directory — not in `/tmp`. In Wings containers, `/tmp` is often a tmpfs with only a few hundred megabytes, which fills up immediately when handling separate audio, video, and muxed files.

Plan for roughly three times the raw video size in free space: separate video and audio streams, the muxed MP4, and the final extracted episode files. For example, a 170 MB video will require around 700 MB of temp space.

Use `GOBLIN_TMP` to specify an alternative temporary directory on a mount with more space.

## Troubleshooting YouTube Blocks

Some videos require a specific player client or an authenticated session. You can spot this if the video plays fine in a web browser, but yt-dlp returns `This video is not available`.

Two workarounds are available without needing code changes:

**Additional arguments** via the `YTDLP_ARGS` environment variable — passed directly to every yt-dlp invocation:

```
--extractor-args "youtube:player_client=web_safari,default"

```

To see what is happening under the hood, run `chapters <url> --verbose`. This prints the full yt-dlp command and forwards its logs instead of suppressing them with `--no-warnings`. This makes it easy to verify whether your extra arguments are applied and which player clients yt-dlp tried.

**Cookies**: If a `cookies.txt` file exists in the working directory, TheGoblin uses it automatically. You can export this file in Netscape format using a browser extension.

*Note: A cookie file represents an active login session for your account — treat it like a password, and keep in mind that YouTube may flag accounts used for automated downloading. It is recommended to try the player-client workaround first.*
