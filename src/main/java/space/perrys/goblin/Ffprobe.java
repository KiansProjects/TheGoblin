package space.perrys.goblin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Small queries against ffprobe. */
final class Ffprobe {

    private Ffprobe() {
    }

    /** Runtime in seconds, 0 if it cannot be determined. */
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
