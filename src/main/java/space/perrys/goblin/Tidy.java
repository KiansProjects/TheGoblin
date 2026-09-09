package space.perrys.goblin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sorts a folder of loose files into the layout a media server expects.
 *
 * The counterpart to everything else here: the other commands produce a tidy
 * library because they know what they downloaded, this one is for the files
 * that arrived some other way - a zip somebody unpacked into the music folder,
 * thirty-four comics dropped in a heap.
 *
 * Three sources of truth, in this order:
 *
 *   1. What the file says about itself - ComicInfo.xml inside a .cbz, the tags
 *      in an audio file. Written by whoever produced the file, and it survives
 *      every rename on the way here.
 *   2. What the file name says. Weaker, but it is all a video file offers.
 *   3. What the database says - TMDb for film and television, MusicBrainz for
 *      music. Used to confirm and complete, never to identify from nothing.
 *
 * Nothing moves without {@code --apply}. Files that cannot be identified are
 * left exactly where they are and listed, because an unsorted file is a small
 * annoyance and a file sorted into the wrong series is one you have to hunt
 * for later.
 */
final class Tidy {

    /** The kinds, in the order an inbox run works through them. */
    private static final List<String> TYPES = List.of("comics", "music", "shows", "movies");

    private static final Set<String> COMIC_EXT = Set.of("cbz", "cbr", "cb7", "pdf", "epub");
    private static final Set<String> MUSIC_EXT =
            Set.of("mp3", "m4a", "flac", "opus", "ogg", "wav", "aac", "wma");
    private static final Set<String> VIDEO_EXT =
            Set.of("mkv", "mp4", "avi", "m4v", "mov", "ts", "wmv", "mpg", "mpeg");

    /** Moved along with the video they belong to. */
    private static final Set<String> SUBTITLE_EXT = Set.of("srt", "ass", "ssa", "sub", "idx", "vtt");

    /**
     * @param from where the file is now
     * @param to   where it belongs
     * @param how  which of the three sources identified it, for the report
     */
    private record Plan(Path from, Path to, String how) {
    }

    /**
     * A comic before its folder is known.
     *
     * Comics need two passes. The year on an issue is the year that issue came
     * out, so using it for the folder would scatter one run across "Saga
     * (2012)" and "Saga (2013)". The folder takes the earliest year seen for
     * the series instead, and that is only known once every file has been read.
     */
    private record ComicId(Path file, String series, String number, Integer year, String how) {
    }

    private Tidy() {
    }

    static int run(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: goblin tidy <folder> --type comics|music|shows|movies [options]");
            return 2;
        }

        Path folder = Path.of(args[1]);
        Path out = null;
        String type = null;
        boolean apply = false;
        boolean useDatabase = true;
        boolean upload = false;
        boolean keepLocal = false;
        Path logFile = null;

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--type", "-t" -> type = args[++i].toLowerCase(Locale.ROOT);
                case "-o", "--out" -> out = Path.of(args[++i]);
                case "--apply" -> apply = true;
                case "--no-database" -> useDatabase = false;
                case "--upload" -> upload = true;
                case "--keep-local" -> keepLocal = true;
                case "--log" -> logFile = Path.of(args[++i]);
                default -> {
                    System.err.println("Unknown option: " + args[i]);
                    return 2;
                }
            }
        }

        if (type != null && !TYPES.contains(type)) {
            System.err.println("Unknown type: " + type);
            return 2;
        }

        Sftp sftp = upload ? Sftp.fromConfig(Goblin.CONFIG) : null;
        if (upload && sftp == null) {
            System.err.println("--upload was given, but " + Goblin.CONFIG
                    + " is missing or incomplete.");
            return 2;
        }
        if (sftp != null) {
            System.out.println("Uploading to " + sftp.describe());
            System.out.println();
        }

        // Without --type the folder is an inbox: one subfolder per kind, each
        // going to its own place in the library.
        if (type == null) {
            return inbox(folder, apply, useDatabase, logFile, sftp, keepLocal);
        }

        if (!Files.isDirectory(folder)) {
            System.err.println("Not a directory: " + folder);
            return 2;
        }

        Path target = (out != null) ? out : Path.of(destination(type));
        return one(folder, target, type, apply, useDatabase, logFile, sftp, keepLocal);
    }

    /**
     * Where each kind belongs below the working directory, and with --upload
     * below sftp.base as well - the remote path mirrors the local one.
     *
     * Overridable per kind in goblin.properties, because not every library
     * spells these the same. The defaults match a Jellyfin tree of
     * books/movies/music/shows.
     */
    private static String destination(String type) {
        String configured = Limits.property("tidy." + type);
        if (configured != null) {
            return configured;
        }
        return switch (type) {
            case "comics" -> "books/Comics";
            case "music" -> "music";
            case "shows" -> "shows";
            default -> "movies";
        };
    }

    /**
     * Tidies an inbox: every subfolder named after a kind is sorted into that
     * kind's place. Drop a heap of comics into input/comics and run one
     * command.
     */
    private static int inbox(Path folder, boolean apply, boolean useDatabase, Path logFile,
                             Sftp sftp, boolean keepLocal) throws Exception {

        if (!Files.isDirectory(folder)) {
            for (String type : TYPES) {
                Files.createDirectories(folder.resolve(type));
            }
            System.out.println("Created " + folder + " with a folder for each kind:");
            for (String type : TYPES) {
                System.out.printf("  %-8s -> %s%n", type, destination(type));
            }
            System.out.println();
            System.out.println("Put your unsorted files in whichever fits and run this again.");
            return 0;
        }

        int worst = 0;
        boolean any = false;

        for (String type : TYPES) {
            Path sub = folder.resolve(type);
            if (!Files.isDirectory(sub)) {
                continue;
            }
            any = true;
            System.out.println("================ " + type + " ================");
            worst = Math.max(worst, one(sub, Path.of(destination(type)), type,
                    apply, useDatabase, logFile, sftp, keepLocal));
            System.out.println();
        }

        if (!any) {
            System.out.println(folder + " has no subfolder named comics, music, shows or movies.");
            System.out.println("Create one of those and put the files in it, or name the kind "
                    + "with --type.");
        }
        return worst;
    }

    private static int one(Path folder, Path target, String type, boolean apply,
                           boolean useDatabase, Path logFile, Sftp sftp, boolean keepLocal)
            throws Exception {
        Set<String> wanted = switch (type) {
            case "comics" -> COMIC_EXT;
            case "music" -> MUSIC_EXT;
            default -> VIDEO_EXT;
        };

        List<Path> files = collect(folder, wanted);
        if (files.isEmpty()) {
            System.out.println("No " + type + " files found under " + folder + ".");
            return 0;
        }

        System.out.printf("%s: %d files under %s%n", type, files.size(), folder);
        System.out.println("Target: " + target);
        if ("music".equals(type)) {
            System.out.println("Reading tags with ffprobe, one file at a time - this takes a moment.");
        }
        System.out.println();

        Context context = new Context(type, folder, target, useDatabase);

        List<Plan> plans = new ArrayList<>();
        List<String> unidentified = new ArrayList<>();
        List<String> inPlace = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        Map<Path, Path> claimed = new LinkedHashMap<>();

        Map<String, Integer> comicYears = "comics".equals(type)
                ? seriesYears(files)
                : Map.of();

        for (Path file : files) {
            Plan plan = "comics".equals(type)
                    ? comicPlan(target, comicId(file), comicYears)
                    : plan(context, file);
            if (plan == null) {
                unidentified.add(folder.relativize(file).toString());
                continue;
            }
            if (plan.from().toAbsolutePath().normalize()
                    .equals(plan.to().toAbsolutePath().normalize())) {
                inPlace.add(folder.relativize(file).toString());
                continue;
            }

            Path key = plan.to().toAbsolutePath().normalize();
            if (claimed.containsKey(key)) {
                conflicts.add(folder.relativize(file) + "  ->  "
                        + target.relativize(plan.to()) + "  (already claimed by "
                        + folder.relativize(claimed.get(key)) + ")");
                continue;
            }
            claimed.put(key, file);
            plans.add(plan);
        }

        report(plans, inPlace, unidentified, conflicts, folder, target);

        if (plans.isEmpty()) {
            return 0;
        }
        if (!apply) {
            System.out.println();
            System.out.println("Dry run. Nothing was moved - add --apply to carry this out.");
            return 0;
        }

        return apply(plans, folder, target,
                (logFile != null) ? logFile : Path.of("goblin-tidy.log"),
                sftp, keepLocal);
    }

    // ------------------------------------------------------------------
    // Identification
    // ------------------------------------------------------------------

    /** Holds what one run needs to keep between files, above all the lookups. */
    private static final class Context {
        final String type;
        final Path root;
        final Path target;
        final Tmdb tmdb;
        final Map<String, Tmdb.Series> seen = new HashMap<>();

        Context(String type, Path root, Path target, boolean useDatabase) {
            this.type = type;
            this.root = root.toAbsolutePath().normalize();
            this.target = target;
            this.tmdb = (useDatabase && !"comics".equals(type) && !"music".equals(type))
                    ? Tmdb.from(Goblin.CONFIG)
                    : null;
            if (useDatabase && tmdb == null && !"comics".equals(type) && !"music".equals(type)) {
                System.out.println("No TMDb key, so no IDs and no years from the database. "
                        + "Sorting carries on with what the file names say.");
            }
        }

        /**
         * One lookup per distinct name, not per file. A season of twenty
         * episodes is one request, and a miss is remembered as a miss.
         */
        Tmdb.Series lookup(String name, boolean movie) {
            if (tmdb == null) {
                return null;
            }
            String key = name.toLowerCase(Locale.ROOT);
            if (seen.containsKey(key)) {
                return seen.get(key);
            }
            Tmdb.Series found = null;
            try {
                found = movie ? tmdb.searchMovie(name) : tmdb.search(name);
            } catch (IOException | InterruptedException | RuntimeException e) {
                System.out.println("  TMDb unreachable for \"" + name + "\": " + e.getMessage());
            }
            seen.put(key, found);
            return found;
        }
    }

    private static Plan plan(Context context, Path file) {
        return switch (context.type) {
            case "music" -> music(context, file);
            case "shows" -> show(context, file);
            default -> movie(context, file);
        };
    }

    /** Everything a comic says about itself, before the folder is decided. */
    private static ComicId comicId(Path file) {
        Cbz.Info info = "cbz".equals(extension(file)) ? Cbz.read(file) : null;
        if (info != null) {
            return new ComicId(file, info.series(), info.number(), info.year(), "ComicInfo.xml");
        }

        Guess.Comic guess = Guess.comic(file.getFileName().toString());
        return (guess == null) ? null
                : new ComicId(file, guess.series(), guess.number(), guess.year(), "file name");
    }

    /** The earliest year seen per series, which is the one the folder carries. */
    private static Map<String, Integer> seriesYears(List<Path> files) {
        Map<String, Integer> years = new HashMap<>();
        for (Path file : files) {
            ComicId id = comicId(file);
            if (id == null || id.year() == null) {
                continue;
            }
            years.merge(id.series().toLowerCase(Locale.ROOT), id.year(), Math::min);
        }
        return years;
    }

    private static Plan comicPlan(Path target, ComicId id, Map<String, Integer> seriesYears) {
        if (id == null) {
            return null;
        }

        String series = Naming.sanitize(id.series());
        Integer folderYear = seriesYears.get(id.series().toLowerCase(Locale.ROOT));
        String folder = series + (folderYear != null ? " (" + folderYear + ")" : "");

        StringBuilder name = new StringBuilder(series);
        if (id.number() != null) {
            name.append(" #").append(issue(id.number()));
        }
        // The issue's own year, not the series', so two printings stay apart.
        if (id.year() != null) {
            name.append(" (").append(id.year()).append(')');
        }
        name.append('.').append(extension(id.file()));

        return new Plan(id.file(), target.resolve(folder).resolve(name.toString()), id.how());
    }

    private static Plan music(Context context, Path file) {
        Tags.Audio tags = Tags.read(file);
        String how = "tags";

        if (tags == null || !tags.usable()) {
            // A dumped archive often keeps "Artist - Album" as the folder even
            // when the files themselves were never tagged.
            Guess.Track guess = Guess.track(file.getFileName().toString());
            String[] fromFolders = artistAndAlbum(file, context.root);
            if (guess == null || fromFolders == null) {
                return null;
            }
            tags = new Tags.Audio(fromFolders[0], fromFolders[0], fromFolders[1],
                    guess.title(), guess.number());
            how = "file name and folders";
        }

        String fileName = (tags.track() != null)
                ? String.format("%02d - %s.%s", tags.track(), Naming.sanitize(tags.title()), extension(file))
                : Naming.sanitize(tags.title()) + "." + extension(file);

        return new Plan(file, context.target
                .resolve(Naming.sanitize(tags.folderArtist()))
                .resolve(Naming.sanitize(tags.album()))
                .resolve(fileName), how);
    }

    private static Plan show(Context context, Path file) {
        Guess.Episode episode = Guess.episode(file.getFileName().toString());
        String how = "file name";

        if (episode == null) {
            // "Series Name/S01/02 - Title.mkv" carries the series in the path.
            Path parent = file.getParent();
            if (parent == null) {
                return null;
            }
            episode = Guess.episode(parent.getFileName() + " " + file.getFileName());
            how = "file name and folder";
        }
        if (episode == null) {
            return null;
        }

        Tmdb.Series series = context.lookup(episode.series(), false);
        String name = (series != null) ? series.name() : episode.series();
        Integer year = (series != null) ? series.year() : null;
        Integer id = (series != null) ? series.id() : null;
        if (series != null) {
            how += " + TMDb";
        }

        return new Plan(file, context.target
                .resolve(Naming.seriesFolder(name, year, id))
                .resolve(Naming.seasonFolder(episode.season()))
                .resolve(Naming.episodeFile(name, episode.season(), episode.episode(),
                        episode.title(), extension(file))), how);
    }

    private static Plan movie(Context context, Path file) {
        Guess.Movie guess = Guess.movie(file.getFileName().toString());
        String how = "file name";

        // "Some Movie (2011)/movie.mkv" - the folder is the title, the file is not.
        Path parent = file.getParent();
        if (guess != null && parent != null && guess.year() == null
                && guess.title().length() <= 5) {
            Guess.Movie fromFolder = Guess.movie(parent.getFileName().toString());
            if (fromFolder != null) {
                guess = fromFolder;
                how = "folder name";
            }
        }
        if (guess == null) {
            return null;
        }

        Tmdb.Series found = context.lookup(guess.title(), true);
        String title = (found != null) ? found.name() : guess.title();
        Integer year = (found != null && found.year() != null) ? found.year() : guess.year();
        Integer id = (found != null) ? found.id() : null;
        if (found != null) {
            how += " + TMDb";
        }

        return new Plan(file, context.target
                .resolve(Naming.movieFolder(title, year, id))
                .resolve(Naming.movieFile(title, year, extension(file))), how);
    }

    // ------------------------------------------------------------------
    // Reporting and moving
    // ------------------------------------------------------------------

    private static void report(List<Plan> plans, List<String> inPlace, List<String> unidentified,
                               List<String> conflicts, Path folder, Path target) {

        if (!plans.isEmpty()) {
            System.out.println(plans.size() + " to move:");
            for (Plan plan : plans) {
                System.out.printf("  %s%n      -> %s   [%s]%n",
                        folder.relativize(plan.from()),
                        target.relativize(plan.to()),
                        plan.how());
            }
        }

        if (!inPlace.isEmpty()) {
            System.out.println();
            System.out.println(inPlace.size() + " already in the right place.");
        }

        if (!conflicts.isEmpty()) {
            System.out.println();
            System.out.println(conflicts.size() + " would land on the same name, skipped:");
            for (String conflict : conflicts) {
                System.out.println("  " + conflict);
            }
        }

        if (!unidentified.isEmpty()) {
            System.out.println();
            System.out.println(unidentified.size() + " not identified, left where they are:");
            for (String name : unidentified) {
                System.out.println("  " + name);
            }
        }
    }

    private static int apply(List<Plan> plans, Path folder, Path target, Path logFile,
                             Sftp sftp, boolean keepLocal) {
        System.out.println();
        int moved = 0;
        int failed = 0;
        int uploaded = 0;
        int uploadFails = 0;

        for (Plan plan : plans) {
            try {
                Files.createDirectories(plan.to().getParent());
                if (Files.exists(plan.to())) {
                    // Between the plan and here, or a file the scan never saw.
                    System.out.println("  exists, skipped: " + plan.to());
                    continue;
                }
                Files.move(plan.from(), plan.to());
                log(logFile, plan.from(), plan.to());
                moved++;

                List<Path> also = subtitles(plan, logFile);
                moved += also.size();

                if (sftp != null) {
                    boolean ok = Goblin.uploadIfConfigured(sftp, target, plan.to(), keepLocal);
                    for (Path subtitle : also) {
                        ok &= Goblin.uploadIfConfigured(sftp, target, subtitle, keepLocal);
                    }

                    uploaded += ok ? 1 + also.size() : 0;
                    uploadFails = ok ? 0 : uploadFails + 1;

                    // The same guard the downloads have: a failed upload keeps
                    // its file, so carrying on would quietly fill the disk.
                    if (Limits.uploadFailures() > 0 && uploadFails >= Limits.uploadFailures()) {
                        System.out.printf("%nStopping: %d uploads in a row failed. "
                                + "The rest stays sorted but local.%n", uploadFails);
                        break;
                    }
                }
            } catch (IOException e) {
                System.out.println("  failed: " + folder.relativize(plan.from())
                        + " (" + e.getMessage() + ")");
                failed++;
            }
        }

        System.out.printf("%nMoved %d files.%s%n", moved,
                failed > 0 ? " " + failed + " failed." : "");
        if (sftp != null) {
            System.out.printf("Uploaded %d.%s%n", uploaded,
                    keepLocal ? " The local copies stay." : " The local copies are gone.");
        }

        int removed = removeEmpty(folder);
        if (sftp != null && !keepLocal && !target.equals(folder)) {
            removed += removeEmpty(target);
        }
        if (removed > 0) {
            System.out.println("Removed " + removed + " empty directories.");
        }

        System.out.println("Every move is in " + logFile + ", oldest first, as "
                + "\"from<tab>to\" - that is your way back.");
        return failed > 0 ? 1 : 0;
    }

    /**
     * Subtitles named after the video follow it, under the video's new name.
     *
     * @return where they landed, so an upload can take them along
     */
    private static List<Path> subtitles(Plan plan, Path logFile) {
        List<Path> moved = new ArrayList<>();

        Path parent = plan.from().getParent();
        if (parent == null) {
            return moved;
        }

        String stem = Guess.stripExtension(plan.from().getFileName().toString());
        String newStem = Guess.stripExtension(plan.to().getFileName().toString());

        try (var entries = Files.list(parent)) {
            for (Path sibling : entries.toList()) {
                String name = sibling.getFileName().toString();
                if (!Files.isRegularFile(sibling) || !SUBTITLE_EXT.contains(extension(sibling))) {
                    continue;
                }

                String suffix = subtitleSuffix(stem, Guess.stripExtension(name));
                if (suffix == null) {
                    continue;
                }
                Path to = plan.to().resolveSibling(newStem + suffix + "." + extension(sibling));
                if (Files.exists(to)) {
                    continue;
                }
                Files.move(sibling, to);
                log(logFile, sibling, to);
                moved.add(to);
            }
        } catch (IOException e) {
            // Losing a subtitle is not worth failing the video's move over.
            return moved;
        }
        return moved;
    }

    /**
     * Whether a subtitle belongs to a video, and which part of its name to keep.
     *
     * Three shapes, because a subtitle is rarely named exactly like its video:
     * the same stem, the stem plus a marker, or - most often - the stem with
     * the release tags dropped and only a language marker left, as in
     * "Show.S01E01.Title.en.srt" beside "Show.S01E01.Title.1080p.x264.mkv".
     *
     * The third shape is the loose one, so it demands that what remains after
     * the markers still covers half the video's name. A subtitle called just
     * "Show.srt" must not claim every episode of it.
     *
     * @return the marker to carry over, "" for none, or null when unrelated
     */
    static String subtitleSuffix(String videoStem, String subtitleStem) {
        if (subtitleStem.equals(videoStem)) {
            return "";
        }
        if (subtitleStem.startsWith(videoStem)) {
            return subtitleStem.substring(videoStem.length());
        }

        // At most two, which covers ".en" and ".en.forced".
        String base = subtitleStem;
        String markers = "";
        for (int i = 0; i < 2; i++) {
            int dot = base.lastIndexOf('.');
            if (dot <= 0) {
                return null;
            }
            String marker = base.substring(dot);
            if (marker.length() > 8 || !marker.substring(1).matches("[A-Za-z]+")) {
                return null;
            }
            base = base.substring(0, dot);
            markers = marker + markers;

            if (videoStem.startsWith(base) && base.length() * 2 >= videoStem.length()) {
                return markers;
            }
        }
        return null;
    }

    private static void log(Path logFile, Path from, Path to) {
        try {
            Files.createDirectories(logFile.toAbsolutePath().getParent());
            Files.writeString(logFile,
                    from.toAbsolutePath() + "\t" + to.toAbsolutePath() + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("  could not write the log: " + e.getMessage());
        }
    }

    /** Deletes the directories the move emptied. Never the folder itself. */
    private static int removeEmpty(Path root) {
        List<Path> directories;
        try (var walk = Files.walk(root)) {
            directories = walk.filter(Files::isDirectory)
                    .filter(p -> !p.equals(root))
                    // Deepest first, so a directory that only held directories
                    // is empty by the time it is reached.
                    .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                    .toList();
        } catch (IOException e) {
            return 0;
        }

        int removed = 0;
        for (Path directory : directories) {
            try (var entries = Files.list(directory)) {
                if (entries.findAny().isEmpty()) {
                    Files.delete(directory);
                    removed++;
                }
            } catch (IOException e) {
                // In use, no permission - either way, leave it.
            }
        }
        return removed;
    }

    // ------------------------------------------------------------------

    private static List<Path> collect(Path folder, Set<String> extensions) {
        try (var walk = Files.walk(folder)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> extensions.contains(extension(p)))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            System.err.println("Could not read " + folder + ": " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Artist and album from the directories above the file, as an unpacked
     * archive usually leaves them.
     *
     * Only directories strictly below the scanned folder count. The folder
     * being tidied is called something like "music" or "dump", and reading
     * those as an artist would file a stray track under a band that does not
     * exist - worse than leaving it where it is.
     *
     * @return {artist, album}, or null when the path says nothing usable
     */
    private static String[] artistAndAlbum(Path file, Path root) {
        Path album = file.toAbsolutePath().normalize().getParent();
        if (album == null || !below(album, root)) {
            return null;
        }

        // "Artist - Album" in one folder is the other common shape, and it
        // needs only the one level.
        String albumName = album.getFileName().toString();
        int dash = albumName.indexOf(" - ");
        if (dash > 0) {
            return new String[] {albumName.substring(0, dash).strip(),
                                 albumName.substring(dash + 3).strip()};
        }

        Path artist = album.getParent();
        if (artist == null || !below(artist, root)) {
            return null;
        }
        return new String[] {artist.getFileName().toString(), albumName};
    }

    private static boolean below(Path directory, Path root) {
        return directory.startsWith(root) && !directory.equals(root);
    }

    /** Pads a plain issue number to three digits so the folder sorts. */
    private static String issue(String number) {
        return number.matches("\\d{1,3}")
                ? String.format("%03d", Integer.parseInt(number))
                : number;
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return (dot < 0) ? "" : name.substring(dot + 1);
    }
}
