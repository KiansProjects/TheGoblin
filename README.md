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

## Speicherplatz

Der Download läuft über ein Arbeitsverzeichnis, das TheGoblin im aktuellen Verzeichnis anlegt — nicht in `/tmp`. In einem Wings-Container ist `/tmp` ein tmpfs mit wenigen hundert Megabyte, und Video plus Tonspur plus gemuxte Datei sprengen das sofort.

Rechne mit etwa dem Dreifachen der Videogröße als freiem Platz: getrennte Video- und Audiodatei, die gemuxte mp4, dazu die geschnittenen Episoden. Bei einem 170-MB-Video also rund 700 MB.

Mit `GOBLIN_TMP` lässt sich ein anderes Arbeitsverzeichnis setzen, etwa auf einem Mount mit mehr Platz.

## Wenn YouTube blockt

Manche Videos verlangen einen bestimmten Player-Client oder eine angemeldete Sitzung. Erkennbar daran, dass das Video im Browser läuft, yt-dlp aber `This video is not available` meldet.

Zwei Stellschrauben, beide ohne Codeänderung:

**Zusatzargumente** über die Umgebungsvariable `YTDLP_ARGS` — wird an jeden yt-dlp-Aufruf angehängt:

```
--extractor-args "youtube:player_client=web_safari,default"
```

Zum Nachsehen, was tatsächlich passiert: `chapters <url> --verbose`. Das druckt das vollständige yt-dlp-Kommando und reicht dessen Meldungen durch, statt sie mit `--no-warnings` zu schlucken. Damit siehst du, ob deine Zusatzargumente ankommen und welche Player-Clients yt-dlp probiert hat.

**Cookies**: liegt eine `cookies.txt` im Arbeitsverzeichnis, benutzt TheGoblin sie automatisch. Export im Netscape-Format, z.B. über eine Browser-Erweiterung.

Eine Cookie-Datei ist eine angemeldete Sitzung deines Kontos — behandle sie wie ein Passwort, und rechne damit, dass YouTube automatisiertes Herunterladen mit einem Konto ungern sieht. Für den Anfang lieber erst die Player-Client-Variante probieren.
