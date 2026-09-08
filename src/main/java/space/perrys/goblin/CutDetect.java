package space.perrys.goblin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the actual cut point near a timestamp.
 *
 * Timestamps in YouTube descriptions are typed by hand and tend to be a second
 * or two off. Instead of measuring every single one, TheGoblin looks inside a
 * window around the timestamp for the point where the picture actually changes.
 *
 * Two signals, in this order:
 * 1. Black frame - almost always present at episode transitions. The cut
 *    belongs at the end of the black stretch, that is where the new picture
 *    starts.
 * 2. Hard scene change, if no black frame is found.
 */
final class CutDetect {

    private static final Pattern BLACK = Pattern.compile(
            "black_start:([0-9.]+)\\s+black_end:([0-9.]+)");

    private static final Pattern PTS = Pattern.compile("pts_time:([0-9.]+)");
    private static final Pattern SCORE = Pattern.compile("lavfi\\.scene_score=([0-9.]+)");

    /** How much the picture has to change to count as a scene change. */
    private static final double SCENE_THRESHOLD = 0.2;

    /** Shortest black stretch that still counts as a transition. */
    private static final double MIN_BLACK = 0.04;

    /** Where the cut that was found came from - for the output only. */
    enum Source { BLACK, SCENE, NONE }

    record Result(double time, Source source) {
    }

    private CutDetect() {
    }

    /**
     * @param window half the window width in seconds. The search runs over
     *               [target - window, target + window].
     * @return the cut point that was found, or the unchanged target value if
     *         nothing could be found
     */
    static Result nearest(Path video, double target, double window) {
        double from = Math.max(0, target - window);
        double length = window * 2;

        OptionalDouble black = black(video, from, length, target);
        if (black.isPresent()) {
            return new Result(black.getAsDouble(), Source.BLACK);
        }

        OptionalDouble scene = scene(video, from, length, target);
        if (scene.isPresent()) {
            return new Result(scene.getAsDouble(), Source.SCENE);
        }

        return new Result(target, Source.NONE);
    }

    /** End of the black stretch closest to the target value. */
    private static OptionalDouble black(Path video, double from, double length, double target) {
        String out = analyse(video, from, length, "blackdetect=d=" + fmt(MIN_BLACK) + ":pix_th=0.10");
        if (out == null) {
            return OptionalDouble.empty();
        }

        double best = Double.NaN;
        double bestDistance = Double.MAX_VALUE;

        Matcher m = BLACK.matcher(out);
        while (m.find()) {
            double end = Double.parseDouble(m.group(2));
            double distance = Math.abs(end - target);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = end;
            }
        }
        return Double.isNaN(best) ? OptionalDouble.empty() : OptionalDouble.of(best);
    }

    /** Scene change closest to the target value. */
    private static OptionalDouble scene(Path video, double from, double length, double target) {
        String out = analyse(video, from, length,
                "select='gt(scene," + fmt(SCENE_THRESHOLD) + ")',metadata=print:file=-");
        if (out == null) {
            return OptionalDouble.empty();
        }

        List<Double> times = new ArrayList<>();
        Double pending = null;

        for (String line : out.split("\\R")) {
            Matcher p = PTS.matcher(line);
            if (p.find()) {
                pending = Double.valueOf(p.group(1));
                continue;
            }
            if (pending != null && SCORE.matcher(line).find()) {
                times.add(pending);
                pending = null;
            }
        }

        return times.stream()
                .mapToDouble(Double::doubleValue)
                .boxed()
                .min((a, b) -> Double.compare(Math.abs(a - target), Math.abs(b - target)))
                .map(OptionalDouble::of)
                .orElse(OptionalDouble.empty());
    }

    /**
     * Lets ffmpeg analyse the window without writing anything.
     * -copyts makes sure the reported times are those of the original and do
     * not start at zero.
     */
    private static String analyse(Path video, double from, double length, String filter) {
        try {
            return Proc.captureCombined(List.of(
                    "ffmpeg", "-hide_banner", "-nostats",
                    "-ss", fmt(from),
                    "-t", fmt(length),
                    "-copyts",
                    "-i", video.toString(),
                    "-vf", filter,
                    "-an", "-f", "null", "-"));
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
