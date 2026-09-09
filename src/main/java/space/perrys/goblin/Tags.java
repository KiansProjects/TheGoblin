package space.perrys.goblin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the tags an audio file carries.
 *
 * For sorting music this is the good source, the way ComicInfo.xml is for
 * comics: whoever ripped or bought the album wrote artist, album and track
 * number into the file, and that survives every rename on the way here. The
 * file name is the fallback, not the other way round.
 *
 * ffprobe does the reading - it is already required for everything else, so
 * this costs no new dependency.
 */
final class Tags {

    /**
     * @param artist      track artist
     * @param albumArtist album artist, what the folder should be named after
     * @param album       album title
     * @param title       track title
     * @param track       track number, null when untagged
     */
    record Audio(String artist, String albumArtist, String album, String title, Integer track) {

        /** The name the artist folder gets: the album artist, or the track artist. */
        String folderArtist() {
            return (albumArtist != null) ? albumArtist : artist;
        }

        /** Whether there is enough here to place the file at all. */
        boolean usable() {
            return folderArtist() != null && album != null && title != null;
        }
    }

    private Tags() {
    }

    /** @return the file's tags, or null when ffprobe could not read it */
    static Audio read(Path file) {
        try {
            return parse(Proc.capture(List.of(
                    "ffprobe", "-v", "error",
                    "-show_format",
                    "-print_format", "json",
                    file.toString())));
        } catch (IOException | InterruptedException | RuntimeException e) {
            return null;
        }
    }

    /** Split out from {@link #read} so the parsing can be exercised offline. */
    static Audio parse(String json) {
        Map<String, Object> format = Json.object(Json.object(Json.parse(json)).get("format"));
        Map<String, Object> tags = Json.object(format.get("tags"));
        if (tags.isEmpty()) {
            return null;
        }

        // Containers disagree about capitalisation - Vorbis comments are
        // upper case, ID3 is not - and ffprobe passes that through.
        Map<String, Object> lower = new java.util.HashMap<>();
        for (var entry : tags.entrySet()) {
            lower.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
        }

        return new Audio(
                text(lower, "artist"),
                first(text(lower, "album_artist"), text(lower, "albumartist")),
                text(lower, "album"),
                text(lower, "title"),
                number(first(text(lower, "track"), text(lower, "tracknumber"))));
    }

    private static String text(Map<String, Object> tags, String key) {
        Object value = tags.get(key);
        if (!(value instanceof String s)) {
            return null;
        }
        String stripped = s.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static String first(String a, String b) {
        return (a != null) ? a : b;
    }

    /** Track numbers are often written as "3/12". */
    private static Integer number(String value) {
        if (value == null) {
            return null;
        }
        String head = value.split("/")[0].strip();
        try {
            int n = Integer.parseInt(head);
            return (n > 0) ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
