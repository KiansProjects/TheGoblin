package space.perrys.goblin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Kleine Abfragen an ffprobe. */
final class Ffprobe {

    private Ffprobe() {
    }

    /** Laufzeit in Sekunden, 0 wenn sie sich nicht ermitteln laesst. */
    static double duration(Path media) {
        try {
            String out = Proc.capture(List.of(
                    "ffprobe", "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    media.toString()));
            return Double.parseDouble(out.strip());
        } catch (IOException | InterruptedException | NumberFormatException e) {
            return 0;
        }
    }
}
