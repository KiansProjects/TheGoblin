package space.perrys.goblin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Starts external programs and collects their output. */
final class Proc {

    private Proc() {
    }

    /** Runs the command and returns stdout. Throws on a non-zero exit code. */
    static String capture(List<String> command) throws IOException, InterruptedException {
        return capture(command, false);
    }

    /**
     * @param echoStderr true passes stderr through to the console live. For
     *                   troubleshooting, when you want to see what yt-dlp is
     *                   failing on.
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
            throw new IOException(command.get(0) + " exited with code " + code + ":\n" + err.toString().strip());
        }
        return out.toString();
    }

    /**
     * Runs the command and feeds {@code input} to its standard input.
     *
     * For credentials that must not show up on the command line: everything in
     * /proc/<pid>/cmdline can be read by any process in the same container,
     * so a password passed as an argument is effectively public there.
     */
    static String captureWithInput(List<String> command, String input)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        Process p = pb.start();

        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();

        Thread errReader = Thread.ofVirtual().start(() -> drain(p.getErrorStream(), err, false));

        try (var stdin = p.getOutputStream()) {
            stdin.write(input.getBytes(StandardCharsets.UTF_8));
        }

        drain(p.getInputStream(), out);
        errReader.join();

        int code = p.waitFor();
        if (code != 0) {
            throw new IOException(command.get(0) + " exited with code " + code + ":\n" + err.toString().strip());
        }
        return out.toString();
    }

    /**
     * Runs the command and returns stdout and stderr together. Does not throw
     * on a non-zero exit code - for analyses whose result is still usable then.
     */
    static String captureCombined(List<String> command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        drain(p.getInputStream(), out);
        p.waitFor();
        return out.toString();
    }

    /**
     * Runs the command and passes its output straight through to the console.
     *
     * Output and errors are inherited, standard input is not. ffmpeg reads its
     * stdin for keystroke commands, so an inherited one lets it eat whatever
     * is typed at the console - which the queue worker is reading. Every
     * ffmpeg call here passes -y, so nothing was ever going to be answered
     * there anyway.
     */
    static void inherit(List<String> command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command)
                .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException(command.get(0) + " exited with code " + code);
        }
    }

    /** Checks whether a program is on the PATH. */
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
            // Stream was closed, ignore the rest
        }
    }
}
