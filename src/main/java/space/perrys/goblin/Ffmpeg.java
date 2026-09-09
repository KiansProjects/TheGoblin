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

    /**
     * Writes metadata into an existing file without re-encoding anything.
     * ffmpeg cannot edit in place, so it writes a sibling and moves it over.
     * {@code -map 0} keeps every stream, embedded cover art included.
     */
    static void tag(Path file, java.util.Map<String, String> tags)
            throws IOException, InterruptedException {

        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = (dot < 0) ? "" : name.substring(dot);
        Path tmp = file.resolveSibling(name + ".tagging" + ext);

        List<String> cmd = new ArrayList<>(List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-i", file.toString(),
                "-map", "0", "-c", "copy"));

        for (var entry : tags.entrySet()) {
            cmd.add("-metadata");
            cmd.add(entry.getKey() + "=" + entry.getValue());
        }
        cmd.add(tmp.toString());

        try {
            Proc.inherit(cmd);
            java.nio.file.Files.move(tmp, file,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            java.nio.file.Files.deleteIfExists(tmp);
        }
    }

    /**
     * Reduces a file to the stretch from {@code start} over {@code seconds}.
     *
     * Copies the streams, so nothing is re-encoded and a second lossy
     * generation is avoided. The cut therefore snaps to a frame boundary,
     * which for audio is a matter of milliseconds. Like {@link #tag}, it
     * writes a sibling and moves it over.
     */
    static void trim(Path file, double start, double seconds)
            throws IOException, InterruptedException {

        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = (dot < 0) ? "" : name.substring(dot);
        Path tmp = file.resolveSibling(name + ".trimming" + ext);

        List<String> cmd = new ArrayList<>(List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y"));
        if (start > 0) {
            cmd.addAll(List.of("-ss", fmt(start)));
        }
        cmd.addAll(List.of(
                "-i", file.toString(),
                "-t", fmt(seconds),
                "-map", "0", "-c", "copy"));
        if (start > 0) {
            // Seeking with a stream copy leaves the first packet at a negative
            // timestamp; without this the player would show the old offset.
            cmd.addAll(List.of("-avoid_negative_ts", "make_zero"));
        }
        cmd.add(tmp.toString());

        try {
            Proc.inherit(cmd);
            java.nio.file.Files.move(tmp, file,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            java.nio.file.Files.deleteIfExists(tmp);
        }
    }

    private static String fmt(double seconds) {
        return String.format(Locale.ROOT, "%.3f", seconds);
    }
}
