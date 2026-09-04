package space.perrys.goblin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Startet externe Programme und sammelt deren Ausgabe ein. */
final class Proc {

    private Proc() {
    }

    /** Fuehrt das Kommando aus und gibt stdout zurueck. Wirft bei Exitcode != 0. */
    static String capture(List<String> command) throws IOException, InterruptedException {
        return capture(command, false);
    }

    /**
     * @param echoStderr true reicht stderr live an die Konsole durch. Fuer die
     *                   Fehlersuche, wenn man sehen will, woran yt-dlp scheitert.
     */
    static String capture(List<String> command, boolean echoStderr)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        Process p = pb.start();

        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();

        Thread errReader = Thread.ofVirtual().start(
                () -> drain(p.getErrorStream(), err, echoStderr));
        drain(p.getInputStream(), out);
        errReader.join();

        int code = p.waitFor();
        if (code != 0) {
            throw new IOException(command.get(0) + " endete mit Code " + code + ":\n" + err.toString().strip());
        }
        return out.toString();
    }

    /**
     * Fuehrt das Kommando aus und gibt stdout und stderr zusammen zurueck.
     * Wirft nicht bei Exitcode != 0 - fuer Analysen, deren Ergebnis auch dann
     * brauchbar ist.
     */
    static String captureCombined(List<String> command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        drain(p.getInputStream(), out);
        p.waitFor();
        return out.toString();
    }

    /** Fuehrt das Kommando aus und reicht die Ausgabe direkt an die Konsole durch. */
    static void inherit(List<String> command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).inheritIO().start();
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException(command.get(0) + " endete mit Code " + code);
        }
    }

    /** Prueft, ob ein Programm im PATH liegt. */
    static boolean exists(String program) {
        try {
            new ProcessBuilder(program, "--version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor();
            return true;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static void drain(java.io.InputStream in, StringBuilder target) {
        drain(in, target, false);
    }

    private static void drain(java.io.InputStream in, StringBuilder target, boolean echo) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                target.append(line).append('\n');
                if (echo) {
                    System.err.println(line);
                }
            }
        } catch (IOException e) {
            // Stream wurde geschlossen, Rest ignorieren
        }
    }
}
