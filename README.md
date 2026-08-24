# TheGoblin

Zerlegt ein YouTube-Video anhand seiner Kapitel in einzelne Episodendateien und legt sie so ab, wie Jellyfins Scanner sie erwartet.

## Voraussetzungen

- Java 21+
- `yt-dlp` und `ffmpeg` im PATH

Das Projekt hat keine externen Java-Dependencies — `./build.sh` reicht, Maven ist optional (`mvn package`).

## Installation

```bash
./build.sh
sudo ln -s "$PWD/goblin" /usr/local/bin/goblin
```

## Benutzung

```bash
# Erst schauen, was drin ist
goblin chapters https://www.youtube.com/watch?v=...

# Dann zerlegen
goblin series https://www.youtube.com/watch?v=... "Ninjago" \
    --out /srv/media/serien --season 1
```

### Optionen

| Option | Bedeutung |
|---|---|
| `-o, --out <pfad>` | Zielverzeichnis, Standard aktuelles Verzeichnis |
| `-s, --season <n>` | Staffelnummer, Standard 1 |
| `-e, --start-episode <n>` | Nummer der ersten Episode, Standard 1 |
| `--year <jahr>` | Erscheinungsjahr, überschreibt TMDb |
| `--tmdb-id <id>` | Serien-ID fest vorgeben statt zu suchen |
| `--no-tmdb` | keine Datenbankabfrage, kein Artwork |
| `--reencode` | exakt schneiden statt auf Keyframes zu runden |
| `--keep` | das komplette Video nach dem Schneiden behalten |
| `--dry-run` | nur zeigen, was passieren würde |

## Ergebnis

```
Ninjago (2011) [tmdbid-12345]/
  poster.jpg
  backdrop.jpg
  Season 01/
    Ninjago S01E01 - Way of the Ninja.mp4
    Ninjago S01E02 - The Golden Weapon.mp4
```

## Serien-Datenbank

Mit einem kostenlosen TMDb-Key holt TheGoblin Serien-ID, Erstausstrahlungsjahr, Poster und Hintergrundbild:

```bash
export TMDB_API_KEY=dein_key
```

Die ID landet im Ordnernamen, damit Jellyfin die Serie nicht selbst erraten muss. Ohne Key läuft alles andere unverändert.

## Wie die Kapitel gefunden werden

1. Hat YouTube die Zeitstempel als Kapitel erkannt, nutzt `yt-dlp` sie direkt — das ist der zuverlässige Fall.
2. Sonst wird die Beschreibung durchsucht. Zuerst nur Zeilen, die mit einem Zeitstempel beginnen (die übliche Kapitelliste). Findet das nichts, wird lockerer gesucht, etwa `Episode 1 - Way of the Ninja - 0:00`.

Zeilen wie `New videos at 10:00 AM every Saturday` oder `Live um 20:15 Uhr` werden aussortiert — sonst hättest du eine Episode namens „AM every Saturday" in der Mediathek.

Der letzte Abschnitt läuft immer bis zum Videoende. Bei Sammelvideos mit Abspann oder Werbung am Schluss lohnt sich vorher ein `goblin chapters`.

## Schnittgenauigkeit

Standardmäßig wird ohne Neukodierung geschnitten (`-c copy`). Das dauert Sekunden statt Minuten, aber ffmpeg kann nur an Keyframes schneiden — die Grenzen liegen dadurch bis zu ein paar Sekunden daneben, und der Anfang kann kurz einfrieren.

Bei aneinandergeschnittenen Episoden fällt das kaum auf. Wenn es stört: `--reencode`. Dann sitzt der Schnitt exakt, kostet aber CPU-Zeit und einen Hauch Qualität.

## Download-Format

Bevorzugt wird H.264 mit AAC in mp4. YouTube liefert sonst VP9 oder AV1 in webm, was viele Clients nicht direkt abspielen — dann transcodiert der Server, und bei AV1 mangels Hardware-Decoder komplett auf der CPU.

## Ideen für später

- `goblin playlist <url>` für ganze Playlists, eine Episode pro Video statt pro Kapitel
- Erkennung, ob eine Episode schon existiert, statt blind zu überschreiben
- `--map` mit einer Datei, die Kapitelnummer auf Episodennummer abbildet, für Fälle wo TMDb anders zählt als das Video
- Wenn du es als echtes Kommando willst: Quarkus mit `quarkus-picocli` und Native Image gibt dir ein Binary ohne JVM-Startzeit
