package space.perrys.goblin;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Builds the paths the way Jellyfin's scanner expects them. */
final class Naming {

    private static final Pattern ILLEGAL = Pattern.compile("[/\\\\:*?\"<>|\\x00-\\x1F]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /** Goes into file names when nothing is left after cleaning. */
    private static final String FALLBACK = "Untitled";

    /** What a Linux file system stores per name. Bytes, not characters. */
    private static final int NAME_MAX = 255;

    /** Left dangling by a cut, and not worth keeping at the end of a name. */
    private static final Pattern TRAILING = Pattern.compile("[\\s\\-\u2013_,.;:]+$");

    private Naming() {
    }

    /** Removes everything that causes trouble in a file name. */
    static String sanitize(String raw) {
        String s = ILLEGAL.matcher(raw).replaceAll("");
        s = SPACES.matcher(s).replaceAll(" ").strip();
        // Windows dislikes trailing dots, and sooner or later you will copy this over
        while (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1).strip();
        }
        return s.isEmpty() ? FALLBACK : s;
    }

    /** e.g. "Ninjago (2011) [tmdbid-12345]" */
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

    /** e.g. "The Movie (2017) [tmdbid-12345]" - movies use the same key. */
    static String movieFolder(String title, Integer year, Integer tmdbId) {
        return seriesFolder(title, year, tmdbId);
    }

    /** e.g. "The Movie (2017).mkv" - without the ID, that is already in the folder name. */
    static String movieFile(String title, Integer year, String ext) {
        StringBuilder sb = new StringBuilder(sanitize(title));
        if (year != null) {
            sb.append(" (").append(year).append(')');
        }
        return sb.append('.').append(ext).toString();
    }

    /** e.g. "Season 01" */
    static String seasonFolder(int season) {
        return String.format("Season %02d", season);
    }

    /** e.g. "Ninjago S01E01 - Way of the Ninja.mp4" */
    static String episodeFile(String seriesName, int season, int episode, String chapterTitle, String ext) {
        String base = String.format("%s S%02dE%02d", sanitize(seriesName), season, episode);
        String name = base;
        if (chapterTitle != null && !chapterTitle.isBlank()) {
            String title = sanitize(chapterTitle);
            if (!title.equals(FALLBACK)) {
                name = base + " - " + title;
            }
        }
        return shorten(name, ext) + "." + ext;
    }

    /**
     * A name trimmed until the file system will take it.
     *
     * The limit is 255 bytes per name rather than 255 characters, so a title
     * in Japanese or one with accents runs out sooner than its length
     * suggests. And a title can be long entirely on its own: TMDb has Clerks
     * S01E05 as a 260-character joke, which the move answers with "File name
     * too long" and no file.
     *
     * The numbering at the front is what has to survive, so the cut falls at
     * the end - on a word boundary unless that would throw away half of what
     * fits, and never inside a character.
     */
    private static String shorten(String name, String ext) {
        int budget = NAME_MAX - (ext.length() + 1);
        if (name.getBytes(StandardCharsets.UTF_8).length <= budget) {
            return name;
        }

        StringBuilder kept = new StringBuilder(name.length());
        int used = 0;
        for (int i = 0; i < name.length(); ) {
            int point = name.codePointAt(i);
            int width = new String(Character.toChars(point))
                    .getBytes(StandardCharsets.UTF_8).length;
            if (used + width > budget) {
                break;
            }
            kept.appendCodePoint(point);
            used += width;
            i += Character.charCount(point);
        }

        String cut = kept.toString();
        int space = cut.lastIndexOf(' ');
        if (space >= cut.length() / 2) {
            cut = cut.substring(0, space);
        }
        return TRAILING.matcher(cut).replaceAll("");
    }
}
