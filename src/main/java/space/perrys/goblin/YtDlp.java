package space.perrys.goblin;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Thin wrapper around yt-dlp. */
final class YtDlp {

    /**
     * Prefers H.264 with AAC in mp4. Just about every client plays that
     * directly, whereas YouTube's default (VP9 or AV1 in webm) forces the
     * server to transcode.
     */
    static final String FORMAT_H264 =
            "bv*[vcodec^=avc1][ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b";

    /** Best available quality, whatever the codec. Can be VP9 or AV1. */
    static final String FORMAT_BEST = "bv*+ba/b";

    /** Used automatically when the file sits in the working directory. */
    private static final Path COOKIES = Path.of("cookies.txt");

    private YtDlp() {
    }

    static VideoMeta metadata(String url) throws IOException, InterruptedException {
        return metadata(url, false);
    }

    /**
     * @param verbose prints the full yt-dlp command and passes its messages
     *                through. Without it, --no-warnings swallows exactly the
     *                lines that matter when troubleshooting.
     */
    static VideoMeta metadata(String url, boolean verbose) throws IOException, InterruptedException {
        List<String> cmd = base(verbose);
        // Chapters live in the metadata, not in the streams. Without this flag
        // yt-dlp gives up as soon as YouTube serves no usable format URLs
        // (SABR, missing PO tokens) - even though everything needed is there.
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
     * Reads a playlist without calling up every video individually.
     * Returns pairs of video ID and title in playlist order.
     */
    static List<String[]> playlist(String url) throws IOException, InterruptedException {
        // Ask yt-dlp first - only that way do we get the titles that season
        // boundaries can be read from. The IDs in the link are the fallback,
        // in case YouTube does not resolve the playlist.
        try {
            List<String[]> resolved = viaYtDlp(url);
            if (!resolved.isEmpty()) {
                return resolved;
            }
        } catch (IOException e) {
            System.out.println("Could not resolve the playlist, using the IDs from the link.");
        }

        List<String[]> direct = idsFromUrl(url);
        if (!direct.isEmpty()) {
            System.out.println("Note: no titles, the order comes from the link.");
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
     * Anonymous playlists of the form watch_videos?video_ids=a,b,c already
     * carry the IDs in the link. We read those out directly - that is more
     * reliable than resolving through YouTube and keeps the order guaranteed.
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
     * Title and channel of a playlist, without fetching the entries one by one.
     *
     * @return {title, channel}, fields can be empty
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
     * Extracts the audio track.
     *
     * The conversion is done by yt-dlp with ffmpeg, tags and cover art included.
     *
     * @param format mp3, flac, wav, opus, m4a - or "best" for the original
     *               track without re-encoding
     * @param outputTemplate output pattern in yt-dlp format
     * @param splitChapters true writes one file per chapter
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
            // Do not keep the unsplit file
            cmd.addAll(List.of("-o", "pl_video:" + System.getProperty("java.io.tmpdir")
                    + "/goblin-full-%(id)s.%(ext)s"));
        } else {
            cmd.addAll(List.of("-o", outputTemplate));
        }

        cmd.add(url);
        Proc.inherit(cmd);
    }

    /** Lists every format available for the video. */
    static void listFormats(String url) throws IOException, InterruptedException {
        List<String> cmd = base();
        cmd.addAll(List.of("--ignore-no-formats-error", "-F", url));
        Proc.inherit(cmd);
    }

    /** Downloads the whole video to {@code target} (without extension, yt-dlp appends it). */
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

        // Fallback: yt-dlp could not mux to mp4
        Path dir = targetWithoutExtension.getParent();
        String prefix = targetWithoutExtension.getFileName().toString();
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().startsWith(prefix))
                    .findFirst()
                    .orElseThrow(() -> new IOException("Download not found: " + targetWithoutExtension));
        }
    }

    /**
     * Base command including the options from the environment variable
     * YTDLP_ARGS and a cookies.txt if one is present. That makes it possible to
     * fetch videos which require a signed-in client or a particular player,
     * without touching the code.
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

        Limits.addRateLimit(cmd);
        cmd.addAll(tokenize(System.getenv("YTDLP_ARGS")));
        return cmd;
    }

    /**
     * Splits an option line the way a command line would. Honours quotes so
     * that values with special characters arrive intact:
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
