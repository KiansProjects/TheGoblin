package space.perrys.goblin;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Faellt ein, wenn YouTube die Zeitstempel nicht selbst als Kapitel erkannt hat.
 *
 * Zwei Durchlaeufe: zuerst nur Zeilen, die mit einem Zeitstempel beginnen - das
 * ist die uebliche Form einer Kapitelliste. Nur wenn das nichts liefert, wird
 * lockerer gesucht ("Episode 1 - 0:00"). Sonst landen Zeilen wie
 * "jeden Samstag um 10:00 Uhr" als Episode in deiner Mediathek.
 */
final class ChapterParser {

    private static final Pattern TIMESTAMP = Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?");
    private static final Pattern LEADING_JUNK = Pattern.compile("^[\\s\\-–—:•·|.,()\\[\\]#*>▶►=~_]+");
    private static final Pattern TRAILING_JUNK = Pattern.compile("[\\s\\-–—:•·|]+$");

    /** Uhrzeit-Angaben, die faelschlich wie ein Zeitstempel aussehen. */
    private static final Pattern CLOCK_SUFFIX =
            Pattern.compile("^\\s*(am|pm|a\\.m\\.|p\\.m\\.|uhr|est|pst|cet|utc|gmt)\\b",
                    Pattern.CASE_INSENSITIVE);

    private record Mark(double start, String title) {
    }

    private ChapterParser() {
    }

    static List<Chapter> parse(String description, double totalDuration) {
        if (description == null || description.isBlank()) {
            return List.of();
        }

        String[] lines = description.split("\\R");

        List<Mark> marks = collect(lines, totalDuration, true);
        if (marks.size() < 2) {
            marks = collect(lines, totalDuration, false);
        }
        if (marks.size() < 2) {
            return List.of();
        }

        marks.sort((a, b) -> Double.compare(a.start(), b.start()));

        List<Chapter> chapters = new ArrayList<>();
        for (int i = 0; i < marks.size(); i++) {
            double start = marks.get(i).start();
            double end = (i + 1 < marks.size()) ? marks.get(i + 1).start() : totalDuration;
            if (end - start < 1.0) {
                continue; // doppelter Zeitstempel
            }
            chapters.add(new Chapter(start, end, marks.get(i).title()));
        }
        return chapters;
    }

    /**
     * @param strict true verlangt, dass der Zeitstempel am Zeilenanfang steht
     */
    private static List<Mark> collect(String[] lines, double totalDuration, boolean strict) {
        List<Mark> marks = new ArrayList<>();

        for (String rawLine : lines) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }

            String stripped = LEADING_JUNK.matcher(line).replaceFirst("");
            Matcher m = TIMESTAMP.matcher(stripped);
            if (!m.find()) {
                continue;
            }
            if (strict && m.start() != 0) {
                continue;
            }

            String after = stripped.substring(m.end());
            if (CLOCK_SUFFIX.matcher(after).find()) {
                continue;
            }

            double seconds = toSeconds(m);
            if (totalDuration > 0 && seconds > totalDuration) {
                continue;
            }

            String before = stripped.substring(0, m.start());
            String title = clean(after.isBlank() ? before : after);
            if (title.isEmpty()) {
                continue;
            }

            marks.add(new Mark(seconds, title));
        }
        return marks;
    }

    private static double toSeconds(Matcher m) {
        int a = Integer.parseInt(m.group(1));
        int b = Integer.parseInt(m.group(2));
        String third = m.group(3);
        return third == null
                ? a * 60.0 + b
                : a * 3600.0 + b * 60.0 + Integer.parseInt(third);
    }

    private static String clean(String text) {
        String s = LEADING_JUNK.matcher(text).replaceFirst("");
        s = TRAILING_JUNK.matcher(s).replaceFirst("");
        return s.strip();
    }
}
