# Brief for AI agents working on this repo

Read this before changing anything.

## What this is

A Java CLI that turns YouTube material into a shape Jellyfin, Navidrome and
Plex read directly. The original case: a channel uploads a whole season as
*one* video with chapter marks — `goblin shows` cuts individual episode files
out of it, named to the `S01E02` scheme. On top of that come `movie`, `audio`,
`concat`, `playlist` and `chapters`.

## Non-negotiables

* **No external Java dependencies.** The project builds with `javac` and
  nothing else — hence the hand-written JSON parser in `Json.java`. Adding a
  library costs the `./build.sh` path and makes Maven mandatory. If something
  really needs a dependency, that is a decision for the repo owner, not for an
  agent.
* **The working directory is not in `/tmp`.** In a Wings container `/tmp` is a
  tmpfs of a few hundred megabytes, and video plus audio track plus the muxed
  file blows through it immediately. See `Goblin.workRoot()`, overridable
  through `GOBLIN_TMP`.
* **The default format is H.264/AAC in mp4**, not the best available quality.
  VP9 and AV1 force the media server to transcode, with AV1 entirely on the
  CPU for lack of a hardware decoder. `--best` exists for the deliberate
  opposite case.
* **Nothing is guessed.** Where a detection fails — no timestamp, no episode
  number in the title, no match in the overlap comparison — the item is
  skipped and reported at the end, not estimated onto a fallback value. A
  wrongly numbered hit would otherwise overwrite a correctly detected episode.
* **`goblin.properties` holds credentials** and is listed in `.gitignore`. Only
  `goblin.properties.example` is in the repo. Do not put real keys, hosts or
  paths into examples.

## Commits

Gitmoji, like this:

```
:sparkles: Add episode-per-video playlist mode
```

* **Shortcode spelling**, never the literal emoji — otherwise
  `git log --grep=':memo:'` will not find the commit. (`dc95f25` uses a
  literal 📝; that was an oversight and is not a model to follow.)
* **No body.** One line, nothing else.
* **English**, imperative, capital first letter, no full stop at the end.
* One commit per thematic change.

Emojis common here: `:sparkles:` new feature, `:bug:` bug fix, `:memo:` docs,
`:fire:` removing code or files, `:recycle:` refactor without behaviour change.

## Language

Everything — README, code comments, commit messages — is in English. The repo
used to have German prose with English commit messages; that was translated in
one pass, so do not reintroduce German.

## Verifying

There are **no tests**. What can be done:

```bash
./build.sh                      # javac + jar, fails on syntax errors
java -jar goblin.jar --help
```

A real run needs `yt-dlp`, `ffmpeg`, `ffprobe` and `curl` on the PATH plus
network access to YouTube. Where that is not available, the build is the whole
check — then say exactly that, and do not imply a change was tested.

The thresholds of the detection routines are calibrated against a single test
video each and described in the README: 0.90 for the correlation in the overlap
comparison (`AudioProbe`), a ±5 s search window for `--snap` (`CutDetect`).
Changing them means changing them without a safety net.

## Layout

| File | |
| --- | --- |
| `Goblin.java` | argument parsing and orchestration of all six commands |
| `YtDlp.java` | wrapper around yt-dlp, format selectors, cookie file |
| `Ffmpeg.java` / `Ffprobe.java` | cutting and determining runtime |
| `ChapterParser.java` | timestamps out of the video description |
| `CutDetect.java` | find the real picture change near a timestamp |
| `OutroDetect.java` | find the closing credits at the end of a part |
| `AudioProbe.java` | detect duplicate starts through loudness envelopes |
| `TitleNumbers.java` | read season and episode from the video title |
| `Naming.java` | build paths the way Jellyfin's scanner expects them |
| `Tmdb.java` | show ID, year, poster, backdrop image |
| `Sftp.java` | push finished files away through curl |
| `Json.java` | minimal JSON parser, replaces a dependency |

`Goblin.java` is by far the largest file at over a thousand lines. New commands
grow it further — that is known and so far deliberately accepted.

## Naming

The CLI command is `shows`. Internally the TMDb entity and the folder helpers
still say *series* (`Tmdb.Series`, `Naming.seriesFolder`) — that is the domain
word for a TV series and is intentionally not renamed. Note that
`TitleNumbers` matches the literal word `Series` inside video titles; renaming
that would break title parsing.
