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
 * Comics and books. Music needs its tags read, which means whole files, and
 * video is identified from its name alone and belongs to the local run.
 */
final class RemoteTidy {

    private static final Set<String> COMIC_EXT = Set.of("cbz", "cbr", "cb7");
    private static final Set<String> BOOK_EXT =
            Set.of("epub", "pdf", "mobi", "azw3", "djvu", "fb2");

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
    static int run(Sftp sftp, String root, String target, String type, boolean apply,
                   boolean useDatabase, boolean withTitles, boolean flat, Books.Group group)
            throws IOException, InterruptedException {
        return "books".equals(type)
                ? books(sftp, root, target, apply, useDatabase, flat, group)
                : comics(sftp, root, target, apply, useDatabase, withTitles);
    }

    private static int comics(Sftp sftp, String root, String target, boolean apply,
                              boolean useDatabase, boolean withTitles)
            throws IOException, InterruptedException {

        System.out.println("Remote: " + sftp.describe());
        System.out.println("Sorting " + root + " into " + target);
        System.out.println();

        List<RemoteFile> files = walk(sftp, root, COMIC_EXT);
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

    /**
     * The same for books.
     *
     * Shorter than the comics path because a book stands on its own: there is
     * no series to reconcile a shelf against, so each file is decided by
     * itself and by whatever the database says about its ISBN.
     */
    private static int books(Sftp sftp, String root, String target, boolean apply,
                             boolean useDatabase, boolean flat, Books.Group group)
            throws IOException, InterruptedException {

        System.out.println("Remote: " + sftp.describe());
        System.out.println("Sorting " + root + " into " + target);
        System.out.println();

        List<RemoteFile> files = walk(sftp, root, BOOK_EXT);
        if (files.isEmpty()) {
            System.out.println("No books found under " + root + ".");
            return 0;
        }
        System.out.printf("%d files under %s%n%n", files.size(), root);

        Map<String, Books.Id> ids = new LinkedHashMap<>();
        List<String> unidentified = new ArrayList<>();
        int fromMetadata = 0;

        for (RemoteFile file : files) {
            Epub.Info metadata = "epub".equals(extension(file.path())) && file.size() > 0
                    ? Epub.read((from, length) -> sftp.range(file.path(), from, length), file.size())
                    : null;

            Books.Id id = Books.identify(name(file.path()), metadata);
            if (id == null) {
                unidentified.add(file.path());
                continue;
            }
            if (metadata != null) {
                fromMetadata++;
            }
            ids.put(file.path(), id);
        }

        System.out.printf("Identified %d of %d - %d from EPUB metadata, %d from the file name.%n",
                ids.size(), files.size(), fromMetadata, ids.size() - fromMetadata);

        if (ids.isEmpty()) {
            System.out.println("Nothing identifiable. Nothing to do.");
            return 1;
        }

        OpenLibrary library = useDatabase ? new OpenLibrary() : null;
        if (library == null) {
            System.out.println("--no-database: working from the files alone.");
        }
        System.out.println();
        Map<String, Books.Id> byKey = Books.resolve(ids.values(), library);

        List<Move> moves = new ArrayList<>();
        List<String> inPlace = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        Map<String, String> claimed = new LinkedHashMap<>();

        for (Map.Entry<String, Books.Id> e : ids.entrySet()) {
            Books.Id id = byKey.getOrDefault(Books.key(e.getValue()), e.getValue());
            String folder = Books.folder(id, group, flat);
            String to = target + (folder.isEmpty() ? "" : "/" + folder)
                    + "/" + Books.fileName(id, flat, extension(e.getKey()));

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

        int status = rename(sftp, moves, root);
        bookCovers(sftp, ids.values(), byKey, target, library, group, flat);
        return status;
    }

    /** A cover beside each book, from Open Library, keyed by its ISBN. */
    private static void bookCovers(Sftp sftp, java.util.Collection<Books.Id> ids,
                                   Map<String, Books.Id> byKey, String target,
                                   OpenLibrary library, Books.Group group, boolean flat) {
        if (library == null) {
            return;
        }

        int written = 0;
        Set<String> done = new LinkedHashSet<>();

        for (Books.Id raw : ids) {
            String key = Books.key(raw);
            if (!done.add(key)) {
                continue;
            }
            Books.Id id = byKey.getOrDefault(key, raw);
            if (id.isbn() == null) {
                continue;
            }

            try {
                Path temp = Files.createTempFile("goblin-cover", ".jpg");
                try {
                    Files.deleteIfExists(temp);
                    if (library.saveCover(id.isbn(), temp)) {
                        String folder = Books.folder(id, group, flat);
                        sftp.upload(temp, target + (folder.isEmpty() ? "" : "/" + folder)
                                + "/" + Books.coverName(flat, id));
                        written++;
                    }
                } finally {
                    Files.deleteIfExists(temp);
                }
            } catch (IOException | InterruptedException e) {
                System.out.println("  Cover for " + id.title() + " skipped - " + e.getMessage());
            }
        }
        if (written > 0) {
            System.out.printf("Wrote %d covers.%n", written);
        }
    }

    /** Lists the tree below one remote folder. */
    private static List<RemoteFile> walk(Sftp sftp, String root, Set<String> wanted)
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
                } else if (wanted.contains(extension(path))) {
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

        int status = rename(sftp, moves, root);
        int covers = artwork(sftp, series, target, comicVine);
        if (covers > 0) {
            System.out.printf("Wrote artwork for %d series.%n", covers);
        }
        return status;
    }

    /**
     * Carries out the renames, logs them, and clears the directories they
     * emptied.
     *
     * Shared by both kinds, because a rename is a rename - only deciding the
     * new name differs between a comic and a book.
     */
    private static int rename(Sftp sftp, List<Move> moves, String root)
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
