package space.perrys.goblin;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads season and episode number out of a video title.
 *
 * Playlists are often out of order or mix seasons together. When the title
 * carries the numbers, it is the more reliable source than the position in
 * the list.
 */
final class TitleNumbers {

    /**
     * A pattern that names both numbers, and which of its two groups is the
     * season.
     */
    private record Rule(Pattern pattern, boolean seasonFirst) {
    }

    /** Order matters: the least ambiguous patterns first. */
    private static final List<Rule> BOTH = List.of(
            // S01E02, S1 E2, s1e2, S01 x 02
            new Rule(Pattern.compile("\\bS\\s*(\\d{1,2})\\s*[EX]\\s*(\\d{1,3})\\b",
                    Pattern.CASE_INSENSITIVE), true),
            // Season 1 Episode 2, Staffel 1 Folge 2, Series 1 Ep 2
            new Rule(Pattern.compile("\\b(?:Season|Staffel|Series|Sezon|Sezonu)\\s*(\\d{1,2})\\b.{0,40}?"
                            + "\\b(?:Episode|Folge|Ep\\.?|Odcinek)\\s*(\\d{1,3})\\b",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL), true),
            // Full Episode 2 | Season 1 - the official channels put the
            // episode first, which without this reads as an episode number
            // alone and files every season into --season.
            new Rule(Pattern.compile("\\b(?:Episode|Folge|Ep\\.?|Odcinek)\\s*(\\d{1,3})\\b.{0,40}?"
                            + "\\b(?:Season|Staffel|Series|Sezon|Sezonu)\\s*(\\d{1,2})\\b",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL), false),
            // 1x02
            new Rule(Pattern.compile("\\b(\\d{1,2})\\s*x\\s*(\\d{1,3})\\b",
                    Pattern.CASE_INSENSITIVE), true));

    /** Episode only, without a season - that one then comes from --season. */
    private static final Pattern EPISODE_ONLY = Pattern.compile(
            "\\b(?:Episode|Folge|Ep\\.?)\\s*(\\d{1,3})\\b", Pattern.CASE_INSENSITIVE);

    /**
     * @param season season number
     * @param episode episode number
     */
    record Ref(int season, int episode) {
    }

    private TitleNumbers() {
    }

    /**
     * @param fallbackSeason used when the title names an episode number only
     */
    static Optional<Ref> parse(String title, int fallbackSeason) {
        if (title == null || title.isBlank()) {
            return Optional.empty();
        }

        for (Rule rule : BOTH) {
            Matcher m = rule.pattern().matcher(title);
            if (m.find()) {
                int first = Integer.parseInt(m.group(1));
                int second = Integer.parseInt(m.group(2));
                return Optional.of(rule.seasonFirst()
                        ? new Ref(first, second)
                        : new Ref(second, first));
            }
        }

        Matcher m = EPISODE_ONLY.matcher(title);
        if (m.find()) {
            return Optional.of(new Ref(fallbackSeason, Integer.parseInt(m.group(1))));
        }

        return Optional.empty();
    }
}
