package space.perrys.goblin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Cuts sections out of the downloaded video. */
final class Ffmpeg {

    private Ffmpeg() {
    }

    /**
     * @param reencode false cuts without re-encoding. Fast, but the start snaps
     *                 to the preceding keyframe - the section ends up longer
     *                 than requested, by up to one keyframe distance. true hits
     *                 the time frame-accurately, at the cost of processing time
     *                 and a little quality.
     */
    static void cut(Path input, Chapter chapter, Path output, boolean reencode)
            throws IOException, InterruptedException {

        List<String> cmd = new ArrayList<>(List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-ss", fmt(chapter.start()),
                "-i", input.toString(),
                "-t", fmt(chapter.duration())));

        if (reencode) {
            // Re-encode the picture only. The audio can be copied frame-accurately,
            // a second AAC generation would be pure quality loss.
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
     * Joins finished sections together. The sections must share the same
     * encoding parameters - going through cut(..., reencode=true) guarantees
     * that, so copying the streams is enough here.
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
