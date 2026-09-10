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
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Looks comics up in ComicVine, the database the scene's ComicInfo.xml files
 * come from.
 *
 * TMDb covers film and television and knows nothing about comics, so this is a
 * separate thing rather than another method on {@link Tmdb}. What it is for:
 * turning "Winter Soldier" and an issue number into a publisher, a start year
 * and a cover image.
 *
 * Needs a free API key, in goblin.properties as comicvine.api_key or in the
 * environment as COMICVINE_API_KEY. Without one nothing here runs and the
 * caller falls back on what the files say about themselves.
 *
 * Two things about this API worth knowing, both of which cost a run if you
 * miss them:
 *
 *   - It refuses a request without a User-Agent of its own, with an HTML error
 *     page rather than JSON.
 *   - The rate limit is 200 requests an hour per resource type. A shelf of
 *     comics is therefore looked up per series, never per issue, and the
 *     issue list of a series arrives in one request.
 */
final class ComicVine {

    private static final String API = "https://comicvine.gamespot.com/api";

    /** ComicVine prefixes its ids by resource type. A volume is a 4050. */
    private static final String VOLUME_PREFIX = "4050-";

    private static final String AGENT = "TheGoblin/1.0 (+https://github.com/KiansProjects/TheGoblin)";

    /** A series. ComicVine calls it a volume. */
    record Volume(int id, String name, Integer startYear, String publisher,
                  String coverUrl, String description) {
    }

    record Issue(int id, String number, String name, Integer year, String coverUrl) {
    }

    private final String apiKey;
    private final HttpClient http;

    private ComicVine(String apiKey) {
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    /**
     * @return null when no key is configured, which is not an error - it means
     *         the caller works from the files alone
     */
    static ComicVine from(Path configFile) {
        String key = null;
        if (Files.isReadable(configFile)) {
            Properties p = new Properties();
            try (var in = Files.newInputStream(configFile)) {
                p.load(in);
                String value = p.getProperty("comicvine.api_key");
                if (value != null && !value.isBlank()) {
                    key = value.strip();
                }
            } catch (IOException e) {
                // An unreadable config is the same as no key here.
            }
        }
        if (key == null) {
            key = System.getenv("COMICVINE_API_KEY");
        }
        return (key == null || key.isBlank()) ? null : new ComicVine(key.strip());
    }

    /**
     * Searches for a series.
     *
     * @param year the start year when it is known, which is what tells three
     *             series called "Captain America" apart
     * @return the best match, or null
     */
    Volume search(String name, Integer year) throws IOException, InterruptedException {
        String url = API + "/volumes/?api_key=" + apiKey + "&format=json"
                + "&filter=name:" + encode(name)
                + "&field_list=id,name,start_year,publisher,image,description"
                + "&limit=50";
        List<Volume> found = parseVolumes(get(url));
        return best(found, name, year);
    }

    Volume byId(int id) throws IOException, InterruptedException {
        String url = API + "/volume/" + VOLUME_PREFIX + id + "/?api_key=" + apiKey
                + "&format=json&field_list=id,name,start_year,publisher,image,description";
        Map<String, Object> body = Json.object(Json.parse(get(url)));
        Map<String, Object> result = Json.object(body.get("results"));
        return (result == null || result.isEmpty()) ? null : toVolume(result);
    }

    /** Every issue of a series, in one request. */
    List<Issue> issues(int volumeId) throws IOException, InterruptedException {
        String url = API + "/issues/?api_key=" + apiKey + "&format=json"
                + "&filter=volume:" + volumeId
                + "&field_list=id,issue_number,name,cover_date,image"
                + "&limit=100";
        return parseIssues(get(url));
    }

    /**
     * Picks the series a name most likely means.
     *
     * An exact name match beats a partial one, and a matching start year beats
     * everything - "Captain America" exists eight times over and the year is
     * the only thing that separates them. Without a year the oldest match wins,
     * because that is the one a bare series name usually refers to.
     */
    static Volume best(List<Volume> found, String wanted, Integer year) {
        Volume bestSoFar = null;
        int bestScore = Integer.MIN_VALUE;

        for (Volume v : found) {
            int score = 0;
            if (v.name() != null && v.name().equalsIgnoreCase(wanted.strip())) {
                score += 100;
            }
            if (year != null && v.startYear() != null) {
                int gap = Math.abs(v.startYear() - year);
                score += (gap == 0) ? 200 : Math.max(0, 40 - gap * 10);
            } else if (v.startYear() != null) {
                // Prefer the older one, gently, so it never outweighs a name.
                score += Math.max(0, 30 - (v.startYear() - 1930) / 10);
            }
            if (score > bestScore) {
                bestScore = score;
                bestSoFar = v;
            }
        }
        return bestSoFar;
    }

    /** Split out from the requests so the parsing can be exercised offline. */
    static List<Volume> parseVolumes(String json) {
        List<Volume> out = new ArrayList<>();
        for (Object o : results(json)) {
            Map<String, Object> m = Json.object(o);
            if (m != null && !m.isEmpty()) {
                out.add(toVolume(m));
            }
        }
        return out;
    }

    static List<Issue> parseIssues(String json) {
        List<Issue> out = new ArrayList<>();
        for (Object o : results(json)) {
            Map<String, Object> m = Json.object(o);
            if (m == null || m.isEmpty()) {
                continue;
            }
            out.add(new Issue(
                    (int) Json.num(m, "id", 0),
                    blankToNull(Json.str(m, "issue_number")),
                    blankToNull(Json.str(m, "name")),
                    yearOf(Json.str(m, "cover_date")),
                    cover(m)));
        }
        out.sort((a, b) -> Double.compare(number(a.number()), number(b.number())));
        return out;
    }

    private static List<Object> results(String json) {
        Map<String, Object> body = Json.object(Json.parse(json));
        if (body == null) {
            return List.of();
        }
        List<Object> list = Json.array(body.get("results"));
        return (list == null) ? List.of() : list;
    }

    private static Volume toVolume(Map<String, Object> m) {
        Map<String, Object> publisher = Json.object(m.get("publisher"));
        return new Volume(
                (int) Json.num(m, "id", 0),
                blankToNull(Json.str(m, "name")),
                integer(Json.str(m, "start_year")),
                (publisher == null) ? null : blankToNull(Json.str(publisher, "name")),
                cover(m),
                strip(blankToNull(Json.str(m, "description"))));
    }

    /**
     * The largest image ComicVine offers, falling back through the smaller
     * ones. "original_url" is missing on a fair number of older entries.
     */
    private static String cover(Map<String, Object> m) {
        Map<String, Object> image = Json.object(m.get("image"));
        if (image == null) {
            return null;
        }
        for (String size : List.of("original_url", "super_url", "screen_large_url",
                "screen_url", "medium_url", "small_url")) {
            String url = blankToNull(Json.str(image, size));
            if (url != null) {
                return url;
            }
        }
        return null;
    }

    /** Downloads a cover. Existing files are left alone. */
    boolean saveCover(String url, Path target) {
        if (url == null || Files.exists(target)) {
            return false;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", AGENT)
                    .GET()
                    .build();
            HttpResponse<java.io.InputStream> res =
                    http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) {
                return false;
            }
            Files.createDirectories(target.toAbsolutePath().getParent());
            try (var in = res.body()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private String get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                // Without this ComicVine answers with an HTML block page.
                .header("User-Agent", AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() == 420 || res.statusCode() == 429) {
            throw new IOException("ComicVine rate limit reached - it allows 200 requests an hour.");
        }
        if (res.statusCode() != 200) {
            throw new IOException("ComicVine responded with " + res.statusCode());
        }
        return res.body();
    }

    /** "2012-01-04" and the bare "2012" both occur. */
    static Integer yearOf(String date) {
        if (date == null || date.length() < 4) {
            return null;
        }
        return integer(date.substring(0, 4));
    }

    /** Sorts "1", "1.MU" and "10" the way a shelf does. */
    static double number(String issueNumber) {
        if (issueNumber == null) {
            return Double.MAX_VALUE;
        }
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("\\d+(?:\\.\\d+)?").matcher(issueNumber);
        return m.find() ? Double.parseDouble(m.group()) : Double.MAX_VALUE;
    }

    /** ComicVine descriptions are HTML. A media server wants text. */
    static String strip(String html) {
        if (html == null) {
            return null;
        }
        String text = html.replaceAll("(?is)<(br|/p|/div|/h\\d)[^>]*>", "\n")
                .replaceAll("(?s)<[^>]+>", "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
        return text.isEmpty() ? null : text;
    }

    private static Integer integer(String value) {
        try {
            return (value == null) ? null : Integer.valueOf(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.strip();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
