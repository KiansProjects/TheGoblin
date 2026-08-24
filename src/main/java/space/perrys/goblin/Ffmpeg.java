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
     * @param reencode false schneidet ohne Neukodierung (Sekunden, aber die Schnitte
     *                 rasten auf den naechsten Keyframe ein). true trifft die Zeit
     *                 exakt, kostet dafuer CPU-Zeit und minimal Qualitaet.
     */
    static void cut(Path input, Chapter chapter, Path output, boolean reencode)
            throws IOException, InterruptedException {

        List<String> cmd = new ArrayList<>(List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-ss", fmt(chapter.start()),
                "-i", input.toString(),
                "-t", fmt(chapter.duration())));

        if (reencode) {
            cmd.addAll(List.of(
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
                    "-c:a", "aac", "-b:a", "192k"));
        } else {
            cmd.addAll(List.of("-c", "copy", "-avoid_negative_ts", "make_zero"));
        }

        cmd.addAll(List.of("-movflags", "+faststart", output.toString()));
        Proc.inherit(cmd);
    }

    private static String fmt(double seconds) {
        return String.format(Locale.ROOT, "%.3f", seconds);
    }
}
