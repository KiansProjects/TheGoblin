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

    /** Order matters: the least ambiguous patterns first. */
    private static final List<Pattern> BOTH = List.of(
            // S01E02, S1 E2, s1e2, S01 x 02
            Pattern.compile("\\bS\\s*(\\d{1,2})\\s*[EX]\\s*(\\d{1,3})\\b", Pattern.CASE_INSENSITIVE),
            // Season 1 Episode 2, Staffel 1 Folge 2, Series 1 Ep 2
            Pattern.compile("\\b(?:Season|Staffel|Series|Sezon|Sezonu)\\s*(\\d{1,2})\\b.{0,40}?"
                            + "\\b(?:Episode|Folge|Ep\\.?|Odcinek)\\s*(\\d{1,3})\\b",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
            // 1x02
            Pattern.compile("\\b(\\d{1,2})\\s*x\\s*(\\d{1,3})\\b", Pattern.CASE_INSENSITIVE));

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

        for (Pattern p : BOTH) {
            Matcher m = p.matcher(title);
            if (m.find()) {
                return Optional.of(new Ref(
                        Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2))));
            }
        }

        Matcher m = EPISODE_ONLY.matcher(title);
        if (m.find()) {
            return Optional.of(new Ref(fallbackSeason, Integer.parseInt(m.group(1))));
        }

        return Optional.empty();
    }
}
