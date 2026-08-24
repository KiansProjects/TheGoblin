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
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        Process p = pb.start();

        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();

        Thread errReader = Thread.ofVirtual().start(() -> drain(p.getErrorStream(), err));
        drain(p.getInputStream(), out);
        errReader.join();

        int code = p.waitFor();
        if (code != 0) {
            throw new IOException(command.get(0) + " endete mit Code " + code + ":\n" + err.toString().strip());
        }
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
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                target.append(line).append('\n');
            }
        } catch (IOException e) {
            // Stream wurde geschlossen, Rest ignorieren
        }
    }
}
