package space.perrys.goblin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Duenne Huelle um yt-dlp. */
final class YtDlp {

    /**
     * Bevorzugt H.264 mit AAC in mp4. Das spielt so gut wie jeder Client direkt ab,
     * waehrend YouTubes Standard (VP9 oder AV1 in webm) den Server zum Transcodieren
     * zwingt.
     */
    private static final String FORMAT =
            "bv*[vcodec^=avc1][ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b";

    private YtDlp() {
    }

    static VideoMeta metadata(String url) throws IOException, InterruptedException {
        String json = Proc.capture(List.of(
                "yt-dlp", "--no-warnings", "--no-playlist", "--skip-download", "-J", url));

        Map<String, Object> root = Json.object(Json.parse(json));

        List<Chapter> chapters = new ArrayList<>();
        for (Object o : Json.array(root.get("chapters"))) {
            Map<String, Object> c = Json.object(o);
            double start = Json.num(c, "start_time", -1);
            double end = Json.num(c, "end_time", -1);
            String title = Json.str(c, "title");
            if (start >= 0 && end > start && title != null && !title.isBlank()) {
                chapters.add(new Chapter(start, end, title.strip()));
            }
        }

        return new VideoMeta(
                Json.str(root, "id"),
                Json.str(root, "title"),
                Json.str(root, "description"),
                Json.num(root, "duration", 0),
                List.copyOf(chapters));
    }

    /** Laedt das komplette Video nach {@code target} (ohne Endung, yt-dlp haengt sie an). */
    static Path download(String url, Path targetWithoutExtension) throws IOException, InterruptedException {
        Proc.inherit(List.of(
                "yt-dlp",
                "--no-playlist",
                "--no-warnings",
                "-f", FORMAT,
                "--merge-output-format", "mp4",
                "-o", targetWithoutExtension + ".%(ext)s",
                url));

        Path mp4 = Path.of(targetWithoutExtension + ".mp4");
        if (java.nio.file.Files.exists(mp4)) {
            return mp4;
        }

        // Fallback: yt-dlp konnte nicht nach mp4 muxen
        Path dir = targetWithoutExtension.getParent();
        String prefix = targetWithoutExtension.getFileName().toString();
        try (var stream = java.nio.file.Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().startsWith(prefix))
                    .findFirst()
                    .orElseThrow(() -> new IOException("Download nicht gefunden: " + targetWithoutExtension));
        }
    }
}
