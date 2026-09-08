package space.perrys.goblin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Cuts a downloaded track back to the length the album version has.
 *
 * A music video is not the album track. It carries an intro, a spoken outro,
 * applause, a fade that runs on - anything from two seconds to a minute of
 * material that does not belong on the record. In a music collection that
 * shows up as a run time that disagrees with every other copy of the song.
 *
 * MusicBrainz knows what the track is supposed to be long, so the difference
 * is measurable rather than guessed. Only the end is cut: extra material at
 * the start would have to be found, not derived, and cutting the wrong end
 * would take the first bar of the song with it.
 */
final class Trim {

    /**
     * How much longer than the release version a file may be before it counts
     * as an outro. Encoders, the fade at the end and the odd sloppy upload
     * account for a couple of seconds on their own.
     */
    static final double DEFAULT_TOLERANCE = 4.0;

    private static final Set<String> AUDIO =
            Set.of("mp3", "m4a", "opus", "ogg", "flac", "wav", "aac", "webm", "mka");

    /** Noise the file name picks up from a YouTube title. */
    private static final String[] SUFFIXES = {
        "officialmusicvideo", "officialvideo", "officialaudio", "officiallyricvideo",
        "musicvideo", "lyricvideo", "audioonly", "official", "lyrics", "hd", "hq",
        "remastered", "explicit", "audio", "video",
    };

    private Trim() {
    }

    /**
     * Measures every track in the folder against the release and shortens the
     * ones that run long.
     *
     * Reports rather than throws: a track that cannot be matched, or a length
     * MusicBrainz does not have, is a gap in the data, not a failed download.
     *
     * @param tolerance seconds a file may exceed the release version by
     */
    static void run(Path albumDir, List<MusicBrainz.Track> tracks, double tolerance) {
        if (tracks.isEmpty()) {
            System.out.println("Trim: MusicBrainz has no track list for this release, "
                    + "nothing to measure against.");
            return;
        }
        if (!Files.isDirectory(albumDir)) {
            return;
        }

        List<Path> files;
        try (var entries = Files.list(albumDir)) {
            files = entries.filter(Files::isRegularFile)
                    .filter(Trim::isAudio)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            System.out.println("Trim: could not read " + albumDir + ": " + e.getMessage());
            return;
        }

        System.out.printf("%nTrim: %d files against %d MusicBrainz tracks, "
                + "tolerance %.0f s.%n", files.size(), tracks.size(), tolerance);

        Set<Integer> claimed = new HashSet<>();
        List<String> unmatched = new ArrayList<>();
        int cut = 0;

        for (Path file : files) {
            MusicBrainz.Track track = match(file, tracks, claimed);
            if (track == null) {
                unmatched.add(file.getFileName().toString());
                continue;
            }
            claimed.add(track.position());

            if (shorten(file, track, tolerance)) {
                cut++;
            }
        }

        System.out.printf("Trim: %d of %d files shortened.%n", cut, files.size());
        if (!unmatched.isEmpty()) {
            System.out.println("Trim: no MusicBrainz track for " + String.join(", ", unmatched)
                    + " - left alone.");
        }
    }

    /** @return true when the file was actually shortened */
    private static boolean shorten(Path file, MusicBrainz.Track track, double tolerance) {
        String name = file.getFileName().toString();

        if (track.seconds() <= 0) {
            System.out.println("  " + name + ": MusicBrainz has no length for \""
                    + track.title() + "\", left alone.");
            return false;
        }

        double local = Ffprobe.duration(file);
        if (local <= 0) {
            System.out.println("  " + name + ": length not readable, left alone.");
            return false;
        }

        double over = local - track.seconds();
        if (over <= tolerance) {
            // Includes the case of a file that is shorter, unless it is short
            // enough to mean a different version.
            if (-over > tolerance) {
                System.out.printf("  %s: %s, but the release version is %s - "
                        + "a different version, left alone.%n",
                        name, time(local), time(track.seconds()));
            }
            return false;
        }

        // The release length says how much is too much, the trailing silence
        // says where the music actually stops. Prefer the silence when it sits
        // anywhere near the expected end - cutting there ends the file on the
        // last note instead of mid-fade.
        double at = track.seconds();
        String reason = "at the release length";

        OptionalDouble silence = OutroDetect.silenceTail(file, local);
        if (silence.isPresent()
                && silence.getAsDouble() >= track.seconds() - tolerance
                && silence.getAsDouble() < local) {
            at = silence.getAsDouble();
            reason = "at the trailing silence";
        }

        try {
            Ffmpeg.trimTo(file, at);
            System.out.printf("  %s: %s -> %s, %s (%s).%n",
                    name, time(local), time(at), reason, track.title());
            return true;
        } catch (IOException | InterruptedException e) {
            System.out.println("  " + name + ": not shortened (" + e.getMessage() + ")");
            return false;
        }
    }

    /**
     * Finds the release track a file belongs to.
     *
     * By title first, because the position in a YouTube playlist is only as
     * trustworthy as whoever put the playlist together. The number in the file
     * name is the fallback, and one that is reported as such.
     */
    private static MusicBrainz.Track match(Path file, List<MusicBrainz.Track> tracks,
                                           Set<Integer> claimed) {
        String base = baseName(file);

        // Both spellings, because the leading number is ambiguous: in
        // "01 - Papercut" it is the track number, in "99 Problems" it is the
        // title. Whichever of the two produces a hit is the right reading.
        List<String> keys = List.of(key(stripNumber(base)), key(base));

        MusicBrainz.Track best = null;
        int bestLength = -1;

        for (MusicBrainz.Track track : tracks) {
            if (claimed.contains(track.position())) {
                continue;
            }
            String trackKey = key(track.title());
            if (trackKey.isEmpty() || trackKey.length() <= bestLength) {
                continue;
            }

            // A YouTube title is the track title plus decoration, so the track
            // title being a prefix is the normal case, not the exception. The
            // longest such title wins, which keeps "Numb" from claiming the
            // file that belongs to "Numb / Encore".
            for (String key : keys) {
                if (key.startsWith(trackKey)) {
                    best = track;
                    bestLength = trackKey.length();
                    break;
                }
            }
        }

        if (best != null) {
            return best;
        }

        int number = leadingNumber(baseName(file));
        if (number > 0 && !claimed.contains(number)) {
            for (MusicBrainz.Track track : tracks) {
                if (track.position() == number) {
                    System.out.println("  " + file.getFileName()
                            + ": matched by track number, not by title (\""
                            + track.title() + "\").");
                    return track;
                }
            }
        }
        return null;
    }

    /**
     * Reduces a title to what two spellings of it have in common: lower case,
     * letters and digits only. Punctuation, spacing and the brackets around
     * "(Official Video)" differ between YouTube and MusicBrainz for the same
     * song, the letters do not.
     */
    private static String key(String s) {
        if (s == null) {
            return "";
        }
        String lower = s.toLowerCase(Locale.ROOT).replace("&", "and");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                b.append(c);
            }
        }

        // Trailing decoration would otherwise defeat an exact match; it cannot
        // defeat the prefix match, but a shorter key makes that one safer.
        String key = b.toString();
        boolean cut = true;
        while (cut) {
            cut = false;
            for (String suffix : SUFFIXES) {
                if (key.length() > suffix.length() && key.endsWith(suffix)) {
                    key = key.substring(0, key.length() - suffix.length());
                    cut = true;
                }
            }
        }
        return key;
    }

    private static String baseName(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return (dot < 0) ? name : name.substring(0, dot);
    }

    /** Removes the "01 - " the download writes in front of the title. */
    private static String stripNumber(String name) {
        return name.replaceFirst("^\\s*\\d{1,3}\\s*[-.)]?\\s+", "");
    }

    private static int leadingNumber(String name) {
        var m = java.util.regex.Pattern.compile("^\\s*(\\d{1,3})\\b").matcher(name);
        try {
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isAudio(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && AUDIO.contains(name.substring(dot + 1));
    }

    private static String time(double seconds) {
        int total = (int) Math.round(seconds);
        return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60);
    }
}
