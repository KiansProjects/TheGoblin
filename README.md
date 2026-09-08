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

Mit einem kostenlosen TMDb-Key holt TheGoblin Serien-ID, Erstausstrahlungsjahr, Poster und Hintergrundbild. Der Key steht in `goblin.properties`:

```
tmdb.api_key = dein_key
```

Ersatzweise wird die Umgebungsvariable `TMDB_API_KEY` gelesen; die Datei hat Vorrang. `goblin.properties` steht in der `.gitignore` — im Repo liegt nur `goblin.properties.example`.

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

## Tonspuren

Für Musik, die es nur auf YouTube gibt — eigene Aufnahmen, Fanprojekte, Netlabel-Veröffentlichungen, Podcasts, Vorträge:

```
audio <url> --artist "Name" --album "Album" --out output/musik
```

Ablage nach dem üblichen Muster für Musiksammlungen, das Navidrome, Jellyfin und Plex verstehen:

```
Interpret/
  Album/
    01 - Titel.mp3
```

Ohne `--artist` und `--album` werden Kanal und Playlisttitel benutzt. Tags und Titelbild werden eingebettet.

| Option | Bedeutung |
|---|---|
| `--format` | mp3 (Standard), m4a, opus, flac, wav, oder `best` |
| `--quality <0-10>` | 0 ist die beste Stufe, gilt nur für mp3 |
| `--chapters` | ein langes Video anhand seiner Kapitel in Einzeltitel teilen |
| `--artist`, `--album` | überschreiben, was aus den Metadaten käme |
| `--dry-run` | nur zeigen, wohin geschrieben würde |

**Zu flac und wav:** YouTube liefert Opus oder AAC, also bereits verlustbehaftet. Eine Umwandlung nach flac macht die Dateien größer, nicht besser — die verlorene Information kommt nicht zurück. Wenn du ohne weitere Verluste arbeiten willst, nimm `--format best`: dann bleibt die Originalspur, wie sie ist, ohne Neukodierung.

## Mehrteilige Videos zusammenfügen

Für Material, das ein Kanal in mehreren Teilen hochgeladen hat — lange Let's Plays, Vorträge, Dokus:

```
concat <playlist-url> "Titel" --out output
```

Die Teile werden in Playlist-Reihenfolge geladen, an den Übergängen zugeschnitten und zu einer Datei zusammengefügt.

### Was geschnitten wird

**Abspann am Ende jedes Teils.** Erkannt über eine Stille, die bis zum Videoende durchläuft, oder ein Schwarzbild am Ende. Ein Outro mit durchlaufender Musik und Bild lässt sich so nicht finden — dafür gibt es `--outro <sekunden>` für einen festen Abzug oder `--no-outro`.

**Doppelter Anfang.** Fängt ein Teil mit dem Ende des vorherigen an, wird der doppelte Teil entfernt. Verglichen werden die Lautstärkeverläufe beider Tonspuren über den normierten Korrelationskoeffizienten — grob genug, um Kodierungsunterschiede zu überstehen, fein genug für sekundengenaue Treffer.

An einem Testübergang lag der echte Treffer bei 0,999, der beste Fehltreffer bei 0,80. Die Schwelle steht deshalb auf 0,90.

### Optionen

| Option | Bedeutung |
|---|---|
| `--outro <sek>` | fester Abzug am Ende statt Erkennung |
| `--no-outro` | Abspann nicht schneiden |
| `--overlap <sek>` | Suchfenster für die Überlappung, Standard 90 |
| `--no-overlap` | Anfänge nicht schneiden |
| `--min-overlap <sek>` | kürzeste Überlappung, Standard 1 |
| `--max-overlap <sek>` | längste plausible Überlappung, Standard 30 |
| `--min-score <0-1>` | Schwelle für einen Treffer, Standard 0.90 |
| `--keep-parts` | Einzelteile nach dem Zusammenfügen behalten |
| `--dry-run` | nur die Teile auflisten |

Die Obergrenze für Überlappungen ist kein Schönheitsfehler: bei periodischer Musik wiederholt sich der Lautstärkeverlauf, und dann passt derselbe Anfang an mehreren Stellen. Ohne Grenze gewinnt gelegentlich der falsche Treffer.

Vor dem Zusammenfügen wird jeder Teil neu kodiert, damit die Schnitte framegenau sitzen und alle Abschnitte dieselben Parameter haben. Bei langen Playlists dauert das entsprechend.

### Grenzen

Die Ausgabe im Log zeigt für jeden Teil, was erkannt wurde. Steht dort bei einem Übergang nichts, obwohl du eine Überlappung erwartest, hilft ein größeres `--overlap` oder ein niedrigeres `--min-score` — letzteres aber vorsichtig, unter 0,85 häufen sich Fehltreffer.

## Filme

Ein Video als ganzen Film ablegen, ohne Schneiden:

```
movie <url> "Filmtitel" --out output/filme
```

Ergebnis:

```
Filme/
  Filmtitel (2017) [tmdbid-12345]/
    Filmtitel (2017).mp4
    poster.jpg
    backdrop.jpg
```

TMDb wird dabei über die Filmsuche abgefragt, nicht über die Seriensuche. Mit `--tmdb-id` lässt sich der Treffer festlegen, mit `--year` das Jahr überschreiben.

Optionen wie bei `series`: `--best`, `-f`, `--container`, `--dry-run`, `--no-tmdb`, `--verbose`. `--snap` und `--reencode` gibt es hier nicht, weil nichts geschnitten wird — der Download landet unverändert als Datei.

Lässt der Konsolen-Wrapper nur `series` durch:

```
series <url> "Filmtitel" --movie --out output/filme
```

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

### Ein Video je Folge

Playlists, bei denen jedes Video eine einzelne Episode ist:

```
playlist <url> "Name" --episodes --season 1 --out output/serien
```

Jedes Video wird einmal geladen und als eine Folge abgelegt — kein Schneiden, keine Kapitel. Die Nummerierung folgt der Reihenfolge der Playlist.

Dateien heißen standardmäßig nur `Name S01E01.mp4`. Den Episodentitel holt sich Jellyfin über die Nummer aus TMDb, und YouTube-Titel sind dafür meist unbrauchbar. Mit `--titles` wird der Videotitel trotzdem angehängt.

Zwei Dinge, die bei langen Playlists zählen:

**Vorhandene Dateien werden übersprungen.** Bricht der Lauf bei Folge 30 ab, rufst du denselben Befehl nochmal auf und er macht dort weiter. Mit `--overwrite` wird stattdessen alles neu geladen.

**Ein kaputtes Video stoppt nicht den Rest.** Fehlschläge werden gezählt und am Ende gemeldet, die übrigen Folgen laufen durch.

Enthält eine Playlist mehrere Staffeln, teilst du sie mit `--from` und `--to` auf (1-basiert, beide einschließend):

```
playlist <url> "Name" --episodes --season 1 --from 1 --to 26
playlist <url> "Name" --episodes --season 2 --from 27 --to 46
```

Wo die Grenzen liegen, zeigt die Liste ohne `--episodes` — dort steht der Titel jedes Videos mit seiner Position.

Anonyme Playlists der Form `watch_videos?video_ids=...` werden direkt aus dem Link gelesen, ohne Umweg über YouTubes Playlist-Auflösung.

### Staffel und Folge aus dem Titel

Ist die Playlist durcheinander sortiert oder mischt sie Staffeln, liest `--from-title` die Nummern aus dem Videotitel statt aus der Position:

```
playlist <url> "Name" --episodes --from-title --out output/serien
```

Jede Folge landet dann im richtigen Staffelordner, egal wo sie in der Liste steht. Erkannt werden unter anderem `S01E02`, `s1e2`, `Season 2 Episode 5`, `Staffel 4 Folge 3` und `3x12`. Nennt der Titel nur eine Folgennummer, kommt die Staffel aus `--season`.

Videos ohne erkennbare Nummer werden **übersprungen und am Ende aufgelistet** — nicht geraten. Ein durchnummerierter Rückfall würde sonst eine richtig erkannte Folge überschreiben. Meist sind das ohnehin Trailer oder Compilations, die nicht in die Staffel gehören.

Vorher immer mit `--dry-run` prüfen: die Ausgabe zeigt für jedes Video den geplanten Pfad samt Staffelordner.

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

## Direkt auf einen anderen Server laden

Statt im Serververzeichnis zu sammeln, kann TheGoblin jede fertige Datei per SFTP wegschieben und die lokale Kopie löschen. Nützlich, wenn die Mediathek auf einem anderen Rechner liegt als der Goblin läuft.

`goblin.properties` ins Arbeitsverzeichnis legen (Vorlage: `goblin.properties.example`):

```
sftp.host = 192.168.1.50
sftp.port = 22
sftp.user = perry
sftp.key  = /home/container/.ssh/id_ed25519
sftp.base = /srv/media/serien
```

Dann `--upload` an `series`, `movie` oder `playlist --episodes` anhängen. Der Pfad unter `sftp.base` entspricht dem, was sonst unter `--out` entstanden wäre — Serienordner und Staffelunterordner werden auf dem Ziel angelegt.

Umgesetzt über `curl`, das im Yolk ohnehin vorhanden ist und SFTP kann. Ein `sftp`-Binary wäre erst nachzurüsten.

Nur Schlüsselauthentifizierung. Ein Passwort stünde im Klartext in der Datei und in der Prozessliste. Den öffentlichen Schlüssel neben den privaten legen (`id_ed25519.pub`), dann findet TheGoblin ihn selbst.

**Fehlgeschlagene Uploads löschen nichts.** Ist das Ziel nicht erreichbar, bleibt die Datei lokal liegen und der Lauf geht weiter — du kannst sie später von Hand nachschieben. Mit `--keep-local` bleibt die Kopie grundsätzlich stehen.

Das Disk-Limit des Servers begrenzt damit nur noch, was gerade in Arbeit ist, nicht die Gesamtmenge.

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
