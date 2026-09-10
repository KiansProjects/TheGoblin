package space.perrys.goblin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds a first guess at where the episodes in a compilation begin, from the
 * run times TMDb has for that season.
 *
 * A feature-length upload of several episodes usually carries no chapters, so
 * there is nothing to read out of it. What is known is how long each episode
 * of the season runs, and laid end to end that gives a boundary list.
 *
 * It is a guess and stays one. TMDb rounds to whole minutes, and a compilation
 * carries its own title card and transitions that the broadcast version never
 * had - so the guess drifts, and the later the episode the further. It is only
 * useful together with --snap, which pulls each boundary onto the black frame
 * that is actually there. The rolling correction in {@code Goblin.snapRolling}
 * is what keeps the drift from accumulating.
 *
 * The number of episodes is derived rather than asked for: they are taken
 * until their run times fill the file. One too many and every boundary after
 * it would sit past the end of the video.
 */
final class Runtimes {

    /** Below this much remaining file there is no room for another episode. */
    private static final double LEFTOVER = 60;

    private Runtimes() {
    }

    /**
     * @param episodes     the season as TMDb has it, in order
     * @param firstEpisode number of the episode the file starts with
     * @param videoSeconds length of the downloaded file
     * @return one chapter per episode that fits, empty when none does
     */
    static List<Chapter> chapters(List<Tmdb.Episode> episodes, int firstEpisode,
                                  double videoSeconds) {
        List<Tmdb.Episode> fitting = new ArrayList<>();
        double sum = 0;

        for (Tmdb.Episode episode : episodes) {
            if (episode.number() < firstEpisode) {
                continue;
            }
            if (sum >= videoSeconds - LEFTOVER) {
                break;
            }
            if (episode.seconds() == null) {
                System.out.println("  TMDb has no run time for episode " + episode.number()
                        + " - stopping there, everything after it would be guesswork.");
                break;
            }
            fitting.add(episode);
            sum += episode.seconds();
        }

        if (fitting.isEmpty()) {
            return List.of();
        }

        List<Chapter> parts = new ArrayList<>();
        double start = 0;
        for (int i = 0; i < fitting.size(); i++) {
            Tmdb.Episode episode = fitting.get(i);
            double end = (i + 1 < fitting.size()) ? start + episode.seconds() : videoSeconds;
            parts.add(new Chapter(start, end, episode.name()));
            start += episode.seconds();
        }

        report(fitting, sum, videoSeconds);
        return parts;
    }

    private static void report(List<Tmdb.Episode> fitting, double sum, double videoSeconds) {
        System.out.printf("TMDb run times: %d episodes (%d to %d), %s together, "
                + "the file runs %s.%n",
                fitting.size(), fitting.get(0).number(),
                fitting.get(fitting.size() - 1).number(),
                time(sum), time(videoSeconds));

        double extra = videoSeconds - sum;
        System.out.printf("  %s unaccounted for - title cards, transitions, and TMDb "
                + "rounding to whole minutes.%n", time(Math.abs(extra)));
        System.out.println("  These are estimates. Without --snap they will be wrong; "
                + "with it they are a starting point.");
    }

    private static String time(double seconds) {
        int total = (int) Math.round(Math.abs(seconds));
        return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60);
    }
}
