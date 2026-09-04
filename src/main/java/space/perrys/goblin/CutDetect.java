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
 * Sucht die tatsaechliche Schnittstelle in der Naehe eines Zeitstempels.
 *
 * Zeitstempel in YouTube-Beschreibungen sind von Hand getippt und liegen gern
 * ein bis zwei Sekunden daneben. Statt jeden einzeln nachzumessen, schaut
 * TheGoblin in einem Fenster um den Zeitstempel herum nach, wo das Bild
 * tatsaechlich wechselt.
 *
 * Zwei Signale, in dieser Reihenfolge:
 * 1. Schwarzbild - bei Episodenuebergaengen fast immer vorhanden. Der Schnitt
 *    gehoert ans Ende des schwarzen Abschnitts, dort faengt das neue Bild an.
 * 2. Harter Szenenwechsel, falls kein Schwarzbild gefunden wird.
 */
final class CutDetect {

    private static final Pattern BLACK = Pattern.compile(
            "black_start:([0-9.]+)\\s+black_end:([0-9.]+)");

    private static final Pattern PTS = Pattern.compile("pts_time:([0-9.]+)");
    private static final Pattern SCORE = Pattern.compile("lavfi\\.scene_score=([0-9.]+)");

    /** Wie stark sich das Bild aendern muss, damit es als Szenenwechsel zaehlt. */
    private static final double SCENE_THRESHOLD = 0.2;

    /** Kuerzestes Schwarzbild, das noch als Uebergang gilt. */
    private static final double MIN_BLACK = 0.04;

    /** Woher der gefundene Schnitt stammt - nur fuer die Ausgabe. */
    enum Source { BLACK, SCENE, NONE }

    record Result(double time, Source source) {
    }

    private CutDetect() {
    }

    /**
     * @param window Halbe Fensterbreite in Sekunden. Gesucht wird in
     *               [target - window, target + window].
     * @return der gefundene Schnittzeitpunkt, oder der unveraenderte Zielwert,
     *         wenn sich nichts finden liess
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

    /** Ende des Schwarzbilds, das dem Zielwert am naechsten liegt. */
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

    /** Szenenwechsel, der dem Zielwert am naechsten liegt. */
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
     * Laesst ffmpeg das Fenster analysieren, ohne etwas zu schreiben.
     * -copyts sorgt dafuer, dass die gemeldeten Zeiten die des Originals sind
     * und nicht bei null anfangen.
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
