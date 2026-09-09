package space.perrys.goblin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the end of the actual content, meaning the point from which only the
 * closing credits run.
 *
 * Two signals: a silence that reaches the end of the video, and a black frame
 * at the end. Both are reliably detectable. An outro with music and picture
 * running through is not - the fixed --outro value exists for that.
 */
final class OutroDetect {

    private static final Pattern BLACK_START =
            Pattern.compile("black_start:([0-9.]+)\\s+black_end:([0-9.]+)");

    /** How far back from the end the search runs. */
    private static final double SEARCH = Silence.SEARCH;

    /** Shortest outro that still counts as one. */
    private static final double MIN_OUTRO = Silence.MIN_TAIL;

    private OutroDetect() {
    }

    /**
     * @return the point from which the outro runs, or empty if none was detected
     */
    static OptionalDouble find(Path media, double duration) {
        double from = Math.max(0, duration - SEARCH);

        OptionalDouble silence = Silence.trailing(media, from, duration);
        OptionalDouble black = trailingBlack(media, from, duration);

        // The earliest plausible point wins: if the black frame starts before
        // the silence, the picture already belongs to the credits.
        if (silence.isPresent() && black.isPresent()) {
            return OptionalDouble.of(Math.min(silence.getAsDouble(), black.getAsDouble()));
        }
        if (silence.isPresent()) {
            return silence;
        }
        return black;
    }

    /** Black frame that runs through to the end. */
    private static OptionalDouble trailingBlack(Path media, double from, double duration) {
        String out = analyse(media, from, "blackdetect=d=1.0:pix_th=0.10");
        if (out == null) {
            return OptionalDouble.empty();
        }

        double start = -1;
        Matcher m = BLACK_START.matcher(out);
        while (m.find()) {
            double blackStart = Double.parseDouble(m.group(1));
            double blackEnd = Double.parseDouble(m.group(2));
            if (duration - blackEnd < 1.0) {
                start = blackStart;
            }
        }

        if (start < 0 || duration - start < MIN_OUTRO) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(start);
    }

    private static String analyse(Path media, double from, String filter) {
        try {
            return Proc.captureCombined(List.of(
                    "ffmpeg", "-hide_banner", "-nostats",
                    "-ss", fmt(from),
                    "-copyts",
                    "-i", media.toString(),
                    "-vf", filter,
                    "-an",
                    "-f", "null", "-"));
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    private static String fmt(double seconds) {
        return String.format(Locale.ROOT, "%.3f", seconds);
    }
}
