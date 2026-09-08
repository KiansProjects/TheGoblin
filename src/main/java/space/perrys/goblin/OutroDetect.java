package space.perrys.goblin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Findet das Ende des eigentlichen Inhalts, also den Punkt, ab dem nur noch
 * Abspann laeuft.
 *
 * Zwei Signale: eine Stille, die bis zum Videoende reicht, und ein
 * Schwarzbild am Ende. Beides ist zuverlaessig erkennbar. Ein Outro mit
 * durchlaufender Musik und Bild ist es nicht - dafuer gibt es die feste
 * Angabe ueber --outro.
 */
final class OutroDetect {

    private static final Pattern SILENCE_START =
            Pattern.compile("silence_start:\\s*([0-9.]+)");
    private static final Pattern BLACK_START =
            Pattern.compile("black_start:([0-9.]+)\\s+black_end:([0-9.]+)");

    /** Wie weit vom Ende her gesucht wird. */
    private static final double SEARCH = 120.0;

    /** Kuerzestes Outro, das noch als solches zaehlt. */
    private static final double MIN_OUTRO = 2.0;

    private OutroDetect() {
    }

    /**
     * @return Zeitpunkt, ab dem das Outro laeuft, oder leer wenn keins erkannt wurde
     */
    static OptionalDouble find(Path media, double duration) {
        double from = Math.max(0, duration - SEARCH);

        OptionalDouble silence = trailingSilence(media, from, duration);
        OptionalDouble black = trailingBlack(media, from, duration);

        // Der frueheste plausible Punkt gewinnt: faengt das Schwarzbild vor
        // der Stille an, gehoert schon das Bild zum Abspann.
        if (silence.isPresent() && black.isPresent()) {
            return OptionalDouble.of(Math.min(silence.getAsDouble(), black.getAsDouble()));
        }
        if (silence.isPresent()) {
            return silence;
        }
        return black;
    }

    /** Stille, die bis zum Ende durchlaeuft. */
    private static OptionalDouble trailingSilence(Path media, double from, double duration) {
        String out = analyse(media, from, "silencedetect=noise=-45dB:d=1.5", true);
        if (out == null) {
            return OptionalDouble.empty();
        }

        // Ein silence_start ohne folgendes silence_end reicht bis zum Ende.
        double last = -1;
        boolean closed = true;
        for (String line : out.split("\\R")) {
            Matcher m = SILENCE_START.matcher(line);
            if (m.find()) {
                last = Double.parseDouble(m.group(1));
                closed = false;
            } else if (line.contains("silence_end")) {
                closed = true;
            }
        }

        if (closed || last < 0 || duration - last < MIN_OUTRO) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(last);
    }

    /** Schwarzbild, das bis zum Ende durchlaeuft. */
    private static OptionalDouble trailingBlack(Path media, double from, double duration) {
        String out = analyse(media, from, "blackdetect=d=1.0:pix_th=0.10", false);
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

    private static String analyse(Path media, double from, String filter, boolean audio) {
        try {
            return Proc.captureCombined(List.of(
                    "ffmpeg", "-hide_banner", "-nostats",
                    "-ss", fmt(from),
                    "-copyts",
                    "-i", media.toString(),
                    audio ? "-af" : "-vf", filter,
                    audio ? "-vn" : "-an",
                    "-f", "null", "-"));
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    private static String fmt(double seconds) {
        return String.format(Locale.ROOT, "%.3f", seconds);
    }
}
