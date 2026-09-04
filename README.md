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

Standardmäßig wird ohne Neukodierung geschnitten (`-c copy`). Das dauert Sekunden statt Minuten, hat aber eine Eigenart, die man kennen muss: ein Videostream ist nur ab einem Keyframe dekodierbar, also beginnt jeder Abschnitt am Keyframe **vor** der gewünschten Zeit. Der Abschnitt wird dadurch länger als angegeben — bei YouTube typischerweise um bis zu 2 Sekunden, weil dort etwa alle 2 Sekunden ein Keyframe sitzt.

Gemessen an einem Testvideo mit Keyframes im 2-Sekunden-Raster, Schnitt ab 11,0 s über 5,0 s:

| Modus | Ergebnis |
|---|---|
| `-c copy` | 6,08 s |
| `--reencode` | 5,00 s |

Der erste Abschnitt ist immer korrekt, weil er bei 0:00 anfängt und dort ein Keyframe liegt. Es geht nichts verloren, es ist nur vorne der Schluss des vorherigen Abschnitts mit dran.

Wenn das stört: `--reencode`. Dann sitzt der Schnitt framegenau. Nur das Bild wird neu kodiert, der Ton wird kopiert.

## Playlists

Für eine Playlist mit einem Video je Staffel:

```
playlist <playlist-url> "Ninjago"
```

Das liest die Playlist flach aus und druckt für jedes Video eine fertige `series`-Zeile, mit hochgezählter Staffelnummer und Titel als Kommentar darüber. Nichts wird dabei heruntergeladen.

Optionen: `--out <pfad>`, `--season <n>` für die erste Staffelnummer, `--extra "<optionen>"` für das, was an jede Zeile angehängt wird (Standard `--snap --reencode`).

Die Zeilen vor dem Ausführen durchsehen — ob die Reihenfolge der Playlist wirklich der Staffelreihenfolge entspricht, weiß nur der Kanal.

Lässt der Konsolen-Wrapper nur `chapters` durch, geht auch:

```
chapters <playlist-url> --playlist "Ninjago"
```

## Grenzen automatisch finden

Zeitstempel in Beschreibungen sind von Hand getippt und liegen oft ein bis zwei Sekunden daneben. Statt jeden nachzumessen:

```
series <url> "Name" --snap --reencode
```

TheGoblin sucht dann in einem Fenster um jeden Zeitstempel herum nach dem tatsächlichen Bildwechsel. Zwei Signale, in dieser Reihenfolge:

1. **Schwarzbild** — bei Episodenübergängen fast immer vorhanden. Der Schnitt landet am Ende des schwarzen Abschnitts, also am ersten Bild der neuen Folge.
2. **Harter Szenenwechsel** — falls kein Schwarzbild da ist.

Findet er nichts, bleibt der ursprüngliche Zeitstempel stehen. Im Log steht pro Grenze, was gefunden wurde:

```
Grenzen suchen (Fenster 5 s) ...
  11:00 -> 11:02  (+2.31 s, Schwarzbild)
  22:00 -> 22:01  (+1.04 s, Szenenwechsel)
```

Die Fensterbreite lässt sich angeben: `--snap 10` sucht ±10 Sekunden. Größer heißt mehr Toleranz gegenüber schlechten Zeitstempeln, aber auch mehr Risiko, einen Szenenwechsel *innerhalb* der Folge zu erwischen.

Der erste Abschnitt bleibt immer bei 0:00.

**Zusammen mit `--reencode` benutzen.** Ohne rastet der Schnitt trotzdem auf den Keyframe davor ein, und die genaue Grenze wäre wieder verschenkt.

## Eigene Zeitstempel

Stimmen die Kapitel im Video nicht, lässt sich eine eigene Liste mitgeben — gleiches Format wie eine YouTube-Beschreibung, eine Zeile je Abschnitt:

```
0:00 Way of the Ninja
11:00 The Golden Weapon
22:00 King of Shadows
33:00 Weapons of Destiny
```

```
series <url> "Name" --chapters kapitel.txt
```

Liegen alle Zeitstempel gleichmäßig daneben, reicht `--offset <sekunden>`. Der verschiebt jede Grenze ab der zweiten; die erste bleibt bei 0, damit der Anfang nicht abgeschnitten wird.

## Download-Format

Bevorzugt wird H.264 mit AAC in mp4. YouTube liefert sonst VP9 oder AV1 in webm, was viele Clients nicht direkt abspielen — dann transcodiert der Server, und bei AV1 mangels Hardware-Decoder komplett auf der CPU.

## Ideen für später

- `goblin playlist <url>` für ganze Playlists, eine Episode pro Video statt pro Kapitel
- Erkennung, ob eine Episode schon existiert, statt blind zu überschreiben
- `--map` mit einer Datei, die Kapitelnummer auf Episodennummer abbildet, für Fälle wo TMDb anders zählt als das Video
- Wenn du es als echtes Kommando willst: Quarkus mit `quarkus-picocli` und Native Image gibt dir ein Binary ohne JVM-Startzeit

## Qualität und Codecs

Standardmäßig wird H.264 mit AAC in mp4 geladen. Das spielt praktisch jeder Client direkt ab — YouTube liefert H.264 aber höchstens bis 1080p, bei älteren Uploads oft nur 720p. Höhere Auflösungen gibt es dort nur als VP9 oder AV1.

Erst nachsehen, was das Video überhaupt hergibt:

```
chapters <url> --formats
```

Steht dort nichts über 720p, ist das Video schlicht nicht besser vorhanden.

Gibt es höhere Auflösungen in anderen Codecs:

```
series <url> "Name" --best
```

`--best` nimmt die beste verfügbare Kombination unabhängig vom Codec und legt das Ergebnis als mkv ab, weil VP9 und Opus dort verlässlicher sitzen als in mp4.

Der Preis: VP9 und AV1 spielen nicht alle Clients direkt ab, dann transcodiert der Server. Ohne Hardware-Decoder für AV1 landet das komplett auf der CPU.

Wer es genauer will, setzt den Selektor selbst:

```
series <url> "Name" -f "bv*[height<=1080]+ba" --container mkv
```

Die Syntax ist die von yt-dlp.

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
