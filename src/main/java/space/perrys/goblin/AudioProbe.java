package space.perrys.goblin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

/**
 * Vergleicht Tonspuren ueber ihren Lautstaerkeverlauf.
 *
 * Statt die Rohsamples zu vergleichen - zu viel Rechnerei und empfindlich
 * gegen unterschiedliche Kodierung - wird der Ton auf 8 kHz Mono
 * heruntergerechnet und daraus je 25 ms ein Effektivwert gebildet. Der
 * daraus entstehende Verlauf ist grob genug, um Kodierungsunterschiede zu
 * ueberstehen, und fein genug, um dieselbe Stelle wiederzufinden.
 */
final class AudioProbe {

    /** Laenge eines Fensters in Sekunden. */
    static final double WINDOW = 0.025;

    private static final int SAMPLE_RATE = 8000;
    private static final int SAMPLES_PER_WINDOW = (int) (SAMPLE_RATE * WINDOW);

    /**
     * @param lagSeconds wie weit der Anfang von B im Ende von A gefunden wurde
     * @param lengthSeconds wie lang die uebereinstimmende Passage ist
     * @param score Uebereinstimmung von 0 bis 1
     */
    record Overlap(double lagSeconds, double lengthSeconds, double score) {
    }

    private AudioProbe() {
    }

    /**
     * Lautstaerkeverlauf eines Ausschnitts.
     *
     * @param start Startzeit in Sekunden, negativ ist nicht erlaubt
     * @param duration Laenge des Ausschnitts in Sekunden
     * @return ein Wert je 25 ms, oder ein leeres Feld bei Fehlern
     */
    static double[] envelope(Path media, double start, double duration) {
        byte[] pcm;
        try {
            pcm = raw(media, start, duration);
        } catch (IOException | InterruptedException e) {
            return new double[0];
        }

        int windows = pcm.length / 2 / SAMPLES_PER_WINDOW;
        double[] out = new double[Math.max(0, windows)];

        for (int w = 0; w < windows; w++) {
            long sum = 0;
            int base = w * SAMPLES_PER_WINDOW * 2;
            for (int i = 0; i < SAMPLES_PER_WINDOW; i++) {
                int lo = pcm[base + i * 2] & 0xFF;
                int hi = pcm[base + i * 2 + 1];
                int sample = (hi << 8) | lo;
                sum += (long) sample * sample;
            }
            out[w] = Math.sqrt((double) sum / SAMPLES_PER_WINDOW);
        }
        return out;
    }

    /**
     * Sucht den Anfang von {@code head} im Verlauf von {@code tail}.
     *
     * Verglichen wird ueber den normierten Korrelationskoeffizienten, damit
     * unterschiedliche Aussteuerung nicht stoert.
     *
     * @param minSeconds kuerzeste Passage, die als Treffer zaehlt
     * @return leer, wenn nichts Belastbares gefunden wurde
     */
    static OptionalDouble bestMatch(double[] tail, double[] head,
                                    double minSeconds, double maxSeconds, double minScore) {
        int minWindows = (int) (minSeconds / WINDOW);
        if (tail.length < minWindows || head.length < minWindows) {
            return OptionalDouble.empty();
        }

        double best = -1;
        int bestLag = -1;

        // Ueberlappungen jenseits von maxSeconds sind bei geschnittenen
        // Folgen unplausibel und meist ein Fehltreffer: bei periodischer
        // Musik wiederholt sich der Lautstaerkeverlauf, und dann passt
        // derselbe Anfang an mehreren Stellen.
        int firstLag = Math.max(0, tail.length - (int) (maxSeconds / WINDOW));

        // Der Anfang von head kann irgendwo in tail liegen. Ab dort muessen
        // beide bis zum Ende von tail uebereinstimmen.
        for (int lag = firstLag; lag <= tail.length - minWindows; lag++) {
            int length = Math.min(tail.length - lag, head.length);
            double score = correlate(tail, lag, head, length);
            if (score > best) {
                best = score;
                bestLag = lag;
            }
        }

        return (best >= minScore) ? OptionalDouble.of(bestLag * WINDOW) : OptionalDouble.empty();
    }

    /** Normierter Korrelationskoeffizient zweier Abschnitte. */
    private static double correlate(double[] a, int offsetA, double[] b, int length) {
        double sumA = 0;
        double sumB = 0;
        for (int i = 0; i < length; i++) {
            sumA += a[offsetA + i];
            sumB += b[i];
        }
        double meanA = sumA / length;
        double meanB = sumB / length;

        double num = 0;
        double devA = 0;
        double devB = 0;
        for (int i = 0; i < length; i++) {
            double da = a[offsetA + i] - meanA;
            double db = b[i] - meanB;
            num += da * db;
            devA += da * da;
            devB += db * db;
        }

        double denom = Math.sqrt(devA * devB);
        return (denom == 0) ? 0 : num / denom;
    }

    /** Rohes PCM eines Ausschnitts, 16 Bit, Mono, 8 kHz. */
    private static byte[] raw(Path media, double start, double duration)
            throws IOException, InterruptedException {

        List<String> cmd = List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error",
                "-ss", fmt(Math.max(0, start)),
                "-t", fmt(duration),
                "-i", media.toString(),
                "-vn", "-ac", "1", "-ar", String.valueOf(SAMPLE_RATE),
                "-f", "s16le", "-");

        Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            in.transferTo(buffer);
        }
        p.waitFor();
        return buffer.toByteArray();
    }

    private static String fmt(double seconds) {
        return String.format(Locale.ROOT, "%.3f", seconds);
    }
}
