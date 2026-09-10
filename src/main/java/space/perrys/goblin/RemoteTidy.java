package space.perrys.goblin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sorts comics that live on another machine, over SFTP, without fetching them.
 *
 * The files usually sit on a media server while the goblin runs somewhere
 * else. Downloading a shelf, sorting it and pushing it back would move
 * gigabytes to change file names; this renames them where they are.
 *
 * Three things make that possible:
 *
 *   - {@link ZipRange} reads a ComicInfo.xml out of a remote archive with
 *     three small byte ranges instead of the whole file.
 *   - SFTP has a rename that the server carries out itself.
 *   - {@link Comics} decides the names, and knows nothing about where the
 *     files are, so the answers match what a local run would give.
 *
 * Only comics. Music needs its tags read, which means whole files, and video
 * is identified from its name alone and belongs to the local run.
 */
final class RemoteTidy {

    private static final Set<String> COMIC_EXT = Set.of("cbz", "cbr", "cb7", "pdf", "epub");

    /** Guards against a symlink loop turning the walk into a hang. */
    private static final int MAX_DEPTH = 8;

    private record RemoteFile(String path, long size) {
    }

    private record Move(String from, String to, String how) {
    }

    private RemoteTidy() {
    }

    /**
     * @param root      the folder to sort, below sftp.base
     * @param target    where the sorted folders go, below sftp.base
     * @param withTitles whether issue titles from the database join the names
     */
    static int run(Sftp sftp, String root, String target, boolean apply,
                   boolean useDatabase, boolean withTitles)
            throws IOException, InterruptedException {

        System.out.println("Remote: " + sftp.describe());
        System.out.println("Sorting " + root + " into " + target);
        System.out.println();

        List<RemoteFile> files = walk(sftp, root);
        if (files.isEmpty()) {
            System.out.println("No comics found under " + root + ".");
            return 0;
        }
        System.out.printf("%d files under %s%n%n", files.size(), root);

        // 1. What each file says about itself.
        Map<String, Comics.Id> ids = new LinkedHashMap<>();
        List<String> unidentified = new ArrayList<>();
        int fromXml = 0;

        for (RemoteFile file : files) {
            Comics.Id id = identify(sftp, file);
            if (id == null) {
                unidentified.add(file.path());
                continue;
            }
            if ("ComicInfo.xml".equals(id.how())) {
                fromXml++;
            }
            ids.put(file.path(), id);
        }

        System.out.printf("Identified %d of %d - %d from ComicInfo.xml, %d from the file name.%n",
                ids.size(), files.size(), fromXml, ids.size() - fromXml);

        if (ids.isEmpty()) {
            System.out.println("Nothing identifiable. Nothing to do.");
            return 1;
        }

        // 2. What the database says, once per series rather than per issue.
        ComicVine comicVine = useDatabase ? ComicVine.from(Goblin.CONFIG) : null;
        if (useDatabase && comicVine == null) {
            System.out.println("No comicvine.api_key and no COMICVINE_API_KEY - "
                    + "working from the files alone.");
        }
        System.out.println();
        Map<String, Comics.Series> series = Comics.resolve(ids.values(), comicVine);

        Map<String, Map<String, String>> titles = new LinkedHashMap<>();
        if (withTitles) {
            for (Map.Entry<String, Comics.Series> e : series.entrySet()) {
                titles.put(e.getKey(), Comics.titles(comicVine, e.getValue().volume()));
            }
        }

        // 3. Where everything goes.
        List<Move> moves = new ArrayList<>();
        List<String> inPlace = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        Map<String, String> claimed = new LinkedHashMap<>();

        for (Map.Entry<String, Comics.Id> e : ids.entrySet()) {
            Comics.Id id = e.getValue();
            String key = id.series().toLowerCase(Locale.ROOT);
            Comics.Series info = series.get(key);

            String title = withTitles && id.number() != null
                    ? titles.getOrDefault(key, Map.of()).get(Comics.normalise(id.number()))
                    : null;

            String to = target + "/" + info.folder() + "/"
                    + Comics.fileName(id, info.name(), title, extension(e.getKey()));

            if (to.equals(e.getKey())) {
                inPlace.add(e.getKey());
                continue;
            }
            if (claimed.containsKey(to)) {
                conflicts.add(e.getKey() + "  ->  " + to
                        + "  (already claimed by " + claimed.get(to) + ")");
                continue;
            }
            claimed.put(to, e.getKey());
            moves.add(new Move(e.getKey(), to, id.how()));
        }

        report(moves, inPlace, unidentified, conflicts);

        if (!apply) {
            System.out.println();
            System.out.println("Dry run. Nothing was renamed - add --apply to carry this out.");
            return 0;
        }
        if (moves.isEmpty() && series.isEmpty()) {
            return 0;
        }

        return carryOut(sftp, moves, series, target, comicVine, root);
    }

    /** Lists the tree below one remote folder. */
    private static List<RemoteFile> walk(Sftp sftp, String root)
            throws IOException, InterruptedException {

        List<RemoteFile> out = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        Set<String> seen = new LinkedHashSet<>();
        queue.add(root);

        while (!queue.isEmpty()) {
            String dir = queue.poll();
            if (!seen.add(dir) || depth(dir) - depth(root) > MAX_DEPTH) {
                continue;
            }

            List<Sftp.Entry> entries;
            try {
                entries = sftp.list(dir);
            } catch (IOException e) {
                System.out.println("  Cannot list " + dir + " - " + e.getMessage());
                continue;
            }

            for (Sftp.Entry entry : entries) {
                String path = dir + "/" + entry.name();
                if (entry.directory()) {
                    queue.add(path);
                } else if (COMIC_EXT.contains(extension(path))) {
                    out.add(new RemoteFile(path, entry.size()));
                }
            }
        }
        out.sort((a, b) -> a.path().compareToIgnoreCase(b.path()));
        return out;
    }

    /**
     * Reads the archive's ComicInfo.xml through byte ranges, falling back to
     * the file name.
     *
     * A server that does not do ranges, or an entry that is not there, both
     * end up on the file name - which is what a .cbr or a .pdf gets anyway.
     */
    private static Comics.Id identify(Sftp sftp, RemoteFile file) {
        byte[] xml = null;
        if ("cbz".equals(extension(file.path())) && file.size() > 0) {
            try {
                xml = ZipRange.entry(
                        (from, length) -> sftp.range(file.path(), from, length),
                        file.size(), "ComicInfo.xml");
            } catch (IOException e) {
                xml = null;
            }
        }
        return Comics.identify(name(file.path()), xml);
    }

    /** Renames everything, then puts the series artwork beside it. */
    private static int carryOut(Sftp sftp, List<Move> moves, Map<String, Comics.Series> series,
                                String target, ComicVine comicVine, String root)
            throws IOException, InterruptedException {

        int done = 0;
        int failed = 0;
        Set<String> emptied = new LinkedHashSet<>();

        Path log = Path.of("goblin-tidy-remote.log");
        List<String> lines = new ArrayList<>();

        for (Move move : moves) {
            try {
                sftp.rename(move.from(), move.to());
                lines.add(move.from() + "\t" + move.to());
                String parent = parent(move.from());
                if (parent != null && !parent.equals(root)) {
                    emptied.add(parent);
                }
                done++;
                System.out.printf("  [%d/%d] %s%n", done + failed, moves.size(), move.to());
            } catch (IOException e) {
                failed++;
                System.out.println("  failed: " + move.from() + " - " + e.getMessage());
            }
        }

        if (!lines.isEmpty()) {
            Files.write(log, String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        }

        int covers = artwork(sftp, series, target, comicVine);

        // Only after the moves, and only ones that should now be empty. An
        // rmdir on a directory that still holds something fails, which is
        // exactly the safety we want - so a failure here is not an error.
        int removed = 0;
        for (String dir : emptied) {
            try {
                sftp.rmdir(dir);
                removed++;
            } catch (IOException e) {
                // Still has something in it. Left alone on purpose.
            }
        }

        System.out.println();
        System.out.printf("Renamed %d files.%n", done);
        if (failed > 0) {
            System.out.printf("%d failed.%n", failed);
        }
        if (covers > 0) {
            System.out.printf("Wrote artwork for %d series.%n", covers);
        }
        if (removed > 0) {
            System.out.printf("Cleared %d directories that emptied out.%n", removed);
        }
        if (!lines.isEmpty()) {
            System.out.println("Every rename is in " + log
                    + ", oldest first, as \"from<tab>to\" - that is your way back.");
        }
        return failed > 0 ? 1 : 0;
    }

    /** cover.jpg and series.json into each series folder. */
    private static int artwork(Sftp sftp, Map<String, Comics.Series> series, String target,
                               ComicVine comicVine) {
        if (comicVine == null) {
            return 0;
        }

        int written = 0;
        for (String key : Comics.sortedKeys(series)) {
            Comics.Series info = series.get(key);
            if (info.volume() == null) {
                continue;
            }
            String folder = target + "/" + info.folder();
            boolean any = false;

            try {
                Path temp = Comics.tempCover();
                try {
                    Files.deleteIfExists(temp);
                    if (comicVine.saveCover(info.volume().coverUrl(), temp)) {
                        sftp.upload(temp, folder + "/cover.jpg");
                        any = true;
                    }
                } finally {
                    Files.deleteIfExists(temp);
                }

                Path json = Files.createTempFile("goblin-series", ".json");
                try {
                    Files.writeString(json, Comics.seriesJson(info), StandardCharsets.UTF_8);
                    sftp.upload(json, folder + "/series.json");
                    any = true;
                } finally {
                    Files.deleteIfExists(json);
                }
            } catch (IOException | InterruptedException e) {
                System.out.println("  Artwork for " + info.folder() + " skipped - " + e.getMessage());
            }

            if (any) {
                written++;
            }
        }
        return written;
    }

    private static void report(List<Move> moves, List<String> inPlace,
                               List<String> unidentified, List<String> conflicts) {
        System.out.println();
        if (!moves.isEmpty()) {
            System.out.printf("%d to rename:%n", moves.size());
            for (Move move : moves) {
                System.out.println("  " + move.from());
                System.out.println("      -> " + move.to() + "   [" + move.how() + "]");
            }
        }
        if (!inPlace.isEmpty()) {
            System.out.printf("%n%d already in the right place.%n", inPlace.size());
        }
        if (!conflicts.isEmpty()) {
            System.out.printf("%n%d would land on the same name and were left alone:%n",
                    conflicts.size());
            conflicts.forEach(c -> System.out.println("  " + c));
        }
        if (!unidentified.isEmpty()) {
            System.out.printf("%n%d could not be identified and were left alone:%n",
                    unidentified.size());
            unidentified.forEach(u -> System.out.println("  " + u));
        }
    }

    static String extension(String path) {
        String name = name(path);
        int dot = name.lastIndexOf('.');
        return (dot < 0) ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String name(String path) {
        int slash = path.lastIndexOf('/');
        return path.substring(slash + 1);
    }

    static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return (slash <= 0) ? null : path.substring(0, slash);
    }

    private static int depth(String path) {
        return (int) path.chars().filter(c -> c == '/').count();
    }
}
