package space.perrys.goblin;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
    static final String FORMAT_H264 =
            "bv*[vcodec^=avc1][ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b";

    /** Beste verfuegbare Qualitaet, egal welcher Codec. Kann VP9 oder AV1 sein. */
    static final String FORMAT_BEST = "bv*+ba/b";

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
        // Kapitel stehen in den Metadaten, nicht in den Streams. Ohne dieses Flag
        // bricht yt-dlp ab, sobald YouTube keine brauchbaren Format-URLs liefert
        // (SABR, fehlende PO-Tokens) - obwohl alles Noetige schon da waere.
        cmd.addAll(List.of("--ignore-no-formats-error", "--skip-download", "-J", url));

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

    /**
     * Liest eine Playlist, ohne jedes Video einzeln aufzurufen.
     * Liefert Paare aus Video-ID und Titel in der Reihenfolge der Playlist.
     */
    static List<String[]> playlist(String url) throws IOException, InterruptedException {
        // Erst yt-dlp fragen - nur so bekommen wir die Titel, an denen sich
        // Staffelgrenzen ablesen lassen. Die IDs aus dem Link sind der
        // Rueckfall, falls YouTube die Playlist nicht aufloest.
        try {
            List<String[]> resolved = viaYtDlp(url);
            if (!resolved.isEmpty()) {
                return resolved;
            }
        } catch (IOException e) {
            System.out.println("Playlist liess sich nicht aufloesen, nutze die IDs aus dem Link.");
        }

        List<String[]> direct = idsFromUrl(url);
        if (!direct.isEmpty()) {
            System.out.println("Hinweis: ohne Titel, die Reihenfolge stammt aus dem Link.");
        }
        return direct;
    }

    private static List<String[]> viaYtDlp(String url) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(
                "yt-dlp", "--no-warnings", "--flat-playlist", "-J"));
        cmd.addAll(tokenize(System.getenv("YTDLP_ARGS")));
        cmd.add(url);

        Map<String, Object> root = Json.object(Json.parse(Proc.capture(cmd)));

        List<String[]> out = new ArrayList<>();
        for (Object o : Json.array(root.get("entries"))) {
            Map<String, Object> e = Json.object(o);
            String id = Json.str(e, "id");
            String title = Json.str(e, "title");
            if (id != null) {
                out.add(new String[] {id, title == null ? "" : title});
            }
        }
        return out;
    }

    /**
     * Anonyme Playlists der Form watch_videos?video_ids=a,b,c tragen die IDs
     * schon im Link. Die lesen wir direkt aus - das ist verlaesslicher als die
     * Aufloesung ueber YouTube und behaelt die Reihenfolge garantiert bei.
     */
    static List<String[]> idsFromUrl(String url) {
        int at = url.indexOf("video_ids=");
        if (at < 0) {
            return List.of();
        }

        String raw = url.substring(at + "video_ids=".length());
        int end = raw.indexOf('&');
        if (end >= 0) {
            raw = raw.substring(0, end);
        }
        raw = URLDecoder.decode(raw, StandardCharsets.UTF_8);

        List<String[]> out = new ArrayList<>();
        for (String id : raw.split(",")) {
            String trimmed = id.strip();
            if (!trimmed.isEmpty()) {
                out.add(new String[] {trimmed, ""});
            }
        }
        return out;
    }

    /**
     * Titel und Kanal einer Playlist, ohne die Eintraege einzeln abzurufen.
     *
     * @return {Titel, Kanal}, Felder koennen leer sein
     */
    static String[] playlistMeta(String url) {
        List<String> cmd = new ArrayList<>(List.of(
                "yt-dlp", "--no-warnings", "--flat-playlist", "--playlist-items", "0", "-J"));
        cmd.addAll(tokenize(System.getenv("YTDLP_ARGS")));
        cmd.add(url);

        try {
            Map<String, Object> root = Json.object(Json.parse(Proc.capture(cmd)));
            String title = Json.str(root, "title");
            String uploader = Json.str(root, "uploader");
            if (uploader == null) {
                uploader = Json.str(root, "channel");
            }
            return new String[] {title == null ? "" : title, uploader == null ? "" : uploader};
        } catch (IOException | InterruptedException | RuntimeException e) {
            return new String[] {"", ""};
        }
    }

    /**
     * Zieht die Tonspur heraus.
     *
     * Die Umwandlung uebernimmt yt-dlp mit ffmpeg, samt Tags und Titelbild.
     *
     * @param format mp3, flac, wav, opus, m4a - oder "best" fuer die
     *               Originalspur ohne Neukodierung
     * @param outputTemplate Ausgabemuster im yt-dlp-Format
     * @param splitChapters true legt je Kapitel eine eigene Datei an
     */
    static void audio(String url, String format, int quality, String outputTemplate,
                      boolean splitChapters) throws IOException, InterruptedException {

        List<String> cmd = base();
        cmd.addAll(List.of("--extract-audio", "--embed-metadata", "--embed-thumbnail"));

        if (!"best".equalsIgnoreCase(format)) {
            cmd.addAll(List.of("--audio-format", format,
                    "--audio-quality", String.valueOf(quality)));
        }

        if (splitChapters) {
            cmd.add("--split-chapters");
            cmd.addAll(List.of("-o", "chapter:" + outputTemplate));
            // Die ungeteilte Datei nicht behalten
            cmd.addAll(List.of("-o", "pl_video:" + System.getProperty("java.io.tmpdir")
                    + "/goblin-full-%(id)s.%(ext)s"));
        } else {
            cmd.addAll(List.of("-o", outputTemplate));
        }

        cmd.add(url);
        Proc.inherit(cmd);
    }

    /** Zeigt alle verfuegbaren Formate des Videos an. */
    static void listFormats(String url) throws IOException, InterruptedException {
        List<String> cmd = base();
        cmd.addAll(List.of("--ignore-no-formats-error", "-F", url));
        Proc.inherit(cmd);
    }

    /** Laedt das komplette Video nach {@code target} (ohne Endung, yt-dlp haengt sie an). */
    static Path download(String url, Path targetWithoutExtension, String format, String container)
            throws IOException, InterruptedException {
        List<String> cmd = base();
        cmd.addAll(List.of(
                "-f", format,
                "--merge-output-format", container,
                "-o", targetWithoutExtension + ".%(ext)s",
                url));

        Proc.inherit(cmd);

        Path merged = Path.of(targetWithoutExtension + "." + container);
        if (Files.exists(merged)) {
            return merged;
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
        return base(false, false);
    }

    private static List<String> base(boolean verbose) {
        return base(verbose, true);
    }

    private static List<String> base(boolean verbose, boolean singleVideo) {
        List<String> cmd = new ArrayList<>(List.of("yt-dlp"));
        if (singleVideo) {
            cmd.add("--no-playlist");
        }
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
