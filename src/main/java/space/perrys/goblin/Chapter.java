package space.perrys.goblin;

/**
 * Ein Abschnitt des Videos.
 *
 * @param start Startzeit in Sekunden
 * @param end   Endzeit in Sekunden
 * @param title Titel ohne den Zeitstempel
 */
record Chapter(double start, double end, String title) {

    double duration() {
        return end - start;
    }

    String timecode() {
        return timecode(start);
    }

    static String timecode(double seconds) {
        long total = (long) seconds;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        return h > 0
                ? String.format("%d:%02d:%02d", h, m, s)
                : String.format("%d:%02d", m, s);
    }
}
