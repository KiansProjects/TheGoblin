package space.perrys.goblin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * TheGoblin - zerlegt ein YouTube-Video anhand seiner Kapitel in einzelne
 * Episodendateien und legt sie so ab, wie Jellyfin sie erwartet.
 */
public final class Goblin {

    private static final String USAGE = """
            TheGoblin

            Befehle:
              goblin series <url> <name> [optionen]   Video in Episoden zerlegen
              goblin chapters <url> [--verbose]       nur die erkannten Kapitel anzeigen
              goblin chapters <url> --formats         verfuegbare Formate auflisten

            Optionen fuer 'series':
              -o, --out <pfad>        Zielverzeichnis (Standard: aktuelles Verzeichnis)
              -s, --season <n>        Staffelnummer (Standard: 1)
              -e, --start-episode <n> Nummer der ersten Episode (Standard: 1)
                  --year <jahr>       Erscheinungsjahr, ueberschreibt TMDb
                  --tmdb-id <id>      TMDb-ID fest vorgeben statt zu suchen
                  --no-tmdb           weder ID noch Artwork holen
                  --best              beste Qualitaet statt H.264, landet in mkv
              -f, --format <sel>      eigener yt-dlp-Formatselektor
                  --container <ext>   Zielcontainer, Standard mp4
                  --chapters <datei>  eigene Zeitstempel statt der aus dem Video
                  --offset <sekunden> alle Grenzen ab der zweiten verschieben
                  --snap [sekunden]   Grenzen auf den echten Bildwechsel ziehen
                                      (Suchfenster, Standard 5)
                  --reencode          exakt schneiden statt auf Keyframes zu runden
                  --keep              das komplette Video nach dem Schneiden behalten
                  --dry-run           nur zeigen, was passieren wuerde
              -v, --verbose           yt-dlp-Kommando und dessen Meldungen zeigen

            Umgebung:
              TMDB_API_KEY            fuer Serien-ID und Artwork (optional)
            """;

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (Exception e) {
            System.err.println("Fehler: " + e.getMessage());
            System.exit(1);
        }
    }

    private static int run(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
            System.out.print(USAGE);
            return 0;
        }

        return switch (args[0]) {
            case "series" -> series(args);
            case "chapters" -> chapters(args);
            default -> {
                System.err.println("Unbekannter Befehl: " + args[0]);
                System.err.print(USAGE);
                yield 2;
            }
        };
    }

    // ------------------------------------------------------------------
    // goblin chapters <url>
    // ------------------------------------------------------------------

    private static int chapters(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Aufruf: goblin chapters <url> [--verbose]");
            return 2;
        }
        requireTools(false);

        boolean verbose = false;
        boolean formats = false;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--verbose", "-v" -> verbose = true;
                case "--formats", "-F" -> formats = true;
                default -> { }
            }
        }

        if (formats) {
            YtDlp.listFormats(args[1]);
            return 0;
        }

        VideoMeta meta = YtDlp.metadata(args[1], verbose);
        List<Chapter> found = resolveChapters(meta);

        if (found.isEmpty()) {
            System.out.println("Keine Kapitel gefunden.");
            return 1;
        }

        System.out.println(meta.title());
        System.out.println();
        for (int i = 0; i < found.size(); i++) {
            Chapter c = found.get(i);
            System.out.printf("  %2d. %-9s %s  (%.0f s)%n",
                    i + 1, c.timecode(), c.title(), c.duration());
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // goblin series <url> <name>
    // ------------------------------------------------------------------

    private static int series(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Aufruf: goblin series <url> <name> [optionen]");
            return 2;
        }

        String url = args[1];
        String name = args[2];

        Path out = Path.of(".");
        int season = 1;
        int startEpisode = 1;
        Integer year = null;
        Integer tmdbId = null;
        boolean useTmdb = true;
        boolean reencode = false;
        boolean keep = false;
        boolean dryRun = false;
        boolean verbose = false;
        String format = YtDlp.FORMAT_H264;
        String container = "mp4";
        Path chapterFile = null;
        double offset = 0;
        double snapWindow = 0;

        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "-o", "--out" -> out = Path.of(args[++i]);
                case "-s", "--season" -> season = Integer.parseInt(args[++i]);
                case "-e", "--start-episode" -> startEpisode = Integer.parseInt(args[++i]);
                case "--year" -> year = Integer.valueOf(args[++i]);
                case "--tmdb-id" -> tmdbId = Integer.valueOf(args[++i]);
                case "--no-tmdb" -> useTmdb = false;
                case "--reencode" -> reencode = true;
                case "--keep" -> keep = true;
                case "--dry-run" -> dryRun = true;
                case "--verbose", "-v" -> verbose = true;
                case "--best" -> {
                    format = YtDlp.FORMAT_BEST;
                    container = "mkv";
                }
                case "--format", "-f" -> format = args[++i];
                case "--chapters" -> chapterFile = Path.of(args[++i]);
                case "--offset" -> offset = Double.parseDouble(args[++i]);
                case "--snap" -> snapWindow = (i + 1 < args.length && !args[i + 1].startsWith("-"))
                        ? Double.parseDouble(args[++i])
                        : 5.0;
                case "--container" -> container = args[++i];
                default -> {
                    System.err.println("Unbekannte Option: " + args[i]);
                    return 2;
                }
            }
        }

        requireTools(!dryRun);

        // 1. Metadaten und Kapitel
        System.out.println("Metadaten abrufen ...");
        VideoMeta meta = YtDlp.metadata(url, verbose);

        List<Chapter> parts;
        if (chapterFile != null) {
            parts = ChapterParser.parse(Files.readString(chapterFile), meta.duration());
            if (parts.isEmpty()) {
                System.err.println("Aus " + chapterFile + " liessen sich keine Zeitstempel lesen.");
                return 1;
            }
            System.out.println("Kapitel aus " + chapterFile);
        } else {
            parts = resolveChapters(meta);
        }

        if (offset != 0) {
            parts = shift(parts, offset, meta.duration());
            System.out.printf("Versatz: %+.1f s ab dem zweiten Abschnitt%n", offset);
        }

        if (parts.isEmpty()) {
            System.err.println("""
                    Keine Kapitel gefunden. Das Video hat weder YouTube-Kapitel noch
                    erkennbare Zeitstempel in der Beschreibung.""");
            return 1;
        }
        System.out.printf("%d Abschnitte in \"%s\"%n", parts.size(), meta.title());

        // 2. Serie in der Datenbank nachschlagen
        Tmdb.Series series = null;
        Tmdb tmdb = useTmdb ? Tmdb.fromEnvironment() : null;
        if (tmdb != null) {
            try {
                series = (tmdbId != null) ? tmdb.byId(tmdbId) : tmdb.search(name);
                if (series != null) {
                    System.out.printf("TMDb: %s (%s), ID %d%n",
                            series.name(), series.year(), series.id());
                }
            } catch (IOException e) {
                System.out.println("TMDb nicht erreichbar, mache ohne weiter: " + e.getMessage());
            }
        } else if (useTmdb) {
            System.out.println("Kein TMDB_API_KEY gesetzt, ueberspringe Artwork.");
        }

        Integer folderYear = (year != null) ? year : (series != null ? series.year() : null);
        Integer folderId = (tmdbId != null) ? tmdbId : (series != null ? series.id() : null);

        Path seriesDir = out.resolve(Naming.seriesFolder(name, folderYear, folderId));
        Path seasonDir = seriesDir.resolve(Naming.seasonFolder(season));

        // 3. Vorschau
        System.out.println();
        System.out.println(seasonDir);
        for (int i = 0; i < parts.size(); i++) {
            System.out.println("  " + Naming.episodeFile(
                    name, season, startEpisode + i, parts.get(i).title(), container));
        }
        System.out.println();

        if (dryRun) {
            System.out.println("Dry-Run, es wurde nichts geschrieben.");
            return 0;
        }

        Files.createDirectories(seasonDir);

        // 4. Video einmal komplett laden
        Path work = Files.createTempDirectory(workRoot(), "goblin-");
        Path source;
        try {
            System.out.println("Video laden ...");
            source = YtDlp.download(url, work.resolve("source"), format, container);

            // 5. Grenzen auf den tatsaechlichen Bildwechsel ziehen
            if (snapWindow > 0) {
                parts = snap(source, parts, snapWindow, meta.duration());
            }

            // 6. Schneiden
            System.out.println("Schneiden ...");
            for (int i = 0; i < parts.size(); i++) {
                Chapter c = parts.get(i);
                Path target = seasonDir.resolve(
                        Naming.episodeFile(name, season, startEpisode + i, c.title(), container));
                Ffmpeg.cut(source, c, target, reencode);
                System.out.println("  " + target.getFileName());
            }

            if (keep) {
                Path kept = seriesDir.resolve("source-" + meta.id() + "." + container);
                Files.move(source, kept);
                System.out.println("Quelle behalten: " + kept);
            }
        } finally {
            if (!keep) {
                deleteTree(work);
            }
        }

        // 7. Artwork
        if (tmdb != null && series != null) {
            tmdb.downloadArtwork(series, seriesDir);
        }

        System.out.println();
        System.out.println("Fertig. " + parts.size() + " Episoden in " + seasonDir);
        return 0;
    }

    // ------------------------------------------------------------------

    /**
     * Arbeitsverzeichnis fuer den Download. Bewusst NICHT /tmp: in einem
     * Wings-Container ist das ein tmpfs mit wenigen hundert Megabyte, und ein
     * Video plus Tonspur plus gemuxte Datei sprengt das sofort. Stattdessen das
     * Serververzeichnis, das dem Disk-Limit des Servers unterliegt.
     */
    private static Path workRoot() throws IOException {
        String override = System.getenv("GOBLIN_TMP");
        Path root = (override == null || override.isBlank())
                ? Path.of(".").toAbsolutePath().normalize()
                : Path.of(override);
        Files.createDirectories(root);
        return root;
    }

    /**
     * Zieht jede Abschnittsgrenze auf den Bildwechsel, der ihr am naechsten
     * liegt. Der erste Abschnitt bleibt bei 0.
     */
    private static List<Chapter> snap(Path video, List<Chapter> parts, double window, double duration) {
        System.out.printf("Grenzen suchen (Fenster %.0f s) ...%n", window);

        List<Double> starts = new ArrayList<>();
        starts.add(parts.get(0).start());

        for (int i = 1; i < parts.size(); i++) {
            double wanted = parts.get(i).start();
            CutDetect.Result found = CutDetect.nearest(video, wanted, window);

            String note = switch (found.source()) {
                case BLACK -> "Schwarzbild";
                case SCENE -> "Szenenwechsel";
                case NONE -> "nichts gefunden, bleibt";
            };
            System.out.printf("  %s -> %s  (%+.2f s, %s)%n",
                    Chapter.timecode(wanted), Chapter.timecode(found.time()),
                    found.time() - wanted, note);

            starts.add(found.time());
        }

        List<Chapter> snapped = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            double end = (i + 1 < starts.size()) ? starts.get(i + 1) : duration;
            if (end - starts.get(i) < 1.0) {
                continue;
            }
            snapped.add(new Chapter(starts.get(i), end, parts.get(i).title()));
        }
        return snapped;
    }

    /**
     * Verschiebt alle Abschnittsgrenzen ausser der allerersten. Der erste
     * Abschnitt startet immer bei 0 - sonst wuerde der Anfang des Videos
     * verlorengehen. Gedacht fuer den Fall, dass die Zeitstempel in der
     * Beschreibung durchgehend ein paar Sekunden zu frueh liegen.
     */
    private static List<Chapter> shift(List<Chapter> parts, double offset, double duration) {
        List<Double> starts = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            double start = (i == 0) ? parts.get(0).start() : parts.get(i).start() + offset;
            starts.add(Math.max(0, Math.min(start, duration)));
        }

        List<Chapter> shifted = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            double end = (i + 1 < starts.size()) ? starts.get(i + 1) : duration;
            if (end - starts.get(i) < 1.0) {
                continue;
            }
            shifted.add(new Chapter(starts.get(i), end, parts.get(i).title()));
        }
        return shifted;
    }

    /** YouTube-Kapitel haben Vorrang, sonst die Beschreibung durchsuchen. */
    private static List<Chapter> resolveChapters(VideoMeta meta) {
        if (!meta.chapters().isEmpty()) {
            return meta.chapters();
        }
        return ChapterParser.parse(meta.description(), meta.duration());
    }

    private static void requireTools(boolean needFfmpeg) {
        List<String> missing = new ArrayList<>();
        if (!Proc.exists("yt-dlp")) {
            missing.add("yt-dlp");
        }
        if (needFfmpeg && !Proc.exists("ffmpeg")) {
            missing.add("ffmpeg");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Nicht im PATH gefunden: " + String.join(", ", missing));
        }
    }

    private static void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // Aufraeumen ist best effort
                        }
                    });
        } catch (IOException ignored) {
            // dito
        }
    }
}
