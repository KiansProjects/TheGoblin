package space.perrys.goblin;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads what a file name is willing to say about its contents.
 *
 * The weakest of the identification sources and the last one tried: embedded
 * metadata is a statement, a file name is a habit. It is still the only thing
 * a video file offers, and it rescues the comics and tracks whose tags were
 * never written.
 *
 * Everything here returns null rather than a guess it cannot support. An
 * unsorted file is a small annoyance; a file sorted into the wrong series is
 * one you have to hunt for later.
 */
final class Guess {

    /** "S01E02", "s1e2", and the "1x02" that older rippers wrote. */
    private static final Pattern EPISODE = Pattern.compile(
            "(?:^|[^a-z0-9])(?:s(\\d{1,2})[\\s._-]*e(\\d{1,3})|(\\d{1,2})x(\\d{1,3}))(?:[^0-9]|$)",
            Pattern.CASE_INSENSITIVE);

    /** A year in brackets is a deliberate statement, so it wins. */
    private static final Pattern BRACKETED_YEAR = Pattern.compile("[(\\[]((?:19|20)\\d{2})[)\\]]");

    private static final Pattern BARE_YEAR = Pattern.compile("(?:^|[^0-9])((?:19|20)\\d{2})(?:[^0-9]|$)");

    /** "#012", "012", "Vol. 2 #7" - the issue number of a comic. */
    private static final Pattern ISSUE = Pattern.compile("#\\s*(\\d{1,4}(?:\\.\\d)?[a-z]{0,3})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TRAILING_NUMBER = Pattern.compile("(?:^|\\s)(\\d{1,4})\\s*$");

    private static final Pattern VOLUME = Pattern.compile("\\bv(?:ol)?\\.?\\s*\\d{1,3}\\b",
            Pattern.CASE_INSENSITIVE);

    /** "01 - Title", "01. Title", "1_Title". */
    private static final Pattern TRACK = Pattern.compile("^\\s*(\\d{1,3})\\s*[-._)]\\s*(.+)$");

    /**
     * Where a title stops and the release notes begin. Everything from the
     * first of these onwards is somebody's encoding settings, not the name of
     * anything.
     */
    private static final List<String> NOISE = List.of(
            "1080p", "2160p", "720p", "480p", "4k", "uhd", "hdr", "sdr",
            "bluray", "blu-ray", "bdrip", "brrip", "dvdrip", "webrip", "web-dl",
            "webdl", "hdtv", "pdtv", "hdrip", "camrip",
            "x264", "x265", "h264", "h265", "hevc", "avc", "xvid", "divx",
            "aac", "ac3", "dts", "ddp5", "flac", "mp3",
            "remux", "proper", "repack", "extended", "unrated", "internal",
            "multi", "dual", "dubbed", "subbed", "german", "english", "ger", "eng");

    record Episode(String series, int season, int episode, String title) {
    }

    record Movie(String title, Integer year) {
    }

    record Comic(String series, String number, Integer year) {
    }

    record Track(Integer number, String title) {
    }

    private Guess() {
    }

    /**
     * @return null when the name carries no season/episode marker at all
     */
    static Episode episode(String fileName) {
        String name = words(stripExtension(fileName));

        Matcher m = EPISODE.matcher(name);
        if (!m.find()) {
            return null;
        }

        boolean sxe = m.group(1) != null;
        int season = Integer.parseInt(sxe ? m.group(1) : m.group(3));
        int episode = Integer.parseInt(sxe ? m.group(2) : m.group(4));

        String series = clean(name.substring(0, m.start()));
        String title = clean(name.substring(m.end()));

        // A name that is nothing but "S01E02" identifies an episode of nothing.
        return series.isEmpty() ? null : new Episode(series, season, episode,
                title.isEmpty() ? null : title);
    }

    /**
     * @return the title with its year, or null when nothing is left of the name
     */
    static Movie movie(String fileName) {
        String name = words(stripExtension(fileName));

        Integer year = null;
        int cut = -1;

        Matcher bracketed = lastMatch(BRACKETED_YEAR, name);
        if (bracketed != null) {
            year = Integer.valueOf(bracketed.group(1));
            cut = bracketed.start();
        } else {
            Matcher bare = lastMatch(BARE_YEAR, name);
            // Only when something precedes it - otherwise the year is the title,
            // as in the film called 2012.
            if (bare != null && !clean(name.substring(0, bare.start(1))).isEmpty()) {
                year = Integer.valueOf(bare.group(1));
                cut = bare.start(1);
            }
        }

        String title = clean(cut >= 0 ? name.substring(0, cut) : name);
        return title.isEmpty() ? null : new Movie(title, year);
    }

    /**
     * @return series, issue and year as far as the name gives them up, or null
     *         when no series name is left
     */
    static Comic comic(String fileName) {
        String name = words(stripExtension(fileName));

        Integer year = null;
        Matcher bracketed = lastMatch(BRACKETED_YEAR, name);
        if (bracketed != null) {
            year = Integer.valueOf(bracketed.group(1));
            name = name.substring(0, bracketed.start()) + " " + name.substring(bracketed.end());
        }

        // The volume belongs to the series, not to the issue, and it would
        // otherwise be read as the issue number.
        name = VOLUME.matcher(name).replaceAll(" ");

        String number = null;
        Matcher issue = ISSUE.matcher(name);
        if (issue.find()) {
            number = issue.group(1);
            name = name.substring(0, issue.start()) + " " + name.substring(issue.end());
        } else {
            Matcher trailing = TRAILING_NUMBER.matcher(name);
            if (trailing.find()) {
                number = trailing.group(1);
                name = name.substring(0, trailing.start());
            }
        }

        String series = clean(name);

        // A name made only of digits is a barcode or a scanner's counter, not
        // a series. Better unsorted than filed under "1234567890".
        return series.matches(".*\\p{L}.*") ? new Comic(series, number, year) : null;
    }

    /**
     * @return the leading track number and what follows it, or a title alone
     *         when the name carries no number
     */
    static Track track(String fileName) {
        String name = stripExtension(fileName).strip();

        Matcher m = TRACK.matcher(name);
        if (m.find()) {
            String title = clean(words(m.group(2)));
            if (!title.isEmpty()) {
                return new Track(Integer.valueOf(m.group(1)), title);
            }
        }

        String title = clean(words(name));
        return title.isEmpty() ? null : new Track(null, title);
    }

    static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return (dot <= 0) ? fileName : fileName.substring(0, dot);
    }

    /** Dots and underscores stand in for spaces in half the names in the wild. */
    private static String words(String s) {
        return s.replace('_', ' ').replace('.', ' ').replaceAll("\\s+", " ").strip();
    }

    /**
     * Trims separators off both ends and drops everything from the first
     * release-notes token onwards.
     */
    private static String clean(String s) {
        String[] parts = s.split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            String bare = part.replaceAll("^[-–_(\\[]+|[-–_)\\]]+$", "");
            if (bare.isEmpty()) {
                continue;
            }
            if (NOISE.contains(bare.toLowerCase(Locale.ROOT))) {
                break;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(bare);
        }
        return out.toString().replaceAll("^[\\s\\-–_]+|[\\s\\-–_]+$", "");
    }

    /**
     * The last match wins: a title may contain something the pattern also
     * fits, as in "Blade Runner 2049 (2017)".
     */
    private static Matcher lastMatch(Pattern pattern, String s) {
        Matcher scan = pattern.matcher(s);
        int start = -1;
        while (scan.find()) {
            start = scan.start();
        }
        if (start < 0) {
            return null;
        }

        Matcher last = pattern.matcher(s);
        last.find(start);
        return last;
    }
}
