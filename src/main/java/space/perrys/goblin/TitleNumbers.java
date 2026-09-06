package space.perrys.goblin;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest Staffel- und Folgennummer aus einem Videotitel.
 *
 * Playlists sind haeufig durcheinander sortiert oder mischen Staffeln. Wenn
 * der Titel die Nummern traegt, ist er die verlaesslichere Quelle als die
 * Position in der Liste.
 */
final class TitleNumbers {

    /** Reihenfolge zaehlt: die eindeutigsten Muster zuerst. */
    private static final List<Pattern> BOTH = List.of(
            // S01E02, S1 E2, s1e2, S01 x 02
            Pattern.compile("\\bS\\s*(\\d{1,2})\\s*[EX]\\s*(\\d{1,3})\\b", Pattern.CASE_INSENSITIVE),
            // Season 1 Episode 2, Staffel 1 Folge 2, Series 1 Ep 2
            Pattern.compile("\\b(?:Season|Staffel|Series|Sezon|Sezonu)\\s*(\\d{1,2})\\b.{0,40}?"
                            + "\\b(?:Episode|Folge|Ep\\.?|Odcinek)\\s*(\\d{1,3})\\b",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
            // 1x02
            Pattern.compile("\\b(\\d{1,2})\\s*x\\s*(\\d{1,3})\\b", Pattern.CASE_INSENSITIVE));

    /** Nur die Folge, ohne Staffel - die kommt dann von --season. */
    private static final Pattern EPISODE_ONLY = Pattern.compile(
            "\\b(?:Episode|Folge|Ep\\.?)\\s*(\\d{1,3})\\b", Pattern.CASE_INSENSITIVE);

    /**
     * @param season Staffelnummer
     * @param episode Folgennummer
     */
    record Ref(int season, int episode) {
    }

    private TitleNumbers() {
    }

    /**
     * @param fallbackSeason wird benutzt, wenn der Titel nur eine Folgennummer nennt
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
