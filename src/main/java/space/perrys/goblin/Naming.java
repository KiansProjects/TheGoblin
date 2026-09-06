package space.perrys.goblin;

import java.util.regex.Pattern;

/** Baut die Pfade so, wie Jellyfins Scanner sie erwartet. */
final class Naming {

    private static final Pattern ILLEGAL = Pattern.compile("[/\\\\:*?\"<>|\\x00-\\x1F]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /** Steht in Dateinamen, wenn nach dem Saeubern nichts uebrig bleibt. */
    private static final String FALLBACK = "Unbenannt";

    private Naming() {
    }

    /** Entfernt alles, was in einem Dateinamen Aerger macht. */
    static String sanitize(String raw) {
        String s = ILLEGAL.matcher(raw).replaceAll("");
        s = SPACES.matcher(s).replaceAll(" ").strip();
        // Punkte am Ende mag Windows nicht, und irgendwann kopierst du das doch mal rueber
        while (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1).strip();
        }
        return s.isEmpty() ? FALLBACK : s;
    }

    /** z.B. "Ninjago (2011) [tmdbid-12345]" */
    static String seriesFolder(String name, Integer year, Integer tmdbId) {
        StringBuilder sb = new StringBuilder(sanitize(name));
        if (year != null) {
            sb.append(" (").append(year).append(')');
        }
        if (tmdbId != null) {
            sb.append(" [tmdbid-").append(tmdbId).append(']');
        }
        return sb.toString();
    }

    /** z.B. "Der Film (2017) [tmdbid-12345]" - fuer Filme heisst der Schluessel genauso. */
    static String movieFolder(String title, Integer year, Integer tmdbId) {
        return seriesFolder(title, year, tmdbId);
    }

    /** z.B. "Der Film (2017).mkv" - ohne ID, die steht schon im Ordnernamen. */
    static String movieFile(String title, Integer year, String ext) {
        StringBuilder sb = new StringBuilder(sanitize(title));
        if (year != null) {
            sb.append(" (").append(year).append(')');
        }
        return sb.append('.').append(ext).toString();
    }

    /** z.B. "Season 01" */
    static String seasonFolder(int season) {
        return String.format("Season %02d", season);
    }

    /** z.B. "Ninjago S01E01 - Way of the Ninja.mp4" */
    static String episodeFile(String seriesName, int season, int episode, String chapterTitle, String ext) {
        String base = String.format("%s S%02dE%02d", sanitize(seriesName), season, episode);
        String name = base;
        if (chapterTitle != null && !chapterTitle.isBlank()) {
            String title = sanitize(chapterTitle);
            if (!title.equals(FALLBACK)) {
                name = base + " - " + title;
            }
        }
        return name + "." + ext;
    }
}
