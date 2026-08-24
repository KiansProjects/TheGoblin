package space.perrys.goblin;

import java.io.IOException;
import java.nio.file.Files;
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

    /** Wird automatisch benutzt, wenn die Datei im Arbeitsverzeichnis liegt. */
    private static final Path COOKIES = Path.of("cookies.txt");

    private YtDlp() {
    }

    static VideoMeta metadata(String url) throws IOException, InterruptedException {
        return metadata(url, false);
    }

    /**
     * @param verbose druckt das vollstaendige yt-dlp-Kommando und reicht dessen
     *                Meldungen durch. Ohne das schluckt --no-warnings genau die
     *                Zeilen, die bei einer Fehlersuche interessant sind.
     */
    static VideoMeta metadata(String url, boolean verbose) throws IOException, InterruptedException {
        List<String> cmd = base(verbose);
        cmd.addAll(List.of("--skip-download", "-J", url));

        if (verbose) {
            System.out.println("$ " + String.join(" ", cmd));
        }

        Map<String, Object> root = Json.object(Json.parse(Proc.capture(cmd, verbose)));

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
        List<String> cmd = base();
        cmd.addAll(List.of(
                "-f", FORMAT,
                "--merge-output-format", "mp4",
                "-o", targetWithoutExtension + ".%(ext)s",
                url));

        Proc.inherit(cmd);

        Path mp4 = Path.of(targetWithoutExtension + ".mp4");
        if (Files.exists(mp4)) {
            return mp4;
        }

        // Fallback: yt-dlp konnte nicht nach mp4 muxen
        Path dir = targetWithoutExtension.getParent();
        String prefix = targetWithoutExtension.getFileName().toString();
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().startsWith(prefix))
                    .findFirst()
                    .orElseThrow(() -> new IOException("Download nicht gefunden: " + targetWithoutExtension));
        }
    }

    /**
     * Grundkommando inklusive der Optionen aus der Umgebungsvariable YTDLP_ARGS
     * und einer eventuell vorhandenen cookies.txt. Damit lassen sich Videos holen,
     * die einen angemeldeten Client oder einen bestimmten Player verlangen, ohne
     * dass dafuer der Code angefasst werden muss.
     */
    private static List<String> base() {
        return base(false);
    }

    private static List<String> base(boolean verbose) {
        List<String> cmd = new ArrayList<>(List.of("yt-dlp", "--no-playlist"));
        cmd.add(verbose ? "-v" : "--no-warnings");

        if (Files.isReadable(COOKIES)) {
            cmd.add("--cookies");
            cmd.add(COOKIES.toString());
        }

        cmd.addAll(tokenize(System.getenv("YTDLP_ARGS")));
        return cmd;
    }

    /**
     * Zerlegt eine Optionszeile wie auf der Kommandozeile. Beruecksichtigt
     * Anfuehrungszeichen, damit Werte mit Sonderzeichen heil ankommen:
     * --extractor-args "youtube:player_client=web_safari,default"
     */
    static List<String> tokenize(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }

        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean started = false;

        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);

            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
                continue;
            }

            if (c == '"' || c == '\'') {
                quote = c;
                started = true;
            } else if (Character.isWhitespace(c)) {
                if (started) {
                    out.add(current.toString());
                    current.setLength(0);
                    started = false;
                }
            } else {
                current.append(c);
                started = true;
            }
        }

        if (started) {
            out.add(current.toString());
        }
        return out;
    }
}
