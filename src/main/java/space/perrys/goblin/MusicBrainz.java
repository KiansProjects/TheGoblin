package space.perrys.goblin;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Music database. Provides the MusicBrainz IDs that Jellyfin identifies albums
 * and artists by.
 *
 * This is the music counterpart of {@link Tmdb}, but the mechanism differs:
 * TMDb IDs travel in the folder name, MusicBrainz IDs have to sit in the tags
 * of the files themselves - Jellyfin ignores file and folder names for music
 * and reads the embedded metadata instead.
 *
 * No API key. MusicBrainz asks for an application-specific User-Agent and at
 * most one request per second instead; a run does one lookup, so the rate limit
 * never comes into play here.
 */
final class MusicBrainz {

    private static final String API = "https://musicbrainz.org/ws/2";

    /** Anonymous requests get blocked, so identify the application. */
    private static final String AGENT =
            "TheGoblin/0.1.0 ( https://github.com/KiansProjects/TheGoblin )";

    private final HttpClient http;

    MusicBrainz() {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    /**
     * @param id             release ID, the one Jellyfin wants as MusicBrainz Album Id
     * @param releaseGroupId ID of the release group, may be null
     * @param artistId       ID of the first credited artist, may be null
     * @param title          release title as MusicBrainz spells it
     * @param artist         artist name as MusicBrainz spells it
     */
    record Release(String id, String releaseGroupId, String artistId, String title, String artist) {
    }

    /**
     * Searches for a release. Takes the first hit, like the TMDb lookups do -
     * for a YouTube upload that is a fuzzy match, so the caller prints what was
     * found and --mbid exists to pin it.
     *
     * @return null when nothing matched
     */
    Release search(String artist, String album) throws IOException, InterruptedException {
        StringBuilder query = new StringBuilder("release:\"").append(escape(album)).append('"');
        if (artist != null && !artist.isBlank()) {
            query.append(" AND artist:\"").append(escape(artist)).append('"');
        }

        String url = API + "/release/?query="
                + URLEncoder.encode(query.toString(), StandardCharsets.UTF_8)
                + "&limit=1&fmt=json";

        Map<String, Object> root = Json.object(Json.parse(get(url)));
        List<Object> releases = Json.array(root.get("releases"));
        return releases.isEmpty() ? null : toRelease(Json.object(releases.get(0)));
    }

    /** Looks a release up directly, for when the search picks the wrong one. */
    Release byId(String mbid) throws IOException, InterruptedException {
        String url = API + "/release/" + mbid + "?inc=release-groups+artist-credits&fmt=json";
        return toRelease(Json.object(Json.parse(get(url))));
    }

    private static Release toRelease(Map<String, Object> m) {
        String id = Json.str(m, "id");
        if (id == null) {
            return null;
        }

        String groupId = Json.str(Json.object(m.get("release-group")), "id");

        String artistId = null;
        String artistName = null;
        List<Object> credits = Json.array(m.get("artist-credit"));
        if (!credits.isEmpty()) {
            Map<String, Object> artist = Json.object(Json.object(credits.get(0)).get("artist"));
            artistId = Json.str(artist, "id");
            artistName = Json.str(artist, "name");
        }

        return new Release(id, groupId, artistId, Json.str(m, "title"), artistName);
    }

    /**
     * Keeps a title from breaking out of the quoted Lucene term. Only the two
     * characters that can do that are removed; the rest of the punctuation is
     * fine inside quotes and dropping it would cost matches.
     */
    private static String escape(String s) {
        return s.replace("\\", " ").replace("\"", " ").strip();
    }

    private String get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) {
            throw new IOException("MusicBrainz responded with " + res.statusCode());
        }
        return res.body();
    }
}
