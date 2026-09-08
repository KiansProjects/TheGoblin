# Brief für KI-Agenten in diesem Repo

Vor Änderungen lesen.

## Was das ist

Ein Java-CLI, das YouTube-Material in eine Form bringt, die Jellyfin,
Navidrome und Plex direkt einlesen. Ursprungsfall: ein Kanal lädt eine
ganze Staffel als *ein* Video mit Kapitelmarken hoch — `goblin series`
schneidet daraus einzelne Episodendateien mit korrektem `S01E02`-Schema.
Dazu kommen `movie`, `audio`, `concat`, `playlist` und `chapters`.

## Nicht verhandelbar

* **Keine externen Java-Dependencies.** Das Projekt baut mit `javac` und
  sonst nichts — deshalb der eigene JSON-Parser in `Json.java`. Eine
  Bibliothek hinzuzufügen kostet den `./build.sh`-Weg und macht Maven zur
  Pflicht. Wenn etwas unbedingt eine Dependency braucht, ist das eine
  Entscheidung für den Repo-Besitzer, nicht für einen Agenten.
* **Das Arbeitsverzeichnis liegt nicht in `/tmp`.** In einem
  Wings-Container ist `/tmp` ein tmpfs mit wenigen hundert Megabyte, und
  Video plus Tonspur plus gemuxte Datei sprengen das sofort. Siehe
  `Goblin.workRoot()`, überschreibbar über `GOBLIN_TMP`.
* **Standardformat ist H.264/AAC in mp4**, nicht die beste verfügbare
  Qualität. VP9 und AV1 zwingen den Medienserver zum Transcodieren, bei
  AV1 mangels Hardware-Decoder komplett auf der CPU. `--best` gibt es für
  den bewussten Gegenfall.
* **Nichts wird geraten.** Wo eine Erkennung fehlschlägt — kein
  Zeitstempel, keine Folgennummer im Titel, kein Treffer beim
  Überlappungsvergleich — wird übersprungen und am Ende gemeldet, nicht
  auf einen Rückfallwert geschätzt. Ein falsch nummerierter Treffer
  überschreibt sonst eine korrekt erkannte Folge.
* **`goblin.properties` enthält Zugangsdaten** und steht in der
  `.gitignore`. Im Repo liegt nur `goblin.properties.example`. Keine
  echten Keys, Hosts oder Pfade in Beispiele schreiben.

## Commits

Gitmoji, und zwar so:

```
:sparkles: Add episode-per-video playlist mode
```

* **Shortcode-Schreibweise**, nie das literale Emoji — sonst findet
  `git log --grep=':memo:'` den Commit nicht. (`dc95f25` benutzt ein
  literales 📝; das war ein Versehen und ist kein Vorbild.)
* **Kein Body.** Eine Zeile, sonst nichts.
* **Englisch**, Imperativ, Großbuchstabe am Anfang, kein Punkt am Ende.
* Ein Commit pro thematischer Änderung.

Übliche Emojis hier: `:sparkles:` neues Feature, `:bug:` Fehlerbehebung,
`:memo:` Doku, `:fire:` Code oder Dateien entfernen, `:recycle:`
Umbau ohne Verhaltensänderung.

## Sprache

README und Code-Kommentare auf Deutsch, Commit-Messages auf Englisch. Das
ist gewachsen, nicht durchdacht — aber es ist der Bestand, also bitte
nicht auf eigene Faust vereinheitlichen.

## Verifizieren

Es gibt **keine Tests**. Was geht:

```bash
./build.sh                      # javac + jar, bricht bei Syntaxfehlern ab
java -jar goblin.jar --help
```

Ein echter Durchlauf braucht `yt-dlp`, `ffmpeg`, `ffprobe` und `curl` im
PATH sowie Netzzugang zu YouTube. Ist das nicht vorhanden, ist der Build
die ganze Prüfung — dann auch genau das sagen und nicht implizieren, eine
Änderung sei getestet.

Die Schwellwerte der Erkennungsverfahren sind an je einem Testvideo
kalibriert und im README beschrieben: 0,90 für die Korrelation beim
Überlappungsvergleich (`AudioProbe`), ±5 s Suchfenster für `--snap`
(`CutDetect`). Wer sie ändert, ändert sie ohne Netz.

## Aufbau

| Datei | |
| --- | --- |
| `Goblin.java` | Argument-Parsing und Orchestrierung aller sechs Befehle |
| `YtDlp.java` | Hülle um yt-dlp, Formatselektoren, Cookie-Datei |
| `Ffmpeg.java` / `Ffprobe.java` | Schneiden und Laufzeit ermitteln |
| `ChapterParser.java` | Zeitstempel aus der Videobeschreibung |
| `CutDetect.java` | echten Bildwechsel nahe einem Zeitstempel finden |
| `OutroDetect.java` | Abspann am Ende eines Teils finden |
| `AudioProbe.java` | doppelte Anfänge über Lautstärkeverläufe erkennen |
| `TitleNumbers.java` | Staffel und Folge aus dem Videotitel lesen |
| `Naming.java` | Pfade so bauen, wie Jellyfins Scanner sie erwartet |
| `Tmdb.java` | Serien-ID, Jahr, Poster, Hintergrundbild |
| `Sftp.java` | fertige Dateien per curl wegschieben |
| `Json.java` | minimaler JSON-Parser, ersetzt eine Dependency |

`Goblin.java` ist mit über tausend Zeilen die mit Abstand größte Datei.
Neue Befehle vergrößern sie weiter — das ist bekannt und bisher bewusst
in Kauf genommen.
