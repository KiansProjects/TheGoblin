package space.perrys.goblin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a shelf of comics is, independent of where the files happen to live.
 *
 * The local {@link Tidy} and the SFTP {@link RemoteTidy} have nothing in
 * common at the filesystem level and everything in common above it: the same
 * three sources of truth, the same two-pass folder naming, the same lookup.
 * That shared part lives here so the two cannot drift apart.
 *
 * Deliberately free of Path and of any remote handle - it takes names and
 * bytes and gives back names.
 */
final class Comics {

    /** What one file says about itself, before its folder is decided. */
    record Id(String series, String number, Integer year, String how) {
    }

    /**
     * A series after everything has been read and, where there is a key,
     * looked up.
     *
     * @param folder what the folder is called
     * @param volume the ComicVine entry, or null when there was no key, no
     *               match, or the lookup failed
     */
    record Series(String name, String folder, ComicVine.Volume volume) {
    }

    private Comics() {
    }

    /** Reads one archive's ComicInfo.xml, falling back to its file name. */
    static Id identify(String fileName, byte[] comicInfoXml) {
        if (comicInfoXml != null && comicInfoXml.length > 0) {
            Cbz.Info info = Cbz.parse(new String(comicInfoXml, java.nio.charset.StandardCharsets.UTF_8));
            if (info != null) {
                return new Id(info.series(), info.number(), info.year(), "ComicInfo.xml");
            }
        }
        Guess.Comic guess = Guess.comic(fileName);
        return (guess == null) ? null
                : new Id(guess.series(), guess.number(), guess.year(), "file name");
    }

    /**
     * Decides what each series' folder is called.
     *
     * The year on an issue is the year that issue came out, so using it for
     * the folder would scatter one run across "Saga (2012)" and "Saga (2013)".
     * The folder takes the earliest year seen instead - and where ComicVine
     * answers, its start year, which is the same thing but right even when the
     * first issue is the one you are missing.
     *
     * @param comicVine may be null, in which case only the files are used
     * @return keyed by the lower-cased series name as the files spell it
     */
    static Map<String, Series> resolve(Collection<Id> ids, ComicVine comicVine) {
        Map<String, Integer> earliest = new LinkedHashMap<>();
        Map<String, String> spelling = new LinkedHashMap<>();

        for (Id id : ids) {
            if (id == null) {
                continue;
            }
            String key = id.series().toLowerCase(Locale.ROOT);
            spelling.putIfAbsent(key, id.series());
            if (id.year() != null) {
                earliest.merge(key, id.year(), Math::min);
            }
        }

        Map<String, Series> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : spelling.entrySet()) {
            String key = e.getKey();
            String name = e.getValue();
            Integer year = earliest.get(key);

            ComicVine.Volume volume = null;
            if (comicVine != null) {
                try {
                    volume = comicVine.search(name, year);
                } catch (IOException | InterruptedException ex) {
                    System.out.println("  ComicVine: " + name + " - " + ex.getMessage());
                }
            }

            // The database's name and start year win when it answered, because
            // that is the whole point of asking. What it cannot supply falls
            // back to the files.
            String finalName = (volume != null && volume.name() != null) ? volume.name() : name;
            Integer finalYear = (volume != null && volume.startYear() != null)
                    ? volume.startYear()
                    : year;

            if (volume != null) {
                System.out.printf("  ComicVine: %s (%s)%s, ID %d%n",
                        volume.name(),
                        (volume.startYear() == null) ? "?" : volume.startYear(),
                        (volume.publisher() == null) ? "" : ", " + volume.publisher(),
                        volume.id());
            }

            out.put(key, new Series(finalName, folder(finalName, finalYear), volume));
        }
        return out;
    }

    static String folder(String series, Integer year) {
        String clean = Naming.sanitize(series);
        return (year == null) ? clean : clean + " (" + year + ")";
    }

    /**
     * The file name for one issue.
     *
     * The issue's own year, not the series', so two printings stay apart.
     *
     * @param title an issue title from the database, or null to leave it off
     */
    static String fileName(Id id, String seriesName, String title, String extension) {
        StringBuilder name = new StringBuilder(Naming.sanitize(seriesName));
        if (id.number() != null) {
            name.append(" #").append(issue(id.number()));
        }
        if (id.year() != null) {
            name.append(" (").append(id.year()).append(')');
        }
        if (title != null && !title.isBlank()) {
            name.append(" - ").append(Naming.sanitize(title));
        }
        return name.append('.').append(extension).toString();
    }

    /**
     * Pads a plain issue number to three digits so that #9 sorts before #10.
     * Anything that is not a plain number - "1.MU", "Annual 1" - is left as it
     * is rather than guessed at.
     */
    static String issue(String number) {
        String value = number.strip();
        if (!value.matches("\\d{1,3}")) {
            return value;
        }
        return String.format("%03d", Integer.parseInt(value));
    }

    /**
     * The series.json that ComicRack wrote and Komga and Kavita read.
     *
     * Written next to the issues rather than into them: putting metadata into
     * each archive means rewriting every file, and a rewrite that goes wrong
     * costs a comic. This costs one small file per series.
     *
     * Hand-rolled rather than through a library, like the rest of this project.
     */
    static String seriesJson(Series series) {
        ComicVine.Volume v = series.volume();
        List<String> fields = new ArrayList<>();

        fields.add("    \"type\": \"comicSeries\"");
        fields.add("    \"name\": " + quote(series.name()));

        if (v != null) {
            if (v.publisher() != null) {
                fields.add("    \"publisher\": " + quote(v.publisher()));
            }
            if (v.startYear() != null) {
                fields.add("    \"year_begin\": " + v.startYear());
            }
            if (v.description() != null) {
                fields.add("    \"description_formatted\": " + quote(v.description()));
            }
            fields.add("    \"comicid\": " + v.id());
        }

        // No "status": ComicVine does not tell us whether a series has ended,
        // and writing "Ended" over every shelf would be asserting something we
        // never asked about.
        return "{\n  \"metadata\": {\n"
                + String.join(",\n", fields)
                + "\n  }\n}\n";
    }

    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** Issue titles by issue number, for the series that were looked up. */
    static Map<String, String> titles(ComicVine comicVine, ComicVine.Volume volume) {
        Map<String, String> out = new LinkedHashMap<>();
        if (comicVine == null || volume == null) {
            return out;
        }
        try {
            for (ComicVine.Issue issue : comicVine.issues(volume.id())) {
                if (issue.number() != null && issue.name() != null) {
                    out.put(normalise(issue.number()), issue.name());
                }
            }
        } catch (IOException | InterruptedException e) {
            System.out.println("  ComicVine issues: " + e.getMessage());
        }
        return out;
    }

    /** "001", "1" and "1.0" all mean the same issue. */
    static String normalise(String number) {
        String value = number.strip().replaceFirst("^0+(?=\\d)", "");
        return value.endsWith(".0") ? value.substring(0, value.length() - 2) : value;
    }

    /** The temporary file used while a cover is on its way somewhere. */
    static Path tempCover() throws IOException {
        return java.nio.file.Files.createTempFile("goblin-cover", ".jpg");
    }

    static List<String> sortedKeys(Map<String, Series> series) {
        List<String> keys = new ArrayList<>(series.keySet());
        keys.sort(String::compareTo);
        return keys;
    }
}
