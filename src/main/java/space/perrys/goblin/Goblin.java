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
              goblin movie <url> <titel> [optionen]   Video als einzelnen Film ablegen
              goblin series <url> <titel> --movie      dasselbe ueber 'series'
              goblin audio <url> [optionen]           Tonspur als Musikdatei ablegen
              goblin concat <url> <titel>             Playlist zu einer Datei zusammenfuegen
              goblin playlist <url> <name>            fertige series-Zeilen erzeugen
              goblin playlist <url> <name> --episodes ein Video je Folge laden
                                                      --from/--to grenzen den
                                                      Ausschnitt ein,
                                                      --from-title liest Staffel
                                                      und Folge aus dem Titel
              goblin chapters <url> --playlist <name> dasselbe ueber 'chapters'

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
                  --upload            fertige Dateien per SFTP hochladen
                                      (Zugang in goblin.properties)
                  --keep-local        lokale Kopie nach dem Upload behalten
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
            case "playlist" -> playlist(args);
            case "movie" -> movie(args);
            case "concat" -> concat(args);
            case "audio" -> audio(args);
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
        boolean playlistMode = false;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--verbose", "-v" -> verbose = true;
                case "--formats", "-F" -> formats = true;
                case "--playlist" -> playlistMode = true;
                default -> { }
            }
        }

        if (formats) {
            YtDlp.listFormats(args[1]);
            return 0;
        }

        if (playlistMode) {
            // Damit die Playlist auch dann geht, wenn der Konsolen-Wrapper
            // nur 'chapters' durchlaesst.
            return playlist(new String[] {"playlist", args[1], nameFrom(args)});
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


    /**
     * Ein Video je Episode. Anders als bei series wird nichts geschnitten -
     * jedes Video der Playlist wird einmal geladen und als eine Folge abgelegt.
     * Vorhandene Dateien werden uebersprungen, damit ein abgebrochener Lauf
     * fortgesetzt werden kann statt von vorn anzufangen.
     */
    private static int episodes(List<String[]> entries, String name, Path out,
                                int season, int startEpisode, Integer year, Integer tmdbId,
                                boolean useTmdb, boolean withTitles, boolean fromTitle,
                                boolean overwrite, boolean dryRun, boolean verbose,
                                boolean upload, boolean keepLocal,
                                String format, String container) throws Exception {

        requireTools(!dryRun);

        Tmdb.Series series = null;
        Tmdb tmdb = useTmdb ? Tmdb.from(CONFIG) : null;
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
        }

        Integer folderYear = (year != null) ? year : (series != null ? series.year() : null);
        Integer folderId = (tmdbId != null) ? tmdbId : (series != null ? series.id() : null);

        Path seriesDir = out.resolve(Naming.seriesFolder(name, folderYear, folderId));

        // Zielpfade vorab bestimmen. Mit --from-title kann jeder Eintrag in
        // einer anderen Staffel landen, deshalb pro Eintrag ein eigener Pfad.
        // Eintraege ohne erkennbare Nummer werden mit --from-title nicht geraten.
        // Ein durchnummerierter Rueckfall wuerde mit erkannten Folgen kollidieren
        // und diese ueberschreiben - lieber melden und der Hand ueberlassen.
        List<Path> targets = new ArrayList<>();
        List<String[]> usable = new ArrayList<>();
        List<String> unparsed = new ArrayList<>();
        int running = startEpisode;

        for (String[] entry : entries) {
            int sn = season;
            int ep;

            if (fromTitle) {
                var ref = TitleNumbers.parse(entry[1], season);
                if (ref.isEmpty()) {
                    unparsed.add(entry[1].isBlank() ? entry[0] : entry[1]);
                    continue;
                }
                sn = ref.get().season();
                ep = ref.get().episode();
            } else {
                ep = running++;
            }

            usable.add(entry);
            targets.add(seriesDir.resolve(Naming.seasonFolder(sn))
                    .resolve(fileFor(name, sn, ep, entry, withTitles, container)));
        }

        entries = usable;

        System.out.printf("%d Videos -> %s%n%n", entries.size(), seriesDir);

        if (!unparsed.isEmpty()) {
            System.out.printf("%d ohne erkennbare Nummer, uebersprungen:%n", unparsed.size());
            for (String t : unparsed) {
                System.out.println("  " + t);
            }
            System.out.println();
        }

        if (entries.isEmpty()) {
            System.err.println("Kein Video mit erkennbarer Nummer. Ohne --from-title versuchen.");
            return 1;
        }

        if (dryRun) {
            for (Path t : targets) {
                System.out.println("  " + seriesDir.relativize(t));
            }
            System.out.println();
            System.out.println("Dry-Run, es wurde nichts geschrieben.");
            return 0;
        }

        Sftp sftp = upload ? Sftp.fromConfig(CONFIG) : null;
        if (upload && sftp == null) {
            System.err.println("--upload gesetzt, aber " + CONFIG + " fehlt oder ist unvollstaendig.");
            return 2;
        }
        if (sftp != null) {
            System.out.println("Upload nach " + sftp.describe());
            System.out.println();
        }

        int done = 0;
        int skipped = 0;
        int failed = 0;

        for (int i = 0; i < entries.size(); i++) {
            Path target = targets.get(i);
            String fileName = target.getFileName().toString();

            if (!overwrite && Files.exists(target)) {
                System.out.println("  vorhanden: " + fileName);
                skipped++;
                continue;
            }

            System.out.printf("  [%d/%d] %s%n", i + 1, entries.size(),
                    seriesDir.relativize(target));
            try {
                Files.createDirectories(target.getParent());
                String stem = fileName.substring(0, fileName.lastIndexOf('.'));
                YtDlp.download("https://youtu.be/" + entries.get(i)[0],
                        target.getParent().resolve(stem), format, container);
                uploadIfConfigured(sftp, out, target, keepLocal);
                done++;
            } catch (IOException e) {
                // Ein kaputtes Video soll die restlichen 50 nicht verhindern
                System.out.println("    fehlgeschlagen: " + e.getMessage());
                failed++;
            }
        }

        if (tmdb != null && series != null && Files.exists(seriesDir)) {
            tmdb.downloadArtwork(series, seriesDir);
        }

        System.out.println();
        System.out.printf("Fertig. %d geladen, %d uebersprungen, %d fehlgeschlagen.%n",
                done, skipped, failed);
        if (failed > 0) {
            System.out.println("Denselben Befehl nochmal aufrufen - Vorhandenes wird uebersprungen.");
        }
        return failed > 0 ? 1 : 0;
    }

    private static String fileFor(String name, int season, int episode,
                                  String[] entry, boolean withTitles, String container) {
        String title = withTitles ? entry[1] : "";
        return Naming.episodeFile(name, season, episode, title, container);
    }

    // ------------------------------------------------------------------
    // goblin movie <url> <titel>
    // ------------------------------------------------------------------

    /**
     * Laedt ein Video als einzelnen Film. Kein Schneiden, keine Kapitel -
     * nur Herunterladen und so ablegen, wie Jellyfins Film-Scanner es erwartet.
     */
    private static int movie(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Aufruf: goblin movie <url> <titel> [optionen]");
            return 2;
        }

        String url = args[1];
        String title = args[2];

        Path out = Path.of("output/filme");
        Integer year = null;
        Integer tmdbId = null;
        boolean useTmdb = true;
        boolean dryRun = false;
        boolean verbose = false;
        boolean upload = false;
        boolean keepLocal = false;
        String format = YtDlp.FORMAT_H264;
        String container = "mp4";

        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "-o", "--out" -> out = Path.of(args[++i]);
                case "--upload" -> upload = true;
                case "--keep-local" -> keepLocal = true;
                case "--year" -> year = Integer.valueOf(args[++i]);
                case "--tmdb-id" -> tmdbId = Integer.valueOf(args[++i]);
                case "--no-tmdb" -> useTmdb = false;
                case "--dry-run" -> dryRun = true;
                case "--verbose", "-v" -> verbose = true;
                case "--movie" -> { }
                case "--best" -> {
                    format = YtDlp.FORMAT_BEST;
                    container = "mkv";
                }
                case "--format", "-f" -> format = args[++i];
                case "--container" -> container = args[++i];
                default -> {
                    System.err.println("Unbekannte Option: " + args[i]);
                    return 2;
                }
            }
        }

        requireTools(!dryRun);

        System.out.println("Metadaten abrufen ...");
        VideoMeta meta = YtDlp.metadata(url, verbose);
        System.out.println("Video: " + meta.title());

        Tmdb.Series film = null;
        Tmdb tmdb = useTmdb ? Tmdb.from(CONFIG) : null;
        if (tmdb != null) {
            try {
                film = (tmdbId != null) ? tmdb.movieById(tmdbId) : tmdb.searchMovie(title);
                if (film != null) {
                    System.out.printf("TMDb: %s (%s), ID %d%n", film.name(), film.year(), film.id());
                }
            } catch (IOException e) {
                System.out.println("TMDb nicht erreichbar, mache ohne weiter: " + e.getMessage());
            }
        } else if (useTmdb) {
            System.out.println("Kein TMDb-Key (tmdb.api_key in " + CONFIG
                    + " oder TMDB_API_KEY), ueberspringe Artwork.");
        }

        Integer folderYear = (year != null) ? year : (film != null ? film.year() : null);
        Integer folderId = (tmdbId != null) ? tmdbId : (film != null ? film.id() : null);

        Path movieDir = out.resolve(Naming.movieFolder(title, folderYear, folderId));
        String fileName = Naming.movieFile(title, folderYear, container);

        System.out.println();
        System.out.println(movieDir);
        System.out.println("  " + fileName);
        System.out.println();

        if (dryRun) {
            System.out.println("Dry-Run, es wurde nichts geschrieben.");
            return 0;
        }

        Files.createDirectories(movieDir);

        Sftp sftp = upload ? Sftp.fromConfig(CONFIG) : null;
        if (upload && sftp == null) {
            System.err.println("--upload gesetzt, aber " + CONFIG + " fehlt oder ist unvollstaendig.");
            return 2;
        }

        System.out.println("Video laden ...");
        String stem = fileName.substring(0, fileName.lastIndexOf('.'));
        YtDlp.download(url, movieDir.resolve(stem), format, container);

        if (tmdb != null && film != null) {
            tmdb.downloadArtwork(film, movieDir);
        }

        uploadIfConfigured(sftp, out, movieDir.resolve(fileName), keepLocal);

        System.out.println();
        System.out.println("Fertig. " + movieDir.resolve(fileName));
        return 0;
    }



    // ------------------------------------------------------------------
    // goblin audio <url>
    // ------------------------------------------------------------------

    /**
     * Zieht die Tonspur aus einem Video oder einer Playlist.
     *
     * Ablage nach dem ueblichen Muster fuer Musiksammlungen:
     * Interpret / Album / NN - Titel.ext
     *
     * Interpret und Album kommen aus den Angaben oder ersatzweise aus Kanal
     * und Playlisttitel.
     */
    private static int audio(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Aufruf: goblin audio <url> [optionen]");
            return 2;
        }

        String url = args[1];

        Path out = Path.of("output/musik");
        String artist = null;
        String album = null;
        String format = "mp3";
        int quality = 0;
        boolean splitChapters = false;
        boolean dryRun = false;

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "-o", "--out" -> out = Path.of(args[++i]);
                case "--artist" -> artist = args[++i];
                case "--album" -> album = args[++i];
                case "--format", "-f" -> format = args[++i];
                case "--quality" -> quality = Integer.parseInt(args[++i]);
                case "--chapters" -> splitChapters = true;
                case "--dry-run" -> dryRun = true;
                default -> {
                    System.err.println("Unbekannte Option: " + args[i]);
                    return 2;
                }
            }
        }

        requireTools(!dryRun);

        boolean isPlaylist = url.contains("list=") || url.contains("playlist")
                || url.contains("video_ids=");

        if (artist == null || album == null) {
            String[] meta = isPlaylist
                    ? YtDlp.playlistMeta(url)
                    : new String[] {"", ""};

            if (album == null && !meta[0].isBlank()) {
                album = meta[0];
            }
            if (artist == null && !meta[1].isBlank()) {
                artist = meta[1];
            }
        }

        // Was sich nicht ermitteln liess, ueberlaesst das Muster yt-dlp.
        String artistDir = (artist != null) ? Naming.sanitize(artist) : "%(artist,uploader)s";
        String albumDir = (album != null) ? Naming.sanitize(album) : "%(album,title)s";

        String fileName = isPlaylist
                ? "%(playlist_index)02d - %(title)s.%(ext)s"
                : "%(title)s.%(ext)s";
        if (splitChapters) {
            fileName = "%(section_number)02d - %(section_title)s.%(ext)s";
        }

        String template = out.resolve(artistDir).resolve(albumDir).resolve(fileName).toString();

        System.out.println("Interpret: " + (artist != null ? artist : "(aus den Metadaten)"));
        System.out.println("Album:     " + (album != null ? album : "(aus den Metadaten)"));
        System.out.println("Format:    " + ("best".equalsIgnoreCase(format)
                ? "Originalspur, keine Neukodierung" : format));
        System.out.println("Muster:    " + template);
        System.out.println();

        if (dryRun) {
            System.out.println("Dry-Run, es wurde nichts geladen.");
            return 0;
        }

        if (!"best".equalsIgnoreCase(format) && List.of("flac", "wav").contains(format.toLowerCase())) {
            System.out.println("Hinweis: YouTube liefert bereits verlustbehaftet. "
                    + format + " macht die Dateien groesser, nicht besser.");
            System.out.println();
        }

        YtDlp.audio(url, format, quality, template, splitChapters);

        System.out.println();
        System.out.println("Fertig. " + out.resolve(artistDir).resolve(albumDir));
        return 0;
    }

    // ------------------------------------------------------------------
    // goblin concat <playlist-url> <titel>
    // ------------------------------------------------------------------

    /**
     * Fuegt die Videos einer Playlist zu einer Datei zusammen.
     *
     * Vor dem Zusammenfuegen wird je Teil geprueft, ob am Ende ein Abspann
     * laeuft und ob der naechste Teil mit dem Ende des vorherigen beginnt.
     * Beides wird weggeschnitten, damit keine Dopplung im fertigen Video
     * landet.
     */
    private static int concat(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Aufruf: goblin concat <playlist-url> <titel> [optionen]");
            return 2;
        }

        String url = args[1];
        String title = args[2];

        Path out = Path.of("output");
        double outroFixed = -1;
        boolean detectOutro = true;
        double overlapWindow = 90;
        // 1 s reicht: bei echter Ueberlappung liegt die Korrelation nahe 1,
        // Zufallstreffer bleiben deutlich darunter. Gemessen an einem
        // Testuebergang: echter Treffer 0.999, bester Fehltreffer 0.80.
        double minOverlap = 1;
        double maxOverlap = 30;
        double minScore = 0.90;
        boolean dryRun = false;
        boolean keepParts = false;
        String format = YtDlp.FORMAT_H264;
        String container = "mp4";

        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "-o", "--out" -> out = Path.of(args[++i]);
                case "--outro" -> outroFixed = Double.parseDouble(args[++i]);
                case "--no-outro" -> detectOutro = false;
                case "--overlap" -> overlapWindow = Double.parseDouble(args[++i]);
                case "--no-overlap" -> overlapWindow = 0;
                case "--min-score" -> minScore = Double.parseDouble(args[++i]);
                case "--min-overlap" -> minOverlap = Double.parseDouble(args[++i]);
                case "--max-overlap" -> maxOverlap = Double.parseDouble(args[++i]);
                case "--dry-run" -> dryRun = true;
                case "--keep-parts" -> keepParts = true;
                case "--best" -> {
                    format = YtDlp.FORMAT_BEST;
                    container = "mkv";
                }
                case "--format", "-f" -> format = args[++i];
                case "--container" -> container = args[++i];
                default -> {
                    System.err.println("Unbekannte Option: " + args[i]);
                    return 2;
                }
            }
        }

        requireTools(!dryRun);

        List<String[]> entries = YtDlp.playlist(url);
        if (entries.size() < 2) {
            System.err.println("Weniger als zwei Videos - dafuer lohnt concat nicht.");
            return 1;
        }

        System.out.printf("%d Teile%n", entries.size());
        if (dryRun) {
            for (int i = 0; i < entries.size(); i++) {
                System.out.printf("  %2d. %s%n", i + 1,
                        entries.get(i)[1].isBlank() ? entries.get(i)[0] : entries.get(i)[1]);
            }
            System.out.println();
            System.out.println("Dry-Run, es wurde nichts geladen.");
            return 0;
        }

        Path work = Files.createTempDirectory(workRoot(), "goblin-concat-");
        Path target = out.resolve(Naming.sanitize(title) + "." + container);
        Files.createDirectories(out);

        try {
            // 1. Alle Teile laden
            List<Path> parts = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                System.out.printf("Teil %d/%d laden ...%n", i + 1, entries.size());
                parts.add(YtDlp.download("https://youtu.be/" + entries.get(i)[0],
                        work.resolve(String.format("part-%03d", i + 1)), format, container));
            }

            // 2. Grenzen bestimmen
            System.out.println();
            System.out.println("Uebergaenge pruefen ...");

            List<double[]> cuts = new ArrayList<>(); // je Teil: {start, ende}
            for (int i = 0; i < parts.size(); i++) {
                double duration = Ffprobe.duration(parts.get(i));
                double start = 0;
                double end = duration;

                if (i < parts.size() - 1 && detectOutro) {
                    if (outroFixed >= 0) {
                        end = Math.max(0, duration - outroFixed);
                    } else {
                        var outro = OutroDetect.find(parts.get(i), duration);
                        if (outro.isPresent()) {
                            end = outro.getAsDouble();
                        }
                    }
                }

                if (i > 0 && overlapWindow > 0) {
                    double prevEnd = cuts.get(i - 1)[1];
                    double[] tail = AudioProbe.envelope(parts.get(i - 1),
                            Math.max(0, prevEnd - overlapWindow),
                            Math.min(overlapWindow, prevEnd));
                    double[] head = AudioProbe.envelope(parts.get(i), 0, overlapWindow);

                    var match = AudioProbe.bestMatch(tail, head, minOverlap, maxOverlap, minScore);
                    if (match.isPresent()) {
                        double lag = match.getAsDouble();
                        double windowStart = Math.max(0, prevEnd - overlapWindow);
                        double overlapLength = prevEnd - (windowStart + lag);
                        if (overlapLength > 0.5 && overlapLength < overlapWindow) {
                            start = overlapLength;
                        }
                    }
                }

                cuts.add(new double[] {start, end});
                System.out.printf("  Teil %2d: %s bis %s%s%s%n", i + 1,
                        Chapter.timecode(start), Chapter.timecode(end),
                        start > 0 ? String.format("  (%.1fs Anfang doppelt)", start) : "",
                        end < duration ? String.format("  (%.1fs Abspann)", duration - end) : "");
            }

            // 3. Teile zuschneiden und aneinanderhaengen
            System.out.println();
            System.out.println("Zusammenfuegen ...");

            List<Path> segments = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                double start = cuts.get(i)[0];
                double end = cuts.get(i)[1];
                if (end - start < 1.0) {
                    System.out.printf("  Teil %d ist nach dem Schnitt leer, uebersprungen.%n", i + 1);
                    continue;
                }
                Path segment = work.resolve(String.format("seg-%03d.%s", i + 1, container));
                Ffmpeg.cut(parts.get(i), new Chapter(start, end, ""), segment, true);
                segments.add(segment);
            }

            Ffmpeg.concat(segments, work.resolve("list.txt"), target);

            if (keepParts) {
                System.out.println("Teile bleiben in " + work);
            }
        } finally {
            if (!keepParts) {
                deleteTree(work);
            }
        }

        System.out.println();
        System.out.println("Fertig. " + target);
        return 0;
    }

    // ------------------------------------------------------------------
    // goblin playlist <url> <name>
    // ------------------------------------------------------------------

    /**
     * Liest eine Playlist und druckt fuer jedes Video eine fertige
     * series-Zeile. Die Staffelnummer steigt mit der Position - das passt,
     * wenn ein Video je Staffel in der Playlist liegt. Sonst die Zeilen vor
     * dem Ausfuehren anpassen.
     */
    private static int playlist(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Aufruf: goblin playlist <url> <name> [optionen]");
            return 2;
        }
        requireTools(false);

        String url = args[1];
        String name = args[2];

        String out = "output/serien";
        int firstSeason = 1;
        String extra = "--snap --reencode";

        boolean episodeMode = false;
        int from = 1;
        int to = Integer.MAX_VALUE;
        int startEpisode = 1;
        Integer year = null;
        Integer tmdbId = null;
        boolean useTmdb = true;
        boolean withTitles = false;
        boolean fromTitle = false;
        boolean overwrite = false;
        boolean upload = false;
        boolean keepLocal = false;
        boolean dryRun = false;
        boolean verbose = false;
        String format = YtDlp.FORMAT_H264;
        String container = "mp4";

        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "-o", "--out" -> out = args[++i];
                case "-s", "--season" -> firstSeason = Integer.parseInt(args[++i]);
                case "--extra" -> extra = args[++i];
                case "--episodes" -> episodeMode = true;
                case "--from" -> from = Integer.parseInt(args[++i]);
                case "--to" -> to = Integer.parseInt(args[++i]);
                case "-e", "--start-episode" -> startEpisode = Integer.parseInt(args[++i]);
                case "--year" -> year = Integer.valueOf(args[++i]);
                case "--tmdb-id" -> tmdbId = Integer.valueOf(args[++i]);
                case "--no-tmdb" -> useTmdb = false;
                case "--titles" -> withTitles = true;
                case "--from-title" -> fromTitle = true;
                case "--upload" -> upload = true;
                case "--keep-local" -> keepLocal = true;
                case "--overwrite" -> overwrite = true;
                case "--dry-run" -> dryRun = true;
                case "--verbose", "-v" -> verbose = true;
                case "--best" -> {
                    format = YtDlp.FORMAT_BEST;
                    container = "mkv";
                }
                case "--format", "-f" -> format = args[++i];
                case "--container" -> container = args[++i];
                default -> {
                    System.err.println("Unbekannte Option: " + args[i]);
                    return 2;
                }
            }
        }

        List<String[]> entries = YtDlp.playlist(url);
        if (entries.isEmpty()) {
            System.err.println("Die Playlist enthaelt keine abrufbaren Videos.");
            return 1;
        }

        int total = entries.size();
        int firstIndex = Math.max(1, from) - 1;
        int lastIndex = Math.min(total, to);
        if (firstIndex >= lastIndex) {
            System.err.printf("Leerer Ausschnitt: %d Videos vorhanden, --from %d --to %d%n",
                    total, from, to);
            return 2;
        }
        if (firstIndex > 0 || lastIndex < total) {
            entries = entries.subList(firstIndex, lastIndex);
            System.out.printf("Ausschnitt %d bis %d von %d Videos%n", firstIndex + 1, lastIndex, total);
        }

        if (episodeMode) {
            return episodes(entries, name, Path.of(out), firstSeason, startEpisode,
                    year, tmdbId, useTmdb, withTitles, fromTitle, overwrite, dryRun, verbose,
                    upload, keepLocal, format, container);
        }

        System.out.printf("%d Videos in der Playlist%n%n", entries.size());
        for (int i = 0; i < entries.size(); i++) {
            String title = entries.get(i)[1];
            System.out.printf("# %d. %s%n", firstIndex + i + 1,
                    title.isBlank() ? "(kein Titel)" : title);
            System.out.printf("series https://youtu.be/%s \"%s\" --out %s --season %d %s%n%n",
                    entries.get(i)[0], name, out, firstSeason + i, extra);
        }

        System.out.println("Zeilen pruefen, dann einzeln ausfuehren.");
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

        for (String a : args) {
            if (a.equals("--movie")) {
                // Damit Filme auch dann gehen, wenn der Wrapper nur 'series' kennt.
                String[] forwarded = args.clone();
                forwarded[0] = "movie";
                return movie(forwarded);
            }
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
        boolean upload = false;
        boolean keepLocal = false;

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
                case "--upload" -> upload = true;
                case "--keep-local" -> keepLocal = true;
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
        Tmdb tmdb = useTmdb ? Tmdb.from(CONFIG) : null;
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
            System.out.println("Kein TMDb-Key (tmdb.api_key in " + CONFIG
                    + " oder TMDB_API_KEY), ueberspringe Artwork.");
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

        Sftp sftp = upload ? Sftp.fromConfig(CONFIG) : null;
        if (upload && sftp == null) {
            System.err.println("--upload gesetzt, aber " + CONFIG + " fehlt oder ist unvollstaendig.");
            return 2;
        }
        if (sftp != null) {
            System.out.println("Upload nach " + sftp.describe());
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
                uploadIfConfigured(sftp, out, target, keepLocal);
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

    /** Konfigurationsdatei fuer den SFTP-Upload. */
    private static final Path CONFIG = Path.of("goblin.properties");

    /**
     * Laedt eine fertige Datei hoch und loescht sie danach lokal. Ohne
     * konfigurierten SFTP-Zugang passiert nichts und die Datei bleibt liegen.
     *
     * @param root    Verzeichnis, relativ zu dem der Zielpfad gebildet wird
     * @param keepLocal true laesst die lokale Kopie stehen
     * @return true, wenn hochgeladen wurde
     */
    private static boolean uploadIfConfigured(Sftp sftp, Path root, Path file, boolean keepLocal) {
        if (sftp == null) {
            return false;
        }
        try {
            String remote = root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
            sftp.upload(file, remote);
            if (!keepLocal) {
                Files.deleteIfExists(file);
            }
            System.out.println("    hochgeladen: " + remote);
            return true;
        } catch (IOException | InterruptedException e) {
            System.out.println("    Upload fehlgeschlagen, Datei bleibt lokal: " + e.getMessage());
            return false;
        }
    }

    /** Nimmt den ersten Nicht-Options-Parameter nach der URL als Serienname. */
    private static String nameFrom(String[] args) {
        for (int i = 2; i < args.length; i++) {
            if (!args[i].startsWith("-")) {
                return args[i];
            }
        }
        return "Serie";
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
