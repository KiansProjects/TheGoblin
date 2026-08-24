package space.perrys.goblin;

import java.util.List;

/**
 * Was TheGoblin ueber ein Video wissen muss.
 *
 * @param id          YouTube-ID
 * @param title       Videotitel
 * @param description Beschreibungstext, Quelle fuer die Zeitstempel-Suche
 * @param duration    Laenge in Sekunden
 * @param chapters    Kapitel, die YouTube selbst schon erkannt hat
 */
record VideoMeta(String id, String title, String description, double duration, List<Chapter> chapters) {
}
