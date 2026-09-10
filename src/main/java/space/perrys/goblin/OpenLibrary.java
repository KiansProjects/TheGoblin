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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks books up in Open Library.
 *
 * Chosen over the alternatives for one reason that matters more than coverage:
 * it needs no API key. TMDb, ComicVine and the rest all cost the user a
 * registration before anything works; this one answers a plain GET. For a
 * feature that is only worth having if it runs unattended, that is the
 * difference between working and being configured later.
 *
 * Two ways in, and the difference between them is large:
 *
 *   - By ISBN, which is a lookup. One book, no ambiguity. A file name from a
 *     download site nearly always carries one.
 *   - By title and author, which is a search, and searches are wrong
 *     sometimes. Only used when there is no ISBN.
 *
 * Covers come from a separate host that takes an ISBN in the path, so a book
 * with an ISBN gets a cover without a second lookup.
 */
final class OpenLibrary {

    private static final String API = "https://openlibrary.org";
    private static final String COVERS = "https://covers.openlibrary.org/b";

    private static final String AGENT = "TheGoblin/1.0 (+https://github.com/KiansProjects/TheGoblin)";

    private static final Pattern YEAR = Pattern.compile("\\b(1[5-9][0-9]{2}|20[0-9]{2})\\b");

    record Book(String title, List<String> authors, String publisher, Integer year, String isbn) {
    }

    private final HttpClient http;

    OpenLibrary() {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    /**
     * @return the book, or null when nothing was found or the service could
     *         not be reached - neither is an error worth stopping a run for
     */
    Book find(String isbn, String title, String author) {
        try {
            if (isbn != null) {
                Book byIsbn = byIsbn(isbn);
                if (byIsbn != null) {
                    return byIsbn;
                }
            }
            return (title == null) ? null : search(title, author);
        } catch (IOException | InterruptedException e) {
            System.out.println("  Open Library: " + e.getMessage());
            return null;
        }
    }

    Book byIsbn(String isbn) throws IOException, InterruptedException {
        String url = API + "/api/books?bibkeys=ISBN:" + encode(isbn)
                + "&format=json&jscmd=data";
        return parseByIsbn(get(url), isbn);
    }

    /** Split out from the request so the parsing can be exercised offline. */
    static Book parseByIsbn(String json, String isbn) {
        Map<String, Object> body = Json.object(Json.parse(json));
        Map<String, Object> entry = Json.object(body.get("ISBN:" + isbn));
        if (entry.isEmpty()) {
            return null;
        }

        String title = Json.str(entry, "title");
        if (title == null) {
            return null;
        }
        String subtitle = Json.str(entry, "subtitle");
        if (subtitle != null && !subtitle.isBlank()) {
            title = title + ": " + subtitle.strip();
        }

        return new Book(title.strip(), names(entry, "authors"), firstName(entry, "publishers"),
                yearOf(Json.str(entry, "publish_date")), isbn);
    }

    Book search(String title, String author) throws IOException, InterruptedException {
        String url = API + "/search.json?limit=5&fields=title,author_name,publisher,"
                + "first_publish_year,isbn&title=" + encode(title)
                + (author == null ? "" : "&author=" + encode(author));
        return parseSearch(get(url));
    }

    static Book parseSearch(String json) {
        Map<String, Object> body = Json.object(Json.parse(json));
        List<Object> docs = Json.array(body.get("docs"));
        if (docs.isEmpty()) {
            return null;
        }

        Map<String, Object> first = Json.object(docs.get(0));
        String title = Json.str(first, "title");
        if (title == null) {
            return null;
        }

        List<String> authors = strings(Json.array(first.get("author_name")));
        List<String> isbns = strings(Json.array(first.get("isbn")));
        double year = Json.num(first, "first_publish_year", 0);

        return new Book(title.strip(), authors,
                strings(Json.array(first.get("publisher"))).stream().findFirst().orElse(null),
                (year > 0) ? (int) year : null,
                isbns.isEmpty() ? null : isbns.get(0));
    }

    /**
     * Downloads the cover for an ISBN.
     *
     * The service answers a book it has no cover for with a 1x1 placeholder
     * rather than a 404, so anything implausibly small is treated as nothing -
     * a shelf of identical grey pixels is worse than no covers at all.
     */
    boolean saveCover(String isbn, Path target) {
        if (isbn == null || Files.exists(target)) {
            return false;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create(COVERS + "/isbn/" + encode(isbn) + "-L.jpg?default=false"))
                    .header("User-Agent", AGENT)
                    .GET()
                    .build();
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200 || res.body().length < 1024) {
                return false;
            }
            Files.createDirectories(target.toAbsolutePath().getParent());
            Files.write(target, res.body());
            return true;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private String get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> res =
                http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) {
            throw new IOException("Open Library responded with " + res.statusCode());
        }
        return res.body();
    }

    /** The jscmd=data shape wraps each name in an object. */
    private static List<String> names(Map<String, Object> entry, String field) {
        List<String> out = new ArrayList<>();
        for (Object o : Json.array(entry.get(field))) {
            String name = Json.str(Json.object(o), "name");
            if (name != null && !name.isBlank()) {
                out.add(name.strip());
            }
        }
        return out;
    }

    private static String firstName(Map<String, Object> entry, String field) {
        List<String> found = names(entry, field);
        return found.isEmpty() ? null : found.get(0);
    }

    private static List<String> strings(List<Object> values) {
        List<String> out = new ArrayList<>();
        for (Object o : values) {
            if (o instanceof String s && !s.isBlank()) {
                out.add(s.strip());
            }
        }
        return out;
    }

    /** "2020", "March 2020" and "2020-03-01" all occur in this field. */
    static Integer yearOf(String date) {
        if (date == null) {
            return null;
        }
        Matcher m = YEAR.matcher(date);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
