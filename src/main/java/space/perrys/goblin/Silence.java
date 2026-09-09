package space.perrys.goblin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds silence at the start and at the end of a file.
 *
 * Both ends matter for different reasons: at the end it marks where the content
 * stops and the credits begin, at the start it is the dead air a rip picks up
 * before the music comes in. The mechanism is the same either way, so it lives
 * here once rather than in {@link OutroDetect} and {@link Trim} separately.
 */
final class Silence {

    private static final Pattern START = Pattern.compile("silence_start:\\s*(-?[0-9.]+)");
    private static final Pattern END = Pattern.compile("silence_end:\\s*([0-9.]+)");

    /** How far back from the end the search runs. */
    static final double SEARCH = 120.0;

    /** Shortest stretch at the end that still counts. */
    static final double MIN_TAIL = 2.0;

    /**
     * Anything shorter at the start is the natural gap before the first beat,
     * not dead air worth cutting.
     */
    static final double MIN_LEAD = 0.5;

    private Silence() {
    }

    /**
     * Silence that runs through to the end of the file.
     *
     * @return the point it starts at, or empty when the file ends on content
     */
    static OptionalDouble trailing(Path media, double from, double duration) {
        String out = analyse(media, from, 1.5, true);
        if (out == null) {
            return OptionalDouble.empty();
        }

        // A silence_start without a following silence_end reaches the end.
        double last = -1;
        boolean closed = true;
        for (String line : out.split("\\R")) {
            Matcher m = START.matcher(line);
            if (m.find()) {
                last = Double.parseDouble(m.group(1));
                closed = false;
            } else if (line.contains("silence_end")) {
                closed = true;
            }
        }

        if (closed || last < 0 || duration - last < MIN_TAIL) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(last);
    }

    /**
     * Silence the file opens with.
     *
     * Only counts when it begins at the very start; a quiet passage a few
     * seconds in is part of the music.
     *
     * @return the point the audio comes in at, or empty when it starts right away
     */
    static OptionalDouble leading(Path media) {
        String out = analyse(media, 0, 0.3, false);
        if (out == null) {
            return OptionalDouble.empty();
        }

        boolean opensSilent = false;
        for (String line : out.split("\\R")) {
            Matcher start = START.matcher(line);
            if (start.find()) {
                if (opensSilent) {
                    // A second stretch begins, so the first one was closed and
                    // the file already had audio. Nothing more to find here.
                    break;
                }
                // ffmpeg reports the first stretch at or slightly before zero.
                opensSilent = Double.parseDouble(start.group(1)) <= 0.05;
                if (!opensSilent) {
                    break;
                }
            }

            Matcher end = END.matcher(line);
            if (opensSilent && end.find()) {
                double at = Double.parseDouble(end.group(1));
                return at >= MIN_LEAD ? OptionalDouble.of(at) : OptionalDouble.empty();
            }
        }
        return OptionalDouble.empty();
    }

    /**
     * @param minimum shortest stretch ffmpeg should report, in seconds
     * @param seek    true starts the analysis at {@code from} instead of at the
     *                beginning - worth it for a tail, pointless for a head
     */
    private static String analyse(Path media, double from, double minimum, boolean seek) {
        try {
            List<String> cmd = new java.util.ArrayList<>(List.of(
                    "ffmpeg", "-hide_banner", "-nostats"));
            if (seek) {
                cmd.addAll(List.of("-ss", fmt(from), "-copyts"));
            }
            cmd.addAll(List.of(
                    "-i", media.toString(),
                    "-af", "silencedetect=noise=-45dB:d=" + fmt(minimum),
                    "-vn",
                    "-f", "null", "-"));
            return Proc.captureCombined(cmd);
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    private static String fmt(double seconds) {
        return String.format(Locale.ROOT, "%.3f", seconds);
    }
}
