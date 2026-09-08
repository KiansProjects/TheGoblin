package space.perrys.goblin;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
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
 * Besides the IDs it also serves the track lengths, which is what --trim
 * measures a download against.
 *
 * No API key. MusicBrainz asks for an application-specific User-Agent and at
 * most one request per second instead; a run makes two lookups at most, and
 * {@link #get} keeps that distance itself.
 */
final class MusicBrainz {

    private static final String API = "https://musicbrainz.org/ws/2";

    /** Anonymous requests get blocked, so identify the application. */
    private static final String AGENT =
            "TheGoblin/0.1.0 ( https://github.com/KiansProjects/TheGoblin )";

    /** MusicBrainz allows one request per second, with a little headroom. */
    private static final long SPACING_MS = 1100;

    private final HttpClient http;

    private long lastRequest;

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

    /**
     * One track of a release.
     *
     * @param position 1-based position on the medium
     * @param title    track title as MusicBrainz spells it
     * @param seconds  length of the release version, 0 when MusicBrainz has none
     */
    record Track(int position, String title, double seconds) {
    }

    /**
     * The track list of a release, in order, discs concatenated.
     *
     * A separate request: the search response carries no recordings, and asking
     * for them there would blow the response up for every lookup that never
     * needs them.
     *
     * @return an empty list when the release has no usable track list
     */
    List<Track> tracks(String releaseId) throws IOException, InterruptedException {
        return parseTracks(get(API + "/release/" + releaseId + "?inc=recordings&fmt=json"));
    }

    /** Split out from {@link #tracks} so the parsing can be exercised offline. */
    static List<Track> parseTracks(String json) {
        Map<String, Object> root = Json.object(Json.parse(json));

        List<Track> tracks = new ArrayList<>();
        for (Object medium : Json.array(root.get("media"))) {
            for (Object entry : Json.array(Json.object(medium).get("tracks"))) {
                Map<String, Object> track = Json.object(entry);

                // The track carries the length of this release's version; the
                // recording's is the one of whatever version was linked first
                // and can differ by seconds. Only fall back to it.
                double ms = Json.num(track, "length", 0);
                if (ms <= 0) {
                    ms = Json.num(Json.object(track.get("recording")), "length", 0);
                }

                tracks.add(new Track(
                        (int) Json.num(track, "position", tracks.size() + 1),
                        Json.str(track, "title"),
                        ms / 1000.0));
            }
        }
        return tracks;
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
        long since = System.currentTimeMillis() - lastRequest;
        if (lastRequest != 0 && since < SPACING_MS) {
            Thread.sleep(SPACING_MS - since);
        }
        lastRequest = System.currentTimeMillis();

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
