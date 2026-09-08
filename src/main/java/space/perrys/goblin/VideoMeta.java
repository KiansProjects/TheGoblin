package space.perrys.goblin;

import java.util.List;

/**
 * What TheGoblin needs to know about a video.
 *
 * @param id          YouTube ID
 * @param title       video title
 * @param description description text, the source for the timestamp search
 * @param duration    length in seconds
 * @param chapters    chapters YouTube already recognised itself
 */
record VideoMeta(String id, String title, String description, double duration, List<Chapter> chapters) {
}
