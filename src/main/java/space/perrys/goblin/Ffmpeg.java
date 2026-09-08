package space.perrys.goblin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Schneidet Abschnitte aus dem heruntergeladenen Video. */
final class Ffmpeg {

    private Ffmpeg() {
    }

    /**
     * @param reencode false schneidet ohne Neukodierung. Schnell, aber der Start
     *                 rastet auf den vorhergehenden Keyframe ein - der Abschnitt
     *                 wird dadurch bis zu eine Keyframe-Distanz laenger als
     *                 angegeben. true trifft die Zeit framegenau, kostet dafuer
     *                 Rechenzeit und minimal Qualitaet.
     */
    static void cut(Path input, Chapter chapter, Path output, boolean reencode)
            throws IOException, InterruptedException {

        List<String> cmd = new ArrayList<>(List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-ss", fmt(chapter.start()),
                "-i", input.toString(),
                "-t", fmt(chapter.duration())));

        if (reencode) {
            // Nur das Bild neu kodieren. Der Ton laesst sich framegenau kopieren,
            // eine zweite AAC-Generation waere reiner Qualitaetsverlust.
            cmd.addAll(List.of(
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
                    "-c:a", "copy"));
        } else {
            cmd.addAll(List.of("-c", "copy", "-avoid_negative_ts", "make_zero"));
        }

        cmd.addAll(List.of("-movflags", "+faststart", output.toString()));
        Proc.inherit(cmd);
    }

    /**
     * Haengt fertige Abschnitte aneinander. Die Abschnitte muessen dieselben
     * Kodierparameter haben - beim Weg ueber cut(..., reencode=true) ist das
     * gegeben, deshalb reicht hier das blosse Kopieren der Streams.
     */
    static void concat(List<Path> segments, Path listFile, Path output)
            throws IOException, InterruptedException {

        StringBuilder list = new StringBuilder();
        for (Path segment : segments) {
            list.append("file '").append(segment.toAbsolutePath()).append("'\n");
        }
        java.nio.file.Files.writeString(listFile, list.toString());

        Proc.inherit(List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "concat", "-safe", "0",
                "-i", listFile.toString(),
                "-c", "copy",
                "-movflags", "+faststart",
                output.toString()));
    }

    private static String fmt(double seconds) {
        return String.format(Locale.ROOT, "%.3f", seconds);
    }
}
