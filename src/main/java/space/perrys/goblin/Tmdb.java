package space.perrys.goblin;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Show database. Provides the TMDb ID (so Jellyfin does not have to guess)
 * along with the poster and the backdrop image.
 *
 * Needs a free API key in the environment variable TMDB_API_KEY. Without a key
 * TheGoblin still runs, just without artwork.
 */
final class Tmdb {

    private static final String API = "https://api.themoviedb.org/3";
    private static final String IMAGES = "https://image.tmdb.org/t/p/original";

    private final String apiKey;
    private final HttpClient http;

    Tmdb(String apiKey) {
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    /**
     * Takes the key from goblin.properties (tmdb.api_key). If it is missing
     * there, the environment variable TMDB_API_KEY is used - that way existing
     * setups using panel variables keep working.
     */
    static Tmdb from(Path configFile) {
        String key = fromFile(configFile);
        if (key == null) {
            key = System.getenv("TMDB_API_KEY");
        }
        return (key == null || key.isBlank()) ? null : new Tmdb(key.strip());
    }

    private static String fromFile(Path configFile) {
        if (!Files.isReadable(configFile)) {
            return null;
        }
        Properties p = new Properties();
        try (var in = Files.newInputStream(configFile)) {
            p.load(in);
        } catch (IOException e) {
            return null;
        }
        String key = p.getProperty("tmdb.api_key");
        return (key == null || key.isBlank()) ? null : key.strip();
    }

    /**
     * @param id         TMDb ID of the show
     * @param name       official show title
     * @param year       year first aired, may be null
     * @param posterPath path fragment of the poster, may be null
     * @param backdropPath path fragment of the backdrop image, may be null
     */
    record Series(int id, String name, Integer year, String posterPath, String backdropPath) {
    }

    Series search(String query) throws IOException, InterruptedException {
        String url = API + "/search/tv?api_key=" + apiKey
                + "&query=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

        Map<String, Object> root = Json.object(Json.parse(get(url)));
        List<Object> results = Json.array(root.get("results"));
        return results.isEmpty() ? null : toSeries(Json.object(results.get(0)));
    }

    Series searchMovie(String query) throws IOException, InterruptedException {
        String url = API + "/search/movie?api_key=" + apiKey
                + "&query=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

        Map<String, Object> root = Json.object(Json.parse(get(url)));
        List<Object> results = Json.array(root.get("results"));
        return results.isEmpty() ? null : toMovie(Json.object(results.get(0)));
    }

    Series movieById(int id) throws IOException, InterruptedException {
        return toMovie(Json.object(Json.parse(get(API + "/movie/" + id + "?api_key=" + apiKey))));
    }

    Series byId(int id) throws IOException, InterruptedException {
        String url = API + "/tv/" + id + "?api_key=" + apiKey;
        return toSeries(Json.object(Json.parse(get(url))));
    }

    /**
     * One episode as TMDb has it.
     *
     * @param number  episode number within the season
     * @param name    episode title, may be null
     * @param seconds run time, null when TMDb has none
     */
    record Episode(int number, String name, Double seconds) {
    }

    /**
     * The episodes of one season, in order.
     *
     * Run times on TMDb are whole minutes and describe the broadcast version,
     * not somebody's compilation of it. They are a starting point for finding
     * boundaries, never the boundaries themselves - see {@link Runtimes}.
     *
     * @return an empty list when TMDb has no such season
     */
    List<Episode> season(int seriesId, int season) throws IOException, InterruptedException {
        return parseSeason(get(API + "/tv/" + seriesId + "/season/" + season
                + "?api_key=" + apiKey));
    }

    /** Split out from {@link #season} so the parsing can be exercised offline. */
    static List<Episode> parseSeason(String json) {
        List<Episode> episodes = new ArrayList<>();
        for (Object entry : Json.array(Json.object(Json.parse(json)).get("episodes"))) {
            Map<String, Object> e = Json.object(entry);
            int number = (int) Json.num(e, "episode_number", -1);
            if (number < 0) {
                continue;
            }
            // TMDb writes minutes, and null for anything it does not know.
            double minutes = Json.num(e, "runtime", 0);
            episodes.add(new Episode(number, Json.str(e, "name"),
                    (minutes > 0) ? minutes * 60 : null));
        }
        episodes.sort(Comparator.comparingInt(Episode::number));
        return episodes;
    }

    /** Writes poster.jpg and backdrop.jpg into the show folder. Jellyfin reads those directly. */
    void downloadArtwork(Series series, Path seriesFolder) {
        save(series.posterPath(), seriesFolder.resolve("poster.jpg"));
        save(series.backdropPath(), seriesFolder.resolve("backdrop.jpg"));
    }

    /** On TMDb movies use "title" and "release_date" instead of "name"/"first_air_date". */
    private Series toMovie(Map<String, Object> m) {
        if (m.isEmpty()) {
            return null;
        }
        int id = (int) Json.num(m, "id", -1);
        if (id < 0) {
            return null;
        }
        String released = Json.str(m, "release_date");
        Integer year = (released != null && released.length() >= 4)
                ? Integer.valueOf(released.substring(0, 4))
                : null;

        return new Series(
                id,
                Json.str(m, "title"),
                year,
                Json.str(m, "poster_path"),
                Json.str(m, "backdrop_path"));
    }

    private Series toSeries(Map<String, Object> m) {
        if (m.isEmpty()) {
            return null;
        }
        int id = (int) Json.num(m, "id", -1);
        if (id < 0) {
            return null;
        }
        String first = Json.str(m, "first_air_date");
        Integer year = (first != null && first.length() >= 4)
                ? Integer.valueOf(first.substring(0, 4))
                : null;

        return new Series(
                id,
                Json.str(m, "name"),
                year,
                Json.str(m, "poster_path"),
                Json.str(m, "backdrop_path"));
    }

    private void save(String imagePath, Path target) {
        if (imagePath == null || Files.exists(target)) {
            return;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(IMAGES + imagePath)).GET().build();
            HttpResponse<java.io.InputStream> res =
                    http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() == 200) {
                try (var in = res.body()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                System.out.println("  Artwork: " + target.getFileName());
            }
        } catch (IOException | InterruptedException e) {
            System.out.println("  Artwork skipped (" + e.getMessage() + ")");
        }
    }

    private String get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) {
            throw new IOException("TMDb responded with " + res.statusCode());
        }
        return res.body();
    }
}
