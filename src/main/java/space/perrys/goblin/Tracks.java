package space.perrys.goblin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Makes every audio and subtitle track in the library read the same way.
 *
 * Jellyfin does not store the line it shows in the track picker, it assembles
 * it every time from the stream itself, in this order:
 *
 *   [title] - language - profile|codec - channel layout - default - external
 *
 * An attribute is left out when the title already contains it, compared as a
 * substring and case-insensitively. "Profile or codec" is meant literally: a
 * profile that is not {@code lc} replaces the codec name, so AAC-LC prints as
 * "AAC" and HE-AAC prints as "HE-AAC" and nothing else. "Default" is not
 * metadata at all, it is the disposition flag, translated.
 *
 * Read from {@code MediaStream.DisplayTitle} and
 * {@code ProbeResultNormalizer.GetMediaStream} in jellyfin/jellyfin.
 *
 * That leaves exactly two things worth correcting here, and one to leave
 * alone:
 *
 *   1. The title. Jellyfin looks for it in the {@code title} tag, then
 *      {@code name}, then falls back to the container's handler name unless
 *      that is the default {@code SoundHandler}. A handler name is a technical
 *      field nobody fills in on purpose, which is why files off YouTube
 *      announce themselves as "ISO Media file produced by Google Inc." in the
 *      track list. Resetting it to the default makes Jellyfin ignore it and
 *      print the derived attributes instead - the same ones it prints for
 *      every other file.
 *   2. The language. Without it the first field is missing entirely, and a
 *      library where some tracks say "English - AAC - Stereo" and others say
 *      "AAC - Stereo" is exactly the unevenness this command exists to remove.
 *   3. Titles that say something Jellyfin cannot derive - "Commentary",
 *      "Audio Description", the name of a cut - are kept. Only titles whose
 *      every word is already in the derived attributes are dropped, because
 *      those turn the line into a repetition of itself.
 *
 * Files that need none of this are never opened for writing. The correction
 * is a remux: {@code -c copy}, nothing re-encoded, but a full second copy of
 * the file is written next to it and moved over, so a library of 16 GiB films
 * needs 16 GiB free while each one is rewritten.
 */
final class Tracks {

    /** What ffmpeg writes when nobody set a handler name - and what Jellyfin ignores. */
    private static final Map<String, String> DEFAULT_HANDLER =
            Map.of("audio", "SoundHandler", "subtitle", "SubtitleHandler");

    /**
     * What a track is taken to be in when nothing says otherwise.
     *
     * Nearly everything that arrives here comes off an English-language
     * channel, and a library where most tracks read "English - AAC - Stereo"
     * and a few read "AAC - Stereo" is the unevenness this exists to remove.
     * A run that fetches something else says so with --lang.
     */
    private static final String DEFAULT_LANGUAGE = "eng";

    /** Language codes that mean "not stated". Jellyfin drops these too. */
    private static final Set<String> UNSET_LANGUAGE = Set.of("", "und", "undefined", "unknown");

    /**
     * Words in a title that carry nothing on their own. "Surround 5.1" is a
     * repetition because "surround" is filler and "5.1" is the channel layout;
     * "Director's Commentary" is not, because "commentary" is neither.
     */
    private static final Set<String> FILLER = Set.of(
            "audio", "track", "tracks", "sound", "surround", "channel", "channels", "ch", "stream");

    /**
     * One correction to one stream: a word for the summary at the end of a
     * download, a sentence for the listing, and the ffmpeg arguments.
     */
    private record Change(String kind, String line, List<String> args) {
    }

    /** Everything one file needs. Empty {@code changes} means: do not touch it. */
    private record Plan(Path file, List<Change> changes, List<String> notes) {
    }

    private Tracks() {
    }

    static int run(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: goblin tracks <path> [--apply] [--lang <code>]");
            return 2;
        }

        Path where = Path.of(args[1]);
        boolean apply = false;
        String language = null;

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--apply" -> apply = true;
                case "--lang" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("--lang needs a code, for example --lang eng.");
                        return 2;
                    }
                    language = args[++i].toLowerCase(Locale.ROOT);
                }
                default -> {
                    System.err.println("Unknown option: " + args[i]);
                    return 2;
                }
            }
        }

        if (language == null) {
            language = configuredLanguage();
        }

        if (!Files.exists(where)) {
            System.err.println("No such file or directory: " + where);
            return 2;
        }
        if (!Proc.exists("ffprobe") || !Proc.exists("ffmpeg")) {
            System.err.println("ffmpeg and ffprobe are needed to read and rewrite track metadata.");
            return 1;
        }

        List<Path> files = Files.isRegularFile(where)
                ? List.of(where)
                : Tidy.collect(where, Tidy.VIDEO_EXT);
        if (files.isEmpty()) {
            System.out.println("No video files found under " + where + ".");
            return 0;
        }

        if (files.size() == 1 && Files.isRegularFile(where)) {
            System.out.println("Reading " + where + ".");
        } else {
            System.out.printf("%d video %s under %s.%n", files.size(),
                    files.size() == 1 ? "file" : "files", where);
        }
        if (!apply) {
            System.out.println("Dry run: nothing is written.");
        }
        System.out.println();

        List<Plan> plans = new ArrayList<>();
        int consistent = 0;
        int unreadable = 0;

        for (Path file : files) {
            Plan plan = plan(file, language);
            if (plan == null) {
                unreadable++;
                continue;
            }
            if (plan.changes().isEmpty() && plan.notes().isEmpty()) {
                consistent++;
                continue;
            }
            plans.add(plan);
            System.out.println("  " + file.getFileName());
            for (Change change : plan.changes()) {
                System.out.println("    " + change.line());
            }
            for (String note : plan.notes()) {
                System.out.println("    " + note);
            }
        }

        if (!plans.isEmpty()) {
            System.out.println();
        }
        if (consistent > 0) {
            System.out.printf("%d already consistent.%n", consistent);
        }
        if (unreadable > 0) {
            System.out.println(unreadable + " could not be read and were left alone.");
        }

        long toWrite = plans.stream().filter(p -> !p.changes().isEmpty()).count();
        if (!apply) {
            if (toWrite > 0) {
                System.out.println();
                System.out.println("Nothing was touched. Run again with --apply.");
            }
            return 0;
        }
        if (toWrite == 0) {
            return 0;
        }

        System.out.println();
        int rewritten = 0;
        int failed = 0;
        for (Plan plan : plans) {
            if (plan.changes().isEmpty()) {
                continue;
            }
            System.out.println("  rewriting " + plan.file().getFileName());
            try {
                rewrite(plan);
                rewritten++;
            } catch (IOException e) {
                System.out.println("    failed: " + e.getMessage());
                failed++;
            }
        }

        System.out.println();
        System.out.printf("%d rewritten.%n", rewritten);
        if (failed > 0) {
            System.out.println(failed + " failed and were left as they were.");
        }
        return (failed > 0) ? 1 : 0;
    }

    /**
     * Gives one finished file the same track names as the rest of the library.
     *
     * Called at the end of every download, because that is where the uneven
     * ones come from: a file off YouTube carries Google's handler name as its
     * track title and states no language at all.
     *
     * The language cannot be read off the video. It comes from --lang when a
     * run fetches something other than the usual, from
     * {@code track.language} in goblin.properties when a library has a
     * different usual, and otherwise from {@link #DEFAULT_LANGUAGE}.
     *
     * Silent when there is nothing to do, and never fatal. A file that cannot
     * be read or rewritten is kept as it is - losing a finished download over
     * a cosmetic detail would be the worse trade.
     */
    static void normalise(Path file, String language) {
        Plan plan = plan(file, language);
        if (plan == null || plan.changes().isEmpty()) {
            return;
        }

        List<String> kinds = new ArrayList<>();
        for (Change change : plan.changes()) {
            if (!kinds.contains(change.kind())) {
                kinds.add(change.kind());
            }
        }

        try {
            rewrite(plan);
            System.out.println("    track names: " + String.join(", ", kinds));
        } catch (IOException | InterruptedException e) {
            System.out.println("    track names left as they are: " + e.getMessage());
        }
    }

    /**
     * The language to write when the command line does not name one:
     * {@code track.language} from goblin.properties, else English. An empty
     * setting is a deliberate "leave the language alone", so it is honoured
     * as null rather than replaced by the default.
     */
    static String configuredLanguage() {
        String configured = Limits.property("track.language");
        if (configured == null) {
            return Limits.hasKey("track.language") ? null : DEFAULT_LANGUAGE;
        }
        return configured.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------

    /** @return what the file needs, or null when ffprobe cannot read it */
    private static Plan plan(Path file, String language) {
        List<Object> streams;
        try {
            String json = Proc.capture(List.of(
                    "ffprobe", "-v", "error",
                    "-print_format", "json",
                    "-show_streams",
                    // Your film is in a folder called "Obi-Wan Kenobi: The
                    // Patterson Cut". Without the prefix ffmpeg reads
                    // everything before that colon as a protocol name.
                    "file:" + file));
            streams = Json.array(Json.object(Json.parse(json)).get("streams"));
        } catch (IOException | InterruptedException e) {
            return null;
        }

        List<Change> changes = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<Integer> audio = new ArrayList<>();
        List<Integer> audioDefaults = new ArrayList<>();

        for (Object entry : streams) {
            Map<String, Object> stream = Json.object(entry);
            String type = String.valueOf(Json.str(stream, "codec_type"));
            if (!DEFAULT_HANDLER.containsKey(type)) {
                continue;
            }

            int index = (int) Json.num(stream, "index", -1);
            if (index < 0) {
                continue;
            }
            Map<String, Object> tags = Json.object(stream.get("tags"));
            String label = String.format("%-8s %2d", type, index);

            if ("audio".equals(type)) {
                audio.add(index);
                if (Json.num(Json.object(stream.get("disposition")), "default", 0) == 1) {
                    audioDefaults.add(index);
                }
            }

            // 1. The title, in the order Jellyfin resolves it.
            String title = tag(tags, "title");
            String name = tag(tags, "name");
            String handler = tag(tags, "handler_name");
            String fromTags = blank(title) ? name : title;

            if (!blank(fromTags)) {
                String repeated = repetition(fromTags, stream);
                if (repeated != null) {
                    changes.add(clearTitle(label, index, tags,
                            "\"" + fromTags + "\" repeats " + repeated));
                }
            } else if (!blank(handler)
                    && !handler.equalsIgnoreCase(DEFAULT_HANDLER.get(type))) {
                changes.add(clearTitle(label, index, tags,
                        "\"" + handler + "\" is the container's handler name"));
            }

            // 2. The language.
            String lang = tag(tags, "language");
            if (UNSET_LANGUAGE.contains(lang == null ? "" : lang.toLowerCase(Locale.ROOT))) {
                if (language == null) {
                    notes.add(label + "  no language (say --lang <code> to set one)");
                } else {
                    changes.add(new Change("language " + language,
                            label + "  language -> " + language,
                            List.of("-metadata:s:" + index, "language=" + language)));
                }
            }
        }

        // 3. Exactly one default audio track. Only the broken cases are
        //    corrected - which of two sensible candidates is the default is a
        //    decision for whoever built the file, not for this.
        if (!audio.isEmpty() && audioDefaults.size() != 1) {
            List<String> args = new ArrayList<>();
            for (int index : audio) {
                args.add("-disposition:" + index);
                args.add(index == audio.get(0) ? "default" : "0");
            }
            changes.add(new Change("default track",
                    String.format("%-8s %2d", "audio", audio.get(0))
                            + "  " + audioDefaults.size() + " tracks marked default -> only this one",
                    args));
        }

        return new Plan(file, changes, notes);
    }

    /**
     * Clears every field Jellyfin would read a title from. An empty value is
     * how ffmpeg is told to delete a tag, so the handler name is not blanked
     * but removed - Matroska then carries none at all and MP4 gets back the
     * default {@code SoundHandler} its muxer writes on its own, which is the
     * one value Jellyfin knows to ignore.
     */
    private static Change clearTitle(String label, int index,
                                     Map<String, Object> tags, String why) {
        List<String> args = new ArrayList<>();
        for (String key : List.of("title", "name", "handler_name")) {
            for (String spelling : spellings(tags, key)) {
                args.add("-metadata:s:" + index);
                args.add(spelling + "=");
            }
        }
        return new Change("title", label + "  title -> none, " + why, args);
    }

    /**
     * Whether the title only repeats what Jellyfin prints anyway.
     *
     * @return the words it repeats, or null when it says something of its own
     */
    private static String repetition(String title, Map<String, Object> stream) {
        Set<String> derived = new LinkedHashSet<>(FILLER);
        derived.addAll(words(Json.str(stream, "channel_layout")));
        derived.addAll(words(Json.str(stream, "codec_name")));
        derived.addAll(words(Json.str(stream, "profile")));
        derived.addAll(words(tag(Json.object(stream.get("tags")), "language")));
        int channels = (int) Json.num(stream, "channels", 0);
        if (channels > 0) {
            // "2.0" and "5.1" as they are written in a title, next to the
            // layout ffprobe reports.
            derived.add(channels + ".0");
            derived.add((channels - 1) + ".1");
        }

        List<String> spoken = words(title);
        if (spoken.isEmpty()) {
            return null;
        }
        List<String> repeated = new ArrayList<>();
        for (String word : spoken) {
            if (!derived.contains(word)) {
                return null;
            }
            if (!FILLER.contains(word)) {
                repeated.add(word);
            }
        }
        return repeated.isEmpty() ? "nothing but filler" : String.join(" and ", repeated);
    }

    /**
     * Lowercase words, keeping the dot that holds "5.1" together. ffprobe
     * writes layouts like "5.1(side)", which this splits into "5.1" and
     * "side".
     */
    private static List<String> words(String text) {
        if (blank(text)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9.]+")) {
            String trimmed = word.replaceAll("^\\.+|\\.+$", "");
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /** Null on a file system that does not have owners and modes. */
    private static java.nio.file.attribute.PosixFileAttributes posix(Path file) {
        try {
            return Files.readAttributes(file, java.nio.file.attribute.PosixFileAttributes.class);
        } catch (IOException | UnsupportedOperationException e) {
            return null;
        }
    }

    /**
     * Puts owner, group and mode of the file that was replaced back on the one
     * that replaced it. Only what actually differs is set: changing the owner
     * needs root, and a run that is not root should not report a failure for
     * something it was never going to have to change.
     */
    private static void restore(Path file, java.nio.file.attribute.PosixFileAttributes before) {
        java.nio.file.attribute.PosixFileAttributes now = posix(file);
        if (before == null || now == null) {
            return;
        }
        try {
            if (!now.permissions().equals(before.permissions())) {
                Files.setPosixFilePermissions(file, before.permissions());
            }
            var view = Files.getFileAttributeView(
                    file, java.nio.file.attribute.PosixFileAttributeView.class);
            if (!now.group().equals(before.group())) {
                view.setGroup(before.group());
            }
            if (!now.owner().equals(before.owner())) {
                view.setOwner(before.owner());
            }
        } catch (IOException e) {
            System.out.println("    owner and mode could not be carried over: " + e.getMessage());
        }
    }

    /**
     * A stream tag, found whatever its spelling.
     *
     * Matroska keeps its tags in capitals, so a file remuxed out of an MP4
     * carries HANDLER_NAME where the MP4 had handler_name. Jellyfin looks
     * these up without regard for case and finds it either way; a lookup here
     * that insists on lowercase reports the file as clean and leaves the
     * server showing "ISO Media file produced by Google Inc.".
     */
    private static String tag(Map<String, Object> tags, String key) {
        for (var entry : tags.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key) && entry.getValue() instanceof String value) {
                return value;
            }
        }
        return null;
    }

    /** The spellings of one tag actually present, so clearing it can name them. */
    private static List<String> spellings(Map<String, Object> tags, String key) {
        List<String> out = new ArrayList<>();
        for (String present : tags.keySet()) {
            if (present.equalsIgnoreCase(key)) {
                out.add(present);
            }
        }
        if (out.isEmpty()) {
            out.add(key);
        }
        return out;
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    /**
     * Writes the corrected file beside the original and moves it over, the way
     * {@link Ffmpeg#tag} does. Nothing is re-encoded; an interrupted run
     * leaves the original untouched and a half-written sibling behind.
     */
    private static void rewrite(Plan plan) throws IOException, InterruptedException {
        Path file = plan.file();
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = (dot < 0) ? "" : name.substring(dot);
        Path tmp = file.resolveSibling(name + ".tracks" + ext);

        // The modification time is deliberately NOT carried over. Jellyfin's
        // scanner decides whether to read a file again by comparing it
        // (ProbeProvider.HasChanged, item.HasChanged(file.LastWriteTimeUtc)),
        // so a correction that keeps the old time is a correction the server
        // never sees: the track picker goes on showing what it cached at
        // import. Keeping it looked tidy and quietly broke the only thing
        // this command is for.
        //
        // Owner, group and mode are another matter. The correction is usually run as
        // root on the media host while the files belong to the container's
        // user; a rewrite that quietly hands them to root leaves a library
        // the server can still read and no longer write.
        java.nio.file.attribute.PosixFileAttributes before = posix(file);

        List<String> cmd = new ArrayList<>(List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-i", "file:" + file,
                "-map", "0", "-c", "copy"));
        for (Change change : plan.changes()) {
            cmd.addAll(change.args());
        }
        if (ext.equalsIgnoreCase(".mp4") || ext.equalsIgnoreCase(".m4v")) {
            // The index lands at the front, so the file can still be played
            // before it has been fetched whole.
            cmd.add("-movflags");
            cmd.add("+faststart");
        }
        cmd.add("file:" + tmp);

        try {
            Proc.inherit(cmd);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            restore(file, before);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
