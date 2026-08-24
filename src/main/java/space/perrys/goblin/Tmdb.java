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
import java.util.List;
import java.util.Map;

/**
 * Serien-Datenbank. Liefert die TMDb-ID (damit Jellyfin nicht raten muss)
 * sowie Poster und Hintergrundbild.
 *
 * Braucht einen kostenlosen API-Key in der Umgebungsvariable TMDB_API_KEY.
 * Ohne Key laeuft TheGoblin trotzdem, nur eben ohne Artwork.
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

    static Tmdb fromEnvironment() {
        String key = System.getenv("TMDB_API_KEY");
        return (key == null || key.isBlank()) ? null : new Tmdb(key.strip());
    }

    /**
     * @param id         TMDb-ID der Serie
     * @param name       offizieller Serientitel
     * @param year       Jahr der Erstausstrahlung, kann null sein
     * @param posterPath Pfadfragment des Posters, kann null sein
     * @param backdropPath Pfadfragment des Hintergrundbilds, kann null sein
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

    Series byId(int id) throws IOException, InterruptedException {
        String url = API + "/tv/" + id + "?api_key=" + apiKey;
        return toSeries(Json.object(Json.parse(get(url))));
    }

    /** Legt poster.jpg und backdrop.jpg im Serienordner ab. Jellyfin liest die direkt ein. */
    void downloadArtwork(Series series, Path seriesFolder) {
        save(series.posterPath(), seriesFolder.resolve("poster.jpg"));
        save(series.backdropPath(), seriesFolder.resolve("backdrop.jpg"));
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
            System.out.println("  Artwork uebersprungen (" + e.getMessage() + ")");
        }
    }

    private String get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) {
            throw new IOException("TMDb antwortete mit " + res.statusCode());
        }
        return res.body();
    }
}
