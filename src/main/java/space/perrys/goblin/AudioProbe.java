package space.perrys.goblin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

/**
 * Compares audio tracks by their loudness envelope.
 *
 * Instead of comparing the raw samples - too much computation and sensitive to
 * differing encodings - the audio is downmixed to 8 kHz mono and one RMS value
 * is formed per 25 ms. The resulting envelope is coarse enough to survive
 * encoding differences and fine enough to find the same spot again.
 */
final class AudioProbe {

    /** Length of one window in seconds. */
    static final double WINDOW = 0.025;

    private static final int SAMPLE_RATE = 8000;
    private static final int SAMPLES_PER_WINDOW = (int) (SAMPLE_RATE * WINDOW);

    /**
     * @param lagSeconds how far into the end of A the start of B was found
     * @param lengthSeconds how long the matching passage is
     * @param score match from 0 to 1
     */
    record Overlap(double lagSeconds, double lengthSeconds, double score) {
    }

    private AudioProbe() {
    }

    /**
     * Loudness envelope of a section.
     *
     * @param start start time in seconds, negative is not allowed
     * @param duration length of the section in seconds
     * @return one value per 25 ms, or an empty array on errors
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
     * Searches for the start of {@code head} within the envelope of {@code tail}.
     *
     * The comparison runs over the normalised correlation coefficient, so that
     * differing levels do not interfere.
     *
     * @param minSeconds shortest passage that counts as a match
     * @return empty when nothing solid was found
     */
    static OptionalDouble bestMatch(double[] tail, double[] head,
                                    double minSeconds, double maxSeconds, double minScore) {
        int minWindows = (int) (minSeconds / WINDOW);
        if (tail.length < minWindows || head.length < minWindows) {
            return OptionalDouble.empty();
        }

        double best = -1;
        int bestLag = -1;

        // Overlaps beyond maxSeconds are implausible for cut episodes and
        // usually a false match: with periodic music the loudness envelope
        // repeats, and then the same start fits in several places.
        int firstLag = Math.max(0, tail.length - (int) (maxSeconds / WINDOW));

        // The start of head can sit anywhere in tail. From there on both have
        // to match until the end of tail.
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

    /** Normalised correlation coefficient of two sections. */
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

    /** Raw PCM of a section, 16 bit, mono, 8 kHz. */
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
