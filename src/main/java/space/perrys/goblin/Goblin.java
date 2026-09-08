package space.perrys.goblin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point and command line.
 */
public final class Goblin {

    private static final String USAGE = """
            TheGoblin

            Commands:
              goblin shows <url> <name> [options]     split a video into episodes
              goblin chapters <url> [--verbose]       only show the detected chapters
              goblin chapters <url> --formats         list the available formats
              goblin movie <url> <title> [options]    store a video as a single movie
              goblin shows <url> <title> --movie      the same through 'shows'
              goblin audio <url> [options]            store the audio track as music
              goblin concat <url> <title>             join a playlist into one file
              goblin playlist <url> <name>            print ready-made shows lines
              goblin playlist <url> <name> --episodes one video per episode
                                                      --from/--to limit the range,
                                                      --from-title reads season and
                                                      episode from the title
              goblin chapters <url> --playlist <name> the same through 'chapters'

            Options for 'shows':
              -o, --out <path>        target directory (default: current directory)
              -s, --season <n>        season number (default: 1)
              -e, --start-episode <n> number of the first episode (default: 1)
                  --year <year>       release year, overrides TMDb
                  --tmdb-id <id>      pin the show ID instead of searching
                  --no-tmdb           fetch neither ID nor artwork
                  --best              best quality instead of H.264, lands in mkv
              -f, --format <sel>      custom yt-dlp format selector
                  --container <ext>   target container, default mp4
                  --chapters <file>   custom timestamps instead of the video's
                  --offset <seconds>  shift every boundary but the first
                  --snap [seconds]    pull boundaries onto the real picture change
                                      (search window, default 5)
                  --reencode          cut exactly instead of rounding to keyframes
                  --keep              keep the whole video after cutting
                  --upload            upload finished files over SFTP
                                      (credentials in goblin.properties)
                  --keep-local        keep the local copy after the upload
                  --dry-run           only show what would happen
              -v, --verbose           show the yt-dlp command and its messages

            Environment:
              TMDB_API_KEY            for the show ID and artwork (optional)
            """;

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static int run(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
            System.out.print(USAGE);
            return 0;
        }

        return switch (args[0]) {
            case "shows" -> shows(args);
            case "chapters" -> chapters(args);
            case "playlist" -> playlist(args);
            case "movie" -> movie(args);
            case "concat" -> concat(args);
            case "audio" -> audio(args);
            default -> {
                System.err.println("Unknown command: " + args[0]);
                System.err.print(USAGE);
                yield 2;
            }
        };
    }

    // ------------------------------------------------------------------
    // goblin chapters <url>
    // ------------------------------------------------------------------

    private static int chapters(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: goblin chapters <url> [--verbose]");
            return 2;
        }
        requireTools(false);

        boolean verbose = false;
        boolean formats = false;
        boolean playlistMode = false;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--verbose", "-v" -> verbose = true;
                case "--formats", "-F" -> formats = true;
                case "--playlist" -> playlistMode = true;
                default -> { }
            }
        }

        if (formats) {
            YtDlp.listFormats(args[1]);
            return 0;
        }

        if (playlistMode) {
            // So that playlists still work when the console wrapper only lets
            // 'chapters' through.
            return playlist(new String[] {"playlist", args[1], nameFrom(args)});
        }

        VideoMeta meta = YtDlp.metadata(args[1], verbose);
        List<Chapter> found = resolveChapters(meta);

        if (found.isEmpty()) {
            System.out.println("No chapters found.");
            return 1;
        }

        System.out.println(meta.title());
        System.out.println();
        for (int i = 0; i < found.size(); i++) {
            Chapter c = found.get(i);
            System.out.printf("  %2d. %-9s %s  (%.0f s)%n",
                    i + 1, c.timecode(), c.title(), c.duration());
        }
        return 0;
    }


    /**
     * One video per episode. Unlike shows, nothing is cut - every video of the
     * playlist is downloaded once and stored as a single episode. Existing
     * files are skipped so that an aborted run can be resumed instead of
     * starting over.
     */
    private static int episodes(List<String[]> entries, String name, Path out,
                                int season, int startEpisode, Integer year, Integer tmdbId,
                                boolean useTmdb, boolean withTitles, boolean fromTitle,
                                boolean overwrite, boolean dryRun, boolean verbose,
                                boolean upload, boolean keepLocal,
                                String format, String container) throws Exception {

        requireTools(!dryRun);

        Tmdb.Series series = null;
        Tmdb tmdb = useTmdb ? Tmdb.from(CONFIG) : null;
        if (tmdb != null) {
            try {
                series = (tmdbId != null) ? tmdb.byId(tmdbId) : tmdb.search(name);
                if (series != null) {
                    System.out.printf("TMDb: %s (%s), ID %d%n",
                            series.name(), series.year(), series.id());
                }
            } catch (IOException e) {
                System.out.println("TMDb unreachable, carrying on without it: " + e.getMessage());
            }
        }

        Integer folderYear = (year != null) ? year : (series != null ? series.year() : null);
        Integer folderId = (tmdbId != null) ? tmdbId : (series != null ? series.id() : null);

        Path seriesDir = out.resolve(Naming.seriesFolder(name, folderYear, folderId));

        // Determine the target paths up front. With --from-title every entry
        // can land in a different season, so each entry gets its own path.
        // Entries without a recognisable number are not guessed at with
        // --from-title. A sequentially numbered fallback would collide with
        // correctly detected episodes and overwrite them - better to report
        // them and leave them to the operator.
        List<Path> targets = new ArrayList<>();
        List<String[]> usable = new ArrayList<>();
        List<String> unparsed = new ArrayList<>();
        int running = startEpisode;

        for (String[] entry : entries) {
            int sn = season;
            int ep;

            if (fromTitle) {
                var ref = TitleNumbers.parse(entry[1], season);
                if (ref.isEmpty()) {
                    unparsed.add(entry[1].isBlank() ? entry[0] : entry[1]);
                    continue;
                }
                sn = ref.get().season();
                ep = ref.get().episode();
            } else {
                ep = running++;
            }

            usable.add(entry);
            targets.add(seriesDir.resolve(Naming.seasonFolder(sn))
                    .resolve(fileFor(name, sn, ep, entry, withTitles, container)));
        }

        entries = usable;

        System.out.printf("%d videos -> %s%n%n", entries.size(), seriesDir);

        if (!unparsed.isEmpty()) {
            System.out.printf("%d without a recognisable number, skipped:%n", unparsed.size());
            for (String t : unparsed) {
                System.out.println("  " + t);
            }
            System.out.println();
        }

        if (entries.isEmpty()) {
            System.err.println("No video with a recognisable number. Try without --from-title.");
            return 1;
        }

        if (dryRun) {
            for (Path t : targets) {
                System.out.println("  " + seriesDir.relativize(t));
            }
            System.out.println();
            System.out.println("Dry run, nothing was written.");
            return 0;
        }

        Sftp sftp = upload ? Sftp.fromConfig(CONFIG) : null;
        if (upload && sftp == null) {
            System.err.println("--upload was given, but " + CONFIG + " is missing or incomplete.");
            return 2;
        }
        if (sftp != null) {
            System.out.println("Uploading to " + sftp.describe());
            System.out.println();
        }

        int done = 0;
        int skipped = 0;
        int failed = 0;

        for (int i = 0; i < entries.size(); i++) {
            Path target = targets.get(i);
            String fileName = target.getFileName().toString();

            if (!overwrite && Files.exists(target)) {
                System.out.println("  exists: " + fileName);
                skipped++;
                continue;
            }

            System.out.printf("  [%d/%d] %s%n", i + 1, entries.size(),
                    seriesDir.relativize(target));
            try {
                Files.createDirectories(target.getParent());
                String stem = fileName.substring(0, fileName.lastIndexOf('.'));
                YtDlp.download("https://youtu.be/" + entries.get(i)[0],
                        target.getParent().resolve(stem), format, container);
                uploadIfConfigured(sftp, out, target, keepLocal);
                done++;
            } catch (IOException e) {
                // One broken video should not stop the other 50
                System.out.println("    failed: " + e.getMessage());
                failed++;
            }
        }

        if (tmdb != null && series != null && Files.exists(seriesDir)) {
            tmdb.downloadArtwork(series, seriesDir);
        }

        System.out.println();
        System.out.printf("Done. %d downloaded, %d skipped, %d failed.%n",
                done, skipped, failed);
        if (failed > 0) {
            System.out.println("Run the same command again - what is already there gets skipped.");
        }
        return failed > 0 ? 1 : 0;
    }

    private static String fileFor(String name, int season, int episode,
                                  String[] entry, boolean withTitles, String container) {
        String title = withTitles ? entry[1] : "";
        return Naming.episodeFile(name, season, episode, title, container);
    }

    // ------------------------------------------------------------------
    // goblin movie <url> <title>
    // ------------------------------------------------------------------

    /**
     * Downloads a video as a single movie. No cutting, no chapters - just
     * download it and store it the way Jellyfin's movie scanner expects.
     */
    private static int movie(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: goblin movie <url> <title> [options]");
            return 2;
        }

        String url = args[1];
        String title = args[2];

        Path out = Path.of("output/movies");
        Integer year = null;
        Integer tmdbId = null;
        boolean useTmdb = true;
        boolean dryRun = false;
        boolean verbose = false;
        boolean upload = false;
        boolean keepLocal = false;
        String format = YtDlp.FORMAT_H264;
        String container = "mp4";

        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "-o", "--out" -> out = Path.of(args[++i]);
                case "--upload" -> upload = true;
                case "--keep-local" -> keepLocal = true;
                case "--year" -> year = Integer.valueOf(args[++i]);
                case "--tmdb-id" -> tmdbId = Integer.valueOf(args[++i]);
                case "--no-tmdb" -> useTmdb = false;
                case "--dry-run" -> dryRun = true;
                case "--verbose", "-v" -> verbose = true;
                case "--movie" -> { }
                case "--best" -> {
                    format = YtDlp.FORMAT_BEST;
                    container = "mkv";
                }
                case "--format", "-f" -> format = args[++i];
                case "--container" -> container = args[++i];
                default -> {
                    System.err.println("Unknown option: " + args[i]);
                    return 2;
                }
            }
        }

        requireTools(!dryRun);

        System.out.println("Fetching metadata ...");
        VideoMeta meta = YtDlp.metadata(url, verbose);
        System.out.println("Video: " + meta.title());

        Tmdb.Series film = null;
        Tmdb tmdb = useTmdb ? Tmdb.from(CONFIG) : null;
        if (tmdb != null) {
            try {
                film = (tmdbId != null) ? tmdb.movieById(tmdbId) : tmdb.searchMovie(title);
                if (film != null) {
                    System.out.printf("TMDb: %s (%s), ID %d%n", film.name(), film.year(), film.id());
                }
            } catch (IOException e) {
                System.out.println("TMDb unreachable, carrying on without it: " + e.getMessage());
            }
        } else if (useTmdb) {
            System.out.println("No TMDb key (tmdb.api_key in " + CONFIG
                    + " or TMDB_API_KEY), skipping artwork.");
        }

        Integer folderYear = (year != null) ? year : (film != null ? film.year() : null);
        Integer folderId = (tmdbId != null) ? tmdbId : (film != null ? film.id() : null);

        Path movieDir = out.resolve(Naming.movieFolder(title, folderYear, folderId));
        String fileName = Naming.movieFile(title, folderYear, container);

        System.out.println();
        System.out.println(movieDir);
        System.out.println("  " + fileName);
        System.out.println();

        if (dryRun) {
            System.out.println("Dry run, nothing was written.");
            return 0;
        }

        Files.createDirectories(movieDir);

        Sftp sftp = upload ? Sftp.fromConfig(CONFIG) : null;
        if (upload && sftp == null) {
            System.err.println("--upload was given, but " + CONFIG + " is missing or incomplete.");
            return 2;
        }

        System.out.println("Downloading video ...");
        String stem = fileName.substring(0, fileName.lastIndexOf('.'));
        YtDlp.download(url, movieDir.resolve(stem), format, container);

        if (tmdb != null && film != null) {
            tmdb.downloadArtwork(film, movieDir);
        }

        uploadIfConfigured(sftp, out, movieDir.resolve(fileName), keepLocal);

        System.out.println();
        System.out.println("Done. " + movieDir.resolve(fileName));
        return 0;
    }



    // ------------------------------------------------------------------
    // goblin audio <url>
    // ------------------------------------------------------------------

    /**
     * Extracts the audio track from a video or a playlist.
     *
     * Stored in the usual layout for music collections:
     * Artist / Album / NN - Title.ext
     *
     * Artist and album come from the given values, or failing that from the
     * channel and the playlist title.
     */
    private static int audio(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: goblin audio <url> [options]");
            return 2;
        }

        String url = args[1];

        Path out = Path.of("output/music");
        String artist = null;
        String album = null;
        String format = "mp3";
        int quality = 0;
        boolean splitChapters = false;
        boolean dryRun = false;

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "-o", "--out" -> out = Path.of(args[++i]);
                case "--artist" -> artist = args[++i];
                case "--album" -> album = args[++i];
                case "--format", "-f" -> format = args[++i];
                case "--quality" -> quality = Integer.parseInt(args[++i]);
                case "--chapters" -> splitChapters = true;
                case "--dry-run" -> dryRun = true;
                default -> {
                    System.err.println("Unknown option: " + args[i]);
                    return 2;
                }
            }
        }

        requireTools(!dryRun);

        boolean isPlaylist = url.contains("list=") || url.contains("playlist")
                || url.contains("video_ids=");

        if (artist == null || album == null) {
            String[] meta = isPlaylist
                    ? YtDlp.playlistMeta(url)
                    : new String[] {"", ""};

            if (album == null && !meta[0].isBlank()) {
                album = meta[0];
            }
            if (artist == null && !meta[1].isBlank()) {
                artist = meta[1];
            }
        }

        // Whatever could not be determined is left to yt-dlp's own pattern.
        String artistDir = (artist != null) ? Naming.sanitize(artist) : "%(artist,uploader)s";
        String albumDir = (album != null) ? Naming.sanitize(album) : "%(album,title)s";

        String fileName = isPlaylist
                ? "%(playlist_index)02d - %(title)s.%(ext)s"
                : "%(title)s.%(ext)s";
        if (splitChapters) {
            fileName = "%(section_number)02d - %(section_title)s.%(ext)s";
        }

        String template = out.resolve(artistDir).resolve(albumDir).resolve(fileName).toString();

        System.out.println("Artist:  " + (artist != null ? artist : "(from the metadata)"));
        System.out.println("Album:   " + (album != null ? album : "(from the metadata)"));
        System.out.println("Format:  " + ("best".equalsIgnoreCase(format)
                ? "original track, no re-encoding" : format));
        System.out.println("Pattern: " + template);
        System.out.println();

        if (dryRun) {
            System.out.println("Dry run, nothing was downloaded.");
            return 0;
        }

        if (!"best".equalsIgnoreCase(format) && List.of("flac", "wav").contains(format.toLowerCase())) {
            System.out.println("Note: YouTube already serves lossy audio. "
                    + format + " makes the files bigger, not better.");
            System.out.println();
        }

        YtDlp.audio(url, format, quality, template, splitChapters);

        System.out.println();
        System.out.println("Done. " + out.resolve(artistDir).resolve(albumDir));
        return 0;
    }

    // ------------------------------------------------------------------
    // goblin concat <playlist-url> <title>
    // ------------------------------------------------------------------

    /**
     * Joins the videos of a playlist into one file.
     *
     * Before joining, each part is checked for closing credits at the end and
     * for whether the next part begins with the end of the previous one. Both
     * are cut away so that no duplication ends up in the finished video.
     */
    private static int concat(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: goblin concat <playlist-url> <title> [options]");
            return 2;
        }

        String url = args[1];
        String title = args[2];

        Path out = Path.of("output");
        double outroFixed = -1;
        boolean detectOutro = true;
        double overlapWindow = 90;
        // 1 s is enough: with a real overlap the correlation is close to 1,
        // chance matches stay well below that. Measured on one test
        // transition: real match 0.999, best false match 0.80.
        double minOverlap = 1;
        double maxOverlap = 30;
        double minScore = 0.90;
        boolean dryRun = false;
        boolean keepParts = false;
        String format = YtDlp.FORMAT_H264;
        String container = "mp4";

        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "-o", "--out" -> out = Path.of(args[++i]);
                case "--outro" -> outroFixed = Double.parseDouble(args[++i]);
                case "--no-outro" -> detectOutro = false;
                case "--overlap" -> overlapWindow = Double.parseDouble(args[++i]);
                case "--no-overlap" -> overlapWindow = 0;
                case "--min-score" -> minScore = Double.parseDouble(args[++i]);
                case "--min-overlap" -> minOverlap = Double.parseDouble(args[++i]);
                case "--max-overlap" -> maxOverlap = Double.parseDouble(args[++i]);
                case "--dry-run" -> dryRun = true;
                case "--keep-parts" -> keepParts = true;
                case "--best" -> {
                    format = YtDlp.FORMAT_BEST;
                    container = "mkv";
                }
                case "--format", "-f" -> format = args[++i];
                case "--container" -> container = args[++i];
                default -> {
                    System.err.println("Unknown option: " + args[i]);
                    return 2;
                }
            }
        }

        requireTools(!dryRun);

        List<String[]> entries = YtDlp.playlist(url);
        if (entries.size() < 2) {
            System.err.println("Fewer than two videos - concat is not worth it for that.");
            return 1;
        }

        System.out.printf("%d parts%n", entries.size());
        if (dryRun) {
            for (int i = 0; i < entries.size(); i++) {
                System.out.printf("  %2d. %s%n", i + 1,
                        entries.get(i)[1].isBlank() ? entries.get(i)[0] : entries.get(i)[1]);
            }
            System.out.println();
            System.out.println("Dry run, nothing was downloaded.");
            return 0;
        }

        Path work = Files.createTempDirectory(workRoot(), "goblin-concat-");
        Path target = out.resolve(Naming.sanitize(title) + "." + container);
        Files.createDirectories(out);

        try {
            // 1. Download every part
            List<Path> parts = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                System.out.printf("Downloading part %d/%d ...%n", i + 1, entries.size());
                parts.add(YtDlp.download("https://youtu.be/" + entries.get(i)[0],
                        work.resolve(String.format("part-%03d", i + 1)), format, container));
            }

            // 2. Determine the boundaries
            System.out.println();
            System.out.println("Checking transitions ...");

            List<double[]> cuts = new ArrayList<>(); // per part: {start, end}
            for (int i = 0; i < parts.size(); i++) {
                double duration = Ffprobe.duration(parts.get(i));
                double start = 0;
                double end = duration;

                if (i < parts.size() - 1 && detectOutro) {
                    if (outroFixed >= 0) {
                        end = Math.max(0, duration - outroFixed);
                    } else {
                        var outro = OutroDetect.find(parts.get(i), duration);
                        if (outro.isPresent()) {
                            end = outro.getAsDouble();
                        }
                    }
                }

                if (i > 0 && overlapWindow > 0) {
                    double prevEnd = cuts.get(i - 1)[1];
                    double[] tail = AudioProbe.envelope(parts.get(i - 1),
                            Math.max(0, prevEnd - overlapWindow),
                            Math.min(overlapWindow, prevEnd));
                    double[] head = AudioProbe.envelope(parts.get(i), 0, overlapWindow);

                    var match = AudioProbe.bestMatch(tail, head, minOverlap, maxOverlap, minScore);
                    if (match.isPresent()) {
                        double lag = match.getAsDouble();
                        double windowStart = Math.max(0, prevEnd - overlapWindow);
                        double overlapLength = prevEnd - (windowStart + lag);
                        if (overlapLength > 0.5 && overlapLength < overlapWindow) {
                            start = overlapLength;
                        }
                    }
                }

                cuts.add(new double[] {start, end});
                System.out.printf("  Part %2d: %s to %s%s%s%n", i + 1,
                        Chapter.timecode(start), Chapter.timecode(end),
                        start > 0 ? String.format("  (%.1fs duplicate start)", start) : "",
                        end < duration ? String.format("  (%.1fs credits)", duration - end) : "");
            }

            // 3. Trim the parts and join them
            System.out.println();
            System.out.println("Joining ...");

            List<Path> segments = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                double start = cuts.get(i)[0];
                double end = cuts.get(i)[1];
                if (end - start < 1.0) {
                    System.out.printf("  Part %d is empty after the cut, skipped.%n", i + 1);
                    continue;
                }
                Path segment = work.resolve(String.format("seg-%03d.%s", i + 1, container));
                Ffmpeg.cut(parts.get(i), new Chapter(start, end, ""), segment, true);
                segments.add(segment);
            }

            Ffmpeg.concat(segments, work.resolve("list.txt"), target);

            if (keepParts) {
                System.out.println("Parts remain in " + work);
            }
        } finally {
            if (!keepParts) {
                deleteTree(work);
            }
        }

        System.out.println();
        System.out.println("Done. " + target);
        return 0;
    }

    // ------------------------------------------------------------------
    // goblin playlist <url> <name>
    // ------------------------------------------------------------------

    /**
     * Reads a playlist and prints a ready-made shows line for every video. The
     * season number rises with the position - which fits when the playlist
     * holds one video per season. Otherwise adjust the lines before running
     * them.
     */
    private static int playlist(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: goblin playlist <url> <name> [options]");
            return 2;
        }
        requireTools(false);

        String url = args[1];
        String name = args[2];

        String out = "output/shows";
        int firstSeason = 1;
        String extra = "--snap --reencode";

        boolean episodeMode = false;
        int from = 1;
        int to = Integer.MAX_VALUE;
        int startEpisode = 1;
        Integer year = null;
        Integer tmdbId = null;
        boolean useTmdb = true;
        boolean withTitles = false;
        boolean fromTitle = false;
        boolean overwrite = false;
        boolean upload = false;
        boolean keepLocal = false;
        boolean dryRun = false;
        boolean verbose = false;
        String format = YtDlp.FORMAT_H264;
        String container = "mp4";

        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "-o", "--out" -> out = args[++i];
                case "-s", "--season" -> firstSeason = Integer.parseInt(args[++i]);
                case "--extra" -> extra = args[++i];
                case "--episodes" -> episodeMode = true;
                case "--from" -> from = Integer.parseInt(args[++i]);
                case "--to" -> to = Integer.parseInt(args[++i]);
                case "-e", "--start-episode" -> startEpisode = Integer.parseInt(args[++i]);
                case "--year" -> year = Integer.valueOf(args[++i]);
                case "--tmdb-id" -> tmdbId = Integer.valueOf(args[++i]);
                case "--no-tmdb" -> useTmdb = false;
                case "--titles" -> withTitles = true;
                case "--from-title" -> fromTitle = true;
                case "--upload" -> upload = true;
                case "--keep-local" -> keepLocal = true;
                case "--overwrite" -> overwrite = true;
                case "--dry-run" -> dryRun = true;
                case "--verbose", "-v" -> verbose = true;
                case "--best" -> {
                    format = YtDlp.FORMAT_BEST;
                    container = "mkv";
                }
                case "--format", "-f" -> format = args[++i];
                case "--container" -> container = args[++i];
                default -> {
                    System.err.println("Unknown option: " + args[i]);
                    return 2;
                }
            }
        }

        List<String[]> entries = YtDlp.playlist(url);
        if (entries.isEmpty()) {
            System.err.println("The playlist contains no retrievable videos.");
            return 1;
        }

        int total = entries.size();
        int firstIndex = Math.max(1, from) - 1;
        int lastIndex = Math.min(total, to);
        if (firstIndex >= lastIndex) {
            System.err.printf("Empty range: %d videos present, --from %d --to %d%n",
                    total, from, to);
            return 2;
        }
        if (firstIndex > 0 || lastIndex < total) {
            entries = entries.subList(firstIndex, lastIndex);
            System.out.printf("Range %d to %d of %d videos%n", firstIndex + 1, lastIndex, total);
        }

        if (episodeMode) {
            return episodes(entries, name, Path.of(out), firstSeason, startEpisode,
                    year, tmdbId, useTmdb, withTitles, fromTitle, overwrite, dryRun, verbose,
                    upload, keepLocal, format, container);
        }

        System.out.printf("%d videos in the playlist%n%n", entries.size());
        for (int i = 0; i < entries.size(); i++) {
            String title = entries.get(i)[1];
            System.out.printf("# %d. %s%n", firstIndex + i + 1,
                    title.isBlank() ? "(no title)" : title);
            System.out.printf("shows https://youtu.be/%s \"%s\" --out %s --season %d %s%n%n",
                    entries.get(i)[0], name, out, firstSeason + i, extra);
        }

        System.out.println("Check the lines, then run them one by one.");
        return 0;
    }

    // ------------------------------------------------------------------
    // goblin shows <url> <name>
    // ------------------------------------------------------------------

    private static int shows(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: goblin shows <url> <name> [options]");
            return 2;
        }

        for (String a : args) {
            if (a.equals("--movie")) {
                // So that movies still work when the wrapper only knows 'shows'.
                String[] forwarded = args.clone();
                forwarded[0] = "movie";
                return movie(forwarded);
            }
        }

        String url = args[1];
        String name = args[2];

        Path out = Path.of(".");
        int season = 1;
        int startEpisode = 1;
        Integer year = null;
        Integer tmdbId = null;
        boolean useTmdb = true;
        boolean reencode = false;
        boolean keep = false;
        boolean dryRun = false;
        boolean verbose = false;
        String format = YtDlp.FORMAT_H264;
        String container = "mp4";
        Path chapterFile = null;
        double offset = 0;
        double snapWindow = 0;
        boolean upload = false;
        boolean keepLocal = false;

        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "-o", "--out" -> out = Path.of(args[++i]);
                case "-s", "--season" -> season = Integer.parseInt(args[++i]);
                case "-e", "--start-episode" -> startEpisode = Integer.parseInt(args[++i]);
                case "--year" -> year = Integer.valueOf(args[++i]);
                case "--tmdb-id" -> tmdbId = Integer.valueOf(args[++i]);
                case "--no-tmdb" -> useTmdb = false;
                case "--reencode" -> reencode = true;
                case "--keep" -> keep = true;
                case "--dry-run" -> dryRun = true;
                case "--verbose", "-v" -> verbose = true;
                case "--best" -> {
                    format = YtDlp.FORMAT_BEST;
                    container = "mkv";
                }
                case "--format", "-f" -> format = args[++i];
                case "--chapters" -> chapterFile = Path.of(args[++i]);
                case "--offset" -> offset = Double.parseDouble(args[++i]);
                case "--upload" -> upload = true;
                case "--keep-local" -> keepLocal = true;
                case "--snap" -> snapWindow = (i + 1 < args.length && !args[i + 1].startsWith("-"))
                        ? Double.parseDouble(args[++i])
                        : 5.0;
                case "--container" -> container = args[++i];
                default -> {
                    System.err.println("Unknown option: " + args[i]);
                    return 2;
                }
            }
        }

        requireTools(!dryRun);

        // 1. Metadata and chapters
        System.out.println("Fetching metadata ...");
        VideoMeta meta = YtDlp.metadata(url, verbose);

        List<Chapter> parts;
        if (chapterFile != null) {
            parts = ChapterParser.parse(Files.readString(chapterFile), meta.duration());
            if (parts.isEmpty()) {
                System.err.println("No timestamps could be read from " + chapterFile + ".");
                return 1;
            }
            System.out.println("Chapters from " + chapterFile);
        } else {
            parts = resolveChapters(meta);
        }

        if (offset != 0) {
            parts = shift(parts, offset, meta.duration());
            System.out.printf("Offset: %+.1f s from the second section on%n", offset);
        }

        if (parts.isEmpty()) {
            System.err.println("""
                    No chapters found. The video has neither YouTube chapters nor
                    recognisable timestamps in its description.""");
            return 1;
        }
        System.out.printf("%d sections in \"%s\"%n", parts.size(), meta.title());

        // 2. Look the show up in the database
        Tmdb.Series series = null;
        Tmdb tmdb = useTmdb ? Tmdb.from(CONFIG) : null;
        if (tmdb != null) {
            try {
                series = (tmdbId != null) ? tmdb.byId(tmdbId) : tmdb.search(name);
                if (series != null) {
                    System.out.printf("TMDb: %s (%s), ID %d%n",
                            series.name(), series.year(), series.id());
                }
            } catch (IOException e) {
                System.out.println("TMDb unreachable, carrying on without it: " + e.getMessage());
            }
        } else if (useTmdb) {
            System.out.println("No TMDb key (tmdb.api_key in " + CONFIG
                    + " or TMDB_API_KEY), skipping artwork.");
        }

        Integer folderYear = (year != null) ? year : (series != null ? series.year() : null);
        Integer folderId = (tmdbId != null) ? tmdbId : (series != null ? series.id() : null);

        Path seriesDir = out.resolve(Naming.seriesFolder(name, folderYear, folderId));
        Path seasonDir = seriesDir.resolve(Naming.seasonFolder(season));

        // 3. Preview
        System.out.println();
        System.out.println(seasonDir);
        for (int i = 0; i < parts.size(); i++) {
            System.out.println("  " + Naming.episodeFile(
                    name, season, startEpisode + i, parts.get(i).title(), container));
        }
        System.out.println();

        if (dryRun) {
            System.out.println("Dry run, nothing was written.");
            return 0;
        }

        Sftp sftp = upload ? Sftp.fromConfig(CONFIG) : null;
        if (upload && sftp == null) {
            System.err.println("--upload was given, but " + CONFIG + " is missing or incomplete.");
            return 2;
        }
        if (sftp != null) {
            System.out.println("Uploading to " + sftp.describe());
        }

        Files.createDirectories(seasonDir);

        // 4. Download the whole video once
        Path work = Files.createTempDirectory(workRoot(), "goblin-");
        Path source;
        try {
            System.out.println("Downloading video ...");
            source = YtDlp.download(url, work.resolve("source"), format, container);

            // 5. Pull the boundaries onto the actual picture change
            if (snapWindow > 0) {
                parts = snap(source, parts, snapWindow, meta.duration());
            }

            // 6. Cut
            System.out.println("Cutting ...");
            for (int i = 0; i < parts.size(); i++) {
                Chapter c = parts.get(i);
                Path target = seasonDir.resolve(
                        Naming.episodeFile(name, season, startEpisode + i, c.title(), container));
                Ffmpeg.cut(source, c, target, reencode);
                System.out.println("  " + target.getFileName());
                uploadIfConfigured(sftp, out, target, keepLocal);
            }

            if (keep) {
                Path kept = seriesDir.resolve("source-" + meta.id() + "." + container);
                Files.move(source, kept);
                System.out.println("Source kept: " + kept);
            }
        } finally {
            if (!keep) {
                deleteTree(work);
            }
        }

        // 7. Artwork
        if (tmdb != null && series != null) {
            tmdb.downloadArtwork(series, seriesDir);
        }

        System.out.println();
        System.out.println("Done. " + parts.size() + " episodes in " + seasonDir);
        return 0;
    }

    // ------------------------------------------------------------------

    /**
     * Working directory for the download. Deliberately NOT /tmp: in a Wings
     * container that is a tmpfs of a few hundred megabytes, and a video plus
     * audio track plus the muxed file blows through it immediately. The server
     * directory is used instead, which is subject to the server's disk limit.
     */
    private static Path workRoot() throws IOException {
        String override = System.getenv("GOBLIN_TMP");
        Path root = (override == null || override.isBlank())
                ? Path.of(".").toAbsolutePath().normalize()
                : Path.of(override);
        Files.createDirectories(root);
        return root;
    }

    /** Configuration file for the SFTP upload. */
    private static final Path CONFIG = Path.of("goblin.properties");

    /**
     * Uploads a finished file and deletes it locally afterwards. Without a
     * configured SFTP target nothing happens and the file stays where it is.
     *
     * @param root    directory the remote path is formed relative to
     * @param keepLocal true leaves the local copy in place
     * @return true when the upload happened
     */
    private static boolean uploadIfConfigured(Sftp sftp, Path root, Path file, boolean keepLocal) {
        if (sftp == null) {
            return false;
        }
        try {
            String remote = root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
            sftp.upload(file, remote);
            if (!keepLocal) {
                Files.deleteIfExists(file);
            }
            System.out.println("    uploaded: " + remote);
            return true;
        } catch (IOException | InterruptedException e) {
            System.out.println("    upload failed, file stays local: " + e.getMessage());
            return false;
        }
    }

    /** Takes the first non-option argument after the URL as the show name. */
    private static String nameFrom(String[] args) {
        for (int i = 2; i < args.length; i++) {
            if (!args[i].startsWith("-")) {
                return args[i];
            }
        }
        return "Show";
    }

    /**
     * Pulls every section boundary onto the picture change closest to it. The
     * first section stays at 0.
     */
    private static List<Chapter> snap(Path video, List<Chapter> parts, double window, double duration) {
        System.out.printf("Searching for boundaries (window %.0f s) ...%n", window);

        List<Double> starts = new ArrayList<>();
        starts.add(parts.get(0).start());

        for (int i = 1; i < parts.size(); i++) {
            double wanted = parts.get(i).start();
            CutDetect.Result found = CutDetect.nearest(video, wanted, window);

            String note = switch (found.source()) {
                case BLACK -> "black frame";
                case SCENE -> "scene change";
                case NONE -> "nothing found, unchanged";
            };
            System.out.printf("  %s -> %s  (%+.2f s, %s)%n",
                    Chapter.timecode(wanted), Chapter.timecode(found.time()),
                    found.time() - wanted, note);

            starts.add(found.time());
        }

        List<Chapter> snapped = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            double end = (i + 1 < starts.size()) ? starts.get(i + 1) : duration;
            if (end - starts.get(i) < 1.0) {
                continue;
            }
            snapped.add(new Chapter(starts.get(i), end, parts.get(i).title()));
        }
        return snapped;
    }

    /**
     * Shifts every section boundary except the very first. The first section
     * always starts at 0 - otherwise the beginning of the video would be lost.
     * Meant for the case where the timestamps in the description are all a few
     * seconds too early.
     */
    private static List<Chapter> shift(List<Chapter> parts, double offset, double duration) {
        List<Double> starts = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            double start = (i == 0) ? parts.get(0).start() : parts.get(i).start() + offset;
            starts.add(Math.max(0, Math.min(start, duration)));
        }

        List<Chapter> shifted = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            double end = (i + 1 < starts.size()) ? starts.get(i + 1) : duration;
            if (end - starts.get(i) < 1.0) {
                continue;
            }
            shifted.add(new Chapter(starts.get(i), end, parts.get(i).title()));
        }
        return shifted;
    }

    /** YouTube chapters take priority, otherwise search the description. */
    private static List<Chapter> resolveChapters(VideoMeta meta) {
        if (!meta.chapters().isEmpty()) {
            return meta.chapters();
        }
        return ChapterParser.parse(meta.description(), meta.duration());
    }

    private static void requireTools(boolean needFfmpeg) {
        List<String> missing = new ArrayList<>();
        if (!Proc.exists("yt-dlp")) {
            missing.add("yt-dlp");
        }
        if (needFfmpeg && !Proc.exists("ffmpeg")) {
            missing.add("ffmpeg");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Not found on the PATH: " + String.join(", ", missing));
        }
    }

    private static void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // Cleanup is best effort
                        }
                    });
        } catch (IOException ignored) {
            // ditto
        }
    }
}
