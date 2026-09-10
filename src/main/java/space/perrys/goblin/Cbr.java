package space.perrys.goblin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Repacks a .cbr into a .cbz.
 *
 * Both are one issue's pages in an archive; the only difference is RAR versus
 * zip. That difference costs more than it looks: {@link Cbz} can read the
 * ComicInfo.xml out of a zip with nothing but the standard library, and RAR
 * needs a decoder that Java does not have. So a .cbr is sorted by
 * {@link Guess}, from its file name - the weakest source there is - while the
 * same issue as a .cbz is sorted from what its publisher wrote inside it.
 * Converting moves a file from the worst source to the best one.
 *
 * The unpacking is left to an external program, the way yt-dlp and ffmpeg do
 * the other heavy lifting here. Whichever of them is installed is used; with
 * none of them the conversion is skipped and the file stays as it is.
 */
final class Cbr {

    /**
     * In order of preference. unar and unrar read every RAR version; 7z
     * depends on the build, and bsdtar on how libarchive was compiled.
     */
    private static final String[] EXTRACTORS = {"unar", "unrar", "7zz", "7z", "bsdtar"};

    /** What the operating system and the scanner leave behind. */
    private static final List<String> JUNK =
            List.of("__macosx", "thumbs.db", "desktop.ini", ".ds_store");

    /**
     * @param entries how many files the .cbz ended up with
     * @param wasZip  the .cbr was a zip all along and only needed renaming
     */
    record Converted(int entries, boolean wasZip) {
    }

    /** The outcome of a run over several archives. */
    record Tally(int converted, int failed) {
    }

    private Cbr() {
    }

    static int run(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: goblin cbz <folder> [--apply] [--keep]");
            return 2;
        }

        Path where = Path.of(args[1]);
        boolean apply = false;
        boolean keep = false;

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--apply" -> apply = true;
                case "--keep" -> keep = true;
                default -> {
                    System.err.println("Unknown option: " + args[i]);
                    return 2;
                }
            }
        }

        List<Path> archives = archives(where);
        if (archives.isEmpty()) {
            System.out.println("No .cbr files found under " + where + ".");
            return 0;
        }

        String tool = extractor();
        System.out.printf("%d .cbr %s under %s%n", archives.size(),
                archives.size() == 1 ? "file" : "files", where);
        if (tool != null) {
            System.out.println("Unpacking with " + tool + ".");
        } else {
            System.out.println("No extractor found. Install one of "
                    + String.join(", ", EXTRACTORS)
                    + " - only files that are zips under a .cbr name can be "
                    + "converted without one.");
        }
        if (!apply) {
            System.out.println("Dry run: nothing is written or deleted.");
        }
        System.out.println();

        if (!apply) {
            for (Path cbr : archives) {
                System.out.println("  " + cbr.getFileName() + "  ->  "
                        + target(cbr).getFileName());
            }
            System.out.println();
            System.out.println("Nothing was touched. Run again with --apply.");
            return 0;
        }

        Tally tally = convertAll(archives, tool, keep);

        System.out.println();
        System.out.printf("%d converted, the .cbr %s.%n", tally.converted(),
                keep ? "files kept" : "files removed");
        if (tally.failed() > 0) {
            System.out.println(tally.failed() + " could not be converted and were left alone.");
        }
        return (tally.failed() > 0) ? 1 : 0;
    }

    /**
     * Converts every archive in the list, reporting each one as it goes.
     *
     * A file that will not convert is one comic still sorted by its name, not
     * a failed run, so the loop carries on and the count comes back.
     *
     * @param keep whether to leave the .cbr behind once the .cbz is readable
     */
    static Tally convertAll(List<Path> archives, String tool, boolean keep)
            throws InterruptedException {
        int converted = 0;
        int failed = 0;

        for (Path cbr : archives) {
            String name = cbr.getFileName().toString();
            Path cbz = target(cbr);

            if (Files.exists(cbz)) {
                System.out.println("  " + name + ": " + cbz.getFileName()
                        + " is already there, left alone.");
                continue;
            }

            try {
                Converted result = convert(cbr, cbz, tool);
                if (!keep) {
                    Files.delete(cbr);
                }
                System.out.printf("  %s  ->  %s  (%d %s%s)%n", name, cbz.getFileName(),
                        result.entries(), result.entries() == 1 ? "page" : "pages",
                        result.wasZip() ? ", was a zip under a .cbr name" : "");
                converted++;
            } catch (IOException e) {
                System.out.println("  " + name + ": not converted (" + e.getMessage() + ")");
                failed++;
            }
        }
        return new Tally(converted, failed);
    }

    private static Path target(Path cbr) {
        return cbr.resolveSibling(
                cbr.getFileName().toString().replaceFirst("(?i)\\.cbr$", "") + ".cbz");
    }

    /** A single .cbr, or every .cbr below a folder. */
    private static List<Path> archives(Path where) throws IOException {
        if (Files.isRegularFile(where)) {
            return List.of(where);
        }
        if (!Files.isDirectory(where)) {
            throw new IOException("No such file or directory: " + where);
        }
        try (var walk = Files.walk(where)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(".cbr"))
                    .sorted()
                    .toList();
        }
    }

    /** @return the extractor to use, or null when none is installed */
    static String extractor() {
        for (String program : EXTRACTORS) {
            if (Proc.exists(program)) {
                return program;
            }
        }
        return null;
    }

    /**
     * Writes {@code cbr}'s contents to {@code cbz}. Leaves the original alone -
     * deleting it is the caller's decision, and only worth making once the new
     * file has been read back.
     *
     * @param tool the extractor from {@link #extractor()}
     * @throws IOException when the archive cannot be unpacked or comes out empty
     */
    static Converted convert(Path cbr, Path cbz, String tool)
            throws IOException, InterruptedException {
        if (Files.exists(cbz)) {
            throw new IOException(cbz.getFileName() + " already exists");
        }

        // Plenty of ".cbr" files are zips that somebody renamed. Unpacking one
        // would be a waste, and with no extractor installed it would fail for
        // no reason at all.
        if (isZip(cbr)) {
            Files.copy(cbr, cbz);
            return new Converted(count(cbz), true);
        }
        if (tool == null) {
            throw new IOException("no extractor installed");
        }

        // Next to the archive rather than in /tmp: same filesystem, so the
        // space needed is the space the user can see, and the finished file
        // moves into place instead of being copied across a device boundary.
        Path work = Files.createTempDirectory(
                cbr.toAbsolutePath().getParent(), ".goblin-cbr-");
        try {
            Proc.capture(command(tool, cbr, work));

            List<Path> pages = pages(work);
            if (pages.isEmpty()) {
                throw new IOException("the archive unpacked to nothing");
            }

            // Written under a different name and moved into place afterwards,
            // so an interrupted run cannot leave a half-written .cbz that
            // looks like a finished one.
            Path partial = cbz.resolveSibling(cbz.getFileName() + ".part");
            pack(pages, prefix(work, pages), partial);

            int entries = count(partial);
            if (entries != pages.size()) {
                throw new IOException("wrote " + pages.size() + " pages but the archive holds "
                        + entries);
            }

            Files.move(partial, cbz, StandardCopyOption.ATOMIC_MOVE);
            return new Converted(entries, false);
        } finally {
            deleteTree(work);
        }
    }

    private static List<String> command(String tool, Path archive, Path into) {
        String from = archive.toAbsolutePath().toString();
        String to = into.toAbsolutePath().toString();
        return switch (tool) {
            case "unar" -> List.of("unar", "-quiet", "-force-overwrite",
                    "-output-directory", to, from);
            case "unrar" -> List.of("unrar", "x", "-y", "-idq", from, to + "/");
            case "7zz", "7z" -> List.of(tool, "x", "-y", "-bso0", "-bsp0", "-o" + to, from);
            case "bsdtar" -> List.of("bsdtar", "-x", "-f", from, "-C", to);
            default -> throw new IllegalArgumentException("unknown extractor: " + tool);
        };
    }

    /**
     * The unpacked files worth keeping, in reading order.
     *
     * Sorted the way a page number sorts rather than the way a string does, so
     * page 10 does not land between 1 and 2. Readers mostly sort for
     * themselves, but the ones that trust the archive's order should get it
     * right.
     */
    private static List<Path> pages(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).filter(Cbr::wanted).forEach(files::add);
        }
        files.sort(Comparator.comparing(p -> root.relativize(p).toString(), Cbr::natural));
        return files;
    }

    private static boolean wanted(Path file) {
        for (Path part : file) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            if (JUNK.contains(name) || name.startsWith("._")) {
                return false;
            }
        }
        return true;
    }

    /**
     * A leading directory that every file shares, which the archive's own
     * folder puts there and no reader wants to see.
     */
    private static Path prefix(Path root, List<Path> pages) {
        Path prefix = root;
        while (true) {
            Path common = null;
            for (Path page : pages) {
                Path relative = prefix.relativize(page);
                if (relative.getNameCount() < 2) {
                    return prefix;
                }
                Path head = relative.getName(0);
                if (common == null) {
                    common = head;
                } else if (!common.equals(head)) {
                    return prefix;
                }
            }
            prefix = prefix.resolve(common);
        }
    }

    private static void pack(List<Path> pages, Path prefix, Path target) throws IOException {
        try (var out = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            // The pages are JPEGs and PNGs, already compressed. Deflating them
            // again costs time and returns nothing.
            zip.setLevel(Deflater.NO_COMPRESSION);
            for (Path page : pages) {
                ZipEntry entry = new ZipEntry(
                        prefix.relativize(page).toString().replace('\\', '/'));
                entry.setTime(Files.getLastModifiedTime(page).toMillis());
                zip.putNextEntry(entry);
                Files.copy(page, zip);
                zip.closeEntry();
            }
        }
    }

    /** Reads the finished archive back, which is what proves it is one. */
    private static int count(Path zip) throws IOException {
        try (ZipFile file = new ZipFile(zip.toFile())) {
            return (int) file.stream().filter(e -> !e.isDirectory()).count();
        }
    }

    static boolean isZip(Path file) {
        byte[] head = new byte[4];
        try (InputStream in = Files.newInputStream(file)) {
            if (in.readNBytes(head, 0, 4) < 4) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        return head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
    }

    private static void deleteTree(Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // A leftover temp folder is worth a shrug, not a failure.
                }
            });
        } catch (IOException e) {
            // Same.
        }
    }

    /** Compares digit runs by value, everything else by character. */
    private static int natural(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char left = a.charAt(i);
            char right = b.charAt(j);

            if (Character.isDigit(left) && Character.isDigit(right)) {
                int startLeft = i;
                int startRight = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) {
                    i++;
                }
                while (j < b.length() && Character.isDigit(b.charAt(j))) {
                    j++;
                }
                String numberLeft = a.substring(startLeft, i).replaceFirst("^0+(?=.)", "");
                String numberRight = b.substring(startRight, j).replaceFirst("^0+(?=.)", "");
                if (numberLeft.length() != numberRight.length()) {
                    return numberLeft.length() - numberRight.length();
                }
                int compared = numberLeft.compareTo(numberRight);
                if (compared != 0) {
                    return compared;
                }
                continue;
            }

            int compared = Character.compare(Character.toLowerCase(left),
                    Character.toLowerCase(right));
            if (compared != 0) {
                return compared;
            }
            i++;
            j++;
        }
        return (a.length() - i) - (b.length() - j);
    }
}
