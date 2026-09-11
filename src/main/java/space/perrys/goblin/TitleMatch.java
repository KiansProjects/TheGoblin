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
 * are the real episode titles and do line up; they are only spelled loosely.
 *
 * So the numbers are thrown away and the title is matched, after folding both
 * sides down to letters and digits. Three differences carry no meaning and get
 * their own tier rather than being paid for out of the edit distance, where a
 * short title cannot afford them: a part number only one side writes, a
 * leading article, and one word of two spellings. An entry that does not match
 * well enough, or that matches two episodes equally well, is reported rather
 * than guessed at - the same rule {@code --from-title} follows.
 */
final class TitleMatch {

    /**
     * @param season  season number on TMDb
     * @param episode episode number within that season
     * @param title   episode title as TMDb spells it
     */
    record Ref(int season, int episode, String title) {
    }

    /**
     * One title in the three shapes it gets compared in.
     *
     * @param folded lowercase letters, digits and single spaces
     * @param base   without a trailing part number
     * @param stem   without a trailing part number or a leading article
     * @param words  the stem split on spaces
     */
    private record Shape(String folded, String base, String stem, List<String> words) {

        static Shape of(String text) {
            String folded = fold(text);
            String base = PART.matcher(folded).replaceAll("").strip();
            String stem = ARTICLE.matcher(base).replaceFirst("");
            return new Shape(folded, base, stem,
                    stem.isEmpty() ? List.of() : List.of(stem.split(" ")));
        }
    }

    private record Candidate(Ref ref, Shape shape) {
    }

    /**
     * TMDb marks the halves of a two-parter as "(1)" and "(2)", uploaders
     * usually do not. Folded that is a trailing " 1", which costs two
     * characters of edit distance - so whether a part matched came down to
     * whether its title was long enough to afford them out of a budget of one
     * character in ten.
     */
    private static final Pattern PART = Pattern.compile("\\s(?:part|teil)?\\s*[1-9]$");

    /**
     * "The Tent Situation" against TMDb's "A Tent Situation", "Chikorita
     * Rescue" against "The Chikorita Rescue". Three characters of edit
     * distance for a word that says nothing about which episode is meant.
     */
    private static final Pattern ARTICLE = Pattern.compile("^(?:the|a|an) ");

    /** "Haunter vs. Kadabra" against TMDb's "Haunter Versus Kadabra". */
    private static final Pattern VERSUS = Pattern.compile("(?<=^| )vs(?= |$)");

    /** The shortest word that may match a longer one it starts. */
    private static final int PREFIX = 4;

    private final List<Candidate> candidates = new ArrayList<>();

    /** Package-private so the matching can be exercised without TMDb. */
    TitleMatch() {
    }

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
        Shape shape = Shape.of(title);
        if (!shape.folded().isEmpty()) {
            candidates.add(new Candidate(new Ref(season, episode, title), shape));
        }
    }

    int size() {
        return candidates.size();
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
     * @return the episode this video title names, or empty when nothing
     *         matches well enough or two episodes match equally well
     */
    Optional<Ref> find(String videoTitle) {
        List<Shape> shapes = shapes(videoTitle);
        if (shapes.isEmpty()) {
            return Optional.empty();
        }

        Ref best = null;
        int bestScore = 0;
        boolean tied = false;

        for (Candidate candidate : candidates) {
            int score = score(shapes, candidate);
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
     * The best few episodes for a title that did not match, best first. Two
     * entries with the same score are why a video is skipped even though one
     * of them looks right.
     */
    List<String> nearest(String videoTitle, int count) {
        List<Shape> shapes = shapes(videoTitle);
        List<Candidate> ranked = new ArrayList<>(candidates);
        ranked.sort((a, b) -> Integer.compare(score(shapes, b), score(shapes, a)));

        List<String> lines = new ArrayList<>();
        for (Candidate candidate : ranked) {
            int score = score(shapes, candidate);
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

    /**
     * The whole title, plus each of its pipe-separated pieces. Uploads read
     * "Episode title | FULL EPISODE 4 | Season 2", so the episode title is
     * usually one piece on its own - which keeps the comparisons below from
     * being swamped by the decoration around it.
     */
    private static List<Shape> shapes(String videoTitle) {
        if (videoTitle == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<Shape> shapes = new ArrayList<>();
        for (String piece : (videoTitle + "|" + videoTitle).split("\\|")) {
            Shape shape = Shape.of(piece);
            if (!shape.folded().isEmpty() && seen.add(shape.folded())) {
                shapes.add(shape);
            }
        }
        return shapes;
    }

    /**
     * Higher is a better match, zero means none. The tiers are ordered by how
     * much the difference between the two spellings can mean: nothing at all,
     * then a word that might, then mere similarity. The length of the episode
     * title is added within a tier so that the longer of two episodes whose
     * titles are prefixes of each other wins.
     */
    private static int score(List<Shape> shapes, Candidate candidate) {
        Shape episode = candidate.shape();
        int best = 0;
        for (Shape video : shapes) {
            if (video.folded().equals(episode.folded())) {
                best = Math.max(best, 1_000_000 + episode.folded().length());
            } else if (video.base().equals(episode.base())) {
                best = Math.max(best, 900_000 + episode.base().length());
            } else if (!episode.stem().isEmpty() && video.stem().equals(episode.stem())) {
                best = Math.max(best, 800_000 + episode.stem().length());
            } else if (oneWordApart(video.words(), episode.words())) {
                best = Math.max(best, 700_000 + episode.stem().length());
            } else if ((" " + video.folded() + " ").contains(" " + episode.folded() + " ")) {
                best = Math.max(best, 1_000 + episode.folded().length());
            } else {
                int allowed = tolerance(episode.folded());
                int distance = distance(video.folded(), episode.folded(), allowed);
                if (distance <= allowed) {
                    best = Math.max(best, 100 + episode.folded().length() - distance);
                }
            }
        }
        return best;
    }

    /**
     * Every word the same but one, and that one only another spelling of the
     * same word: "Sick Days" against "Sick Daze", "Play with Fire" against
     * "Playing with Fire". Everything else matching exactly is what makes this
     * safe - a title that differs in a word that carries meaning, "Round One"
     * against "Round Two", is three characters apart and stays out.
     */
    private static boolean oneWordApart(List<String> video, List<String> episode) {
        if (video.size() != episode.size() || video.size() < 2) {
            return false;
        }
        int odd = -1;
        for (int i = 0; i < video.size(); i++) {
            if (!video.get(i).equals(episode.get(i))) {
                if (odd >= 0) {
                    return false;
                }
                odd = i;
            }
        }
        if (odd < 0) {
            return false;
        }

        String a = video.get(odd);
        String b = episode.get(odd);
        String shorter = (a.length() <= b.length()) ? a : b;
        String longer = (a.length() <= b.length()) ? b : a;
        return distance(a, b, 2) <= 2
                || (shorter.length() >= PREFIX && longer.startsWith(shorter));
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
}
