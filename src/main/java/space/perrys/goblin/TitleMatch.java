package space.perrys.goblin;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds an episode by its title instead of by a number.
 *
 * The official channels count their uploads their own way. The "All Pokemon
 * episodes (in order)" playlist, for one, splits the first 118 dubbed episodes
 * into a season of 52 and a season of 60, where TMDb has 82 and 36 - so every
 * number in those titles points at the wrong episode. The titles themselves
 * are the real episode titles and do line up; they are only spelled loosely,
 * with a stray accent, a curly apostrophe or an em dash where TMDb has a
 * plain one.
 *
 * So the numbers are thrown away and the title is matched, after folding both
 * sides down to letters and digits. An entry that does not match well enough,
 * or that matches two episodes equally well, is reported rather than guessed
 * at - the same rule {@code --from-title} follows.
 */
final class TitleMatch {

    /**
     * @param season  season number on TMDb
     * @param episode episode number within that season
     * @param title   episode title as TMDb spells it
     */
    record Ref(int season, int episode, String title) {
    }

    private record Candidate(Ref ref, String folded, String base) {
    }

    /**
     * TMDb marks the halves of a two-parter as "(1)" and "(2)", uploaders
     * usually do not. Folded that is a trailing " 1", which costs two
     * characters of edit distance - so whether a part matched came down to
     * whether its title was long enough to afford them out of a budget of one
     * character in ten. It is matched on its own tier instead.
     */
    private static final Pattern PART = Pattern.compile("\\s(?:part|teil)?\\s*[1-9]$");

    /** "Haunter vs. Kadabra" against TMDb's "Haunter Versus Kadabra". */
    private static final Pattern VERSUS = Pattern.compile("(?<=^| )vs(?= |$)");

    private final List<Candidate> candidates = new ArrayList<>();

    /** Reads every season of the series. Around one request per season. */
    static TitleMatch of(Tmdb tmdb, int seriesId) throws IOException, InterruptedException {
        TitleMatch match = new TitleMatch();
        for (int season : tmdb.seasons(seriesId)) {
            for (Tmdb.Episode episode : tmdb.season(seriesId, season)) {
                match.add(season, episode.number(), episode.name());
            }
        }
        return match;
    }

    void add(int season, int episode, String title) {
        String folded = fold(title);
        if (!folded.isEmpty()) {
            candidates.add(new Candidate(new Ref(season, episode, title), folded, base(folded)));
        }
    }

    /** The title without a trailing part number. Unchanged when there is none. */
    static String base(String folded) {
        return PART.matcher(folded).replaceAll("").strip();
    }

    int size() {
        return candidates.size();
    }

    /**
     * @return the episode this video title names, or empty when nothing
     *         matches well enough or two episodes match equally well
     */
    Optional<Ref> find(String videoTitle) {
        List<String> parts = parts(videoTitle);
        if (parts.isEmpty()) {
            return Optional.empty();
        }

        Ref best = null;
        int bestScore = 0;
        boolean tied = false;

        for (Candidate candidate : candidates) {
            int score = score(parts, candidate);
            if (score > bestScore) {
                best = candidate.ref();
                bestScore = score;
                tied = false;
            } else if (score == bestScore && score > 0) {
                tied = true;
            }
        }

        return (best == null || tied) ? Optional.empty() : Optional.of(best);
    }

    /**
     * The best few episodes for a title that did not match, worst reason
     * first to read. Two entries with the same score are why a video is
     * skipped even though one of them looks right.
     */
    List<String> nearest(String videoTitle, int count) {
        List<String> parts = parts(videoTitle);
        List<Candidate> ranked = new ArrayList<>(candidates);
        ranked.sort((a, b) -> Integer.compare(score(parts, b), score(parts, a)));

        List<String> lines = new ArrayList<>();
        for (Candidate candidate : ranked) {
            int score = score(parts, candidate);
            // An episode that scored nothing is not a near miss, it is an
            // unrelated title. Listing it says less than saying nothing.
            if (score == 0 || lines.size() == count) {
                break;
            }
            lines.add(String.format("S%02dE%02d  %s  (score %d)",
                    candidate.ref().season(), candidate.ref().episode(),
                    candidate.ref().title(), score));
        }
        return lines;
    }

    /** Every episode read from TMDb, in the order it was added. */
    List<Ref> all() {
        List<Ref> refs = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            refs.add(candidate.ref());
        }
        return refs;
    }

    /**
     * The whole title, plus each of its pipe-separated pieces. Uploads read
     * "Episode title | FULL EPISODE 4 | Season 2", so the episode title is
     * usually one piece on its own - which keeps the edit distance below from
     * being swamped by the decoration around it.
     */
    static List<String> parts(String videoTitle) {
        if (videoTitle == null) {
            return List.of();
        }
        Set<String> parts = new LinkedHashSet<>();
        String whole = fold(videoTitle);
        if (!whole.isEmpty()) {
            parts.add(whole);
        }
        for (String piece : videoTitle.split("\\|")) {
            String folded = fold(piece);
            if (!folded.isEmpty()) {
                parts.add(folded);
            }
        }
        return List.copyOf(parts);
    }

    /**
     * Higher is a better match. Zero means no match at all.
     *
     * The length of the episode title is added so that the longer of two
     * episodes whose titles are prefixes of each other wins.
     */
    private static int score(List<String> parts, Candidate candidate) {
        String folded = candidate.folded();
        int best = 0;
        for (String part : parts) {
            if (part.equals(folded)) {
                best = Math.max(best, 1_000_000 + folded.length());
                continue;
            }
            // The same title but for a part number on one side only. Below an
            // exact match, above anything merely similar.
            if (base(part).equals(candidate.base())) {
                best = Math.max(best, 900_000 + candidate.base().length());
                continue;
            }
            if ((" " + part + " ").contains(" " + folded + " ")) {
                best = Math.max(best, 1_000 + folded.length());
                continue;
            }
            int allowed = tolerance(folded);
            int distance = distance(part, folded, allowed);
            if (distance <= allowed) {
                best = Math.max(best, 100 + folded.length() - distance);
            }
        }
        return best;
    }

    /**
     * How far apart two spellings of the same title may be. One character in
     * ten, and never less than one so that a single dropped letter still
     * matches a short title.
     */
    static int tolerance(String folded) {
        return Math.max(1, folded.length() / 10);
    }

    /**
     * Levenshtein distance, abandoned once every way through costs more than
     * {@code limit}. The return value is then only known to be above it.
     */
    static int distance(String a, String b, int limit) {
        if (Math.abs(a.length() - b.length()) > limit) {
            return limit + 1;
        }

        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }

        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            int rowBest = current[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
                rowBest = Math.min(rowBest, current[j]);
            }
            if (rowBest > limit) {
                return limit + 1;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }

        return previous[b.length()];
    }

    /**
     * Down to lowercase letters, digits and single spaces. Accents are
     * decomposed and dropped, everything else becomes a space - which is what
     * turns "Charmander-the Stray Pokemon" into the same string as
     * "Charmander - The Stray Pokemon".
     */
    static String fold(String text) {
        if (text == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(decomposed.length());
        boolean space = true;
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK) {
                continue;
            }
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
                space = false;
            } else if (!space) {
                out.append(' ');
                space = true;
            }
        }
        return VERSUS.matcher(out.toString().strip()).replaceAll("versus");
    }

    /** Package-private so the matching can be exercised without TMDb. */
    TitleMatch() {
    }
}
