package space.perrys.goblin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs commands one after another instead of all at once.
 *
 * Downloads are long and the services behind them are not infinitely patient.
 * Starting six at a time is how you find a rate limit; a queue turns a list of
 * work into one job at a time, with a pause between them if you want one.
 *
 * The queue is a file, and that is the point. A container that restarts
 * mid-download comes back to the same list, with the job it was on marked
 * pending again rather than silently lost.
 *
 * It also reads the console while it works. On a Pelican server the console is
 * the running process's standard input, so pasting a command there adds it to
 * the queue - which is the whole reason this is a long-running worker and not
 * a script.
 */
final class Queue {

    static final String DEFAULT_FILE = "goblin-queue.tsv";

    private static final String PENDING = "pending";
    private static final String RUNNING = "running";
    private static final String DONE = "done";
    private static final String FAILED = "failed";

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    /** How often the worker looks at the file again while it has nothing to do. */
    private static final long IDLE_POLL_MS = 1000;

    /**
     * @param state one of pending, running, done, failed
     * @param command the command line as typed, quoting included
     */
    private record Job(int id, String state, String added, String command) {

        String line() {
            return state + '\t' + id + '\t' + added + '\t' + command;
        }
    }

    /** Set by the console reader, read by the job loop. */
    private static final AtomicBoolean paused = new AtomicBoolean();
    private static final AtomicBoolean stopping = new AtomicBoolean();

    private Queue() {
    }

    static int run(String[] args) throws Exception {
        Path file = Path.of(DEFAULT_FILE);

        // 'goblin queue' and 'goblin queue --delay 30' both mean the worker,
        // so an option in the first position is not a subcommand.
        boolean named = args.length > 1 && !args[1].startsWith("-");
        String sub = named ? args[1] : "run";

        // --file may sit anywhere among the rest, so it is pulled out first
        // and everything else passed on untouched. No goblin command takes a
        // --file of its own, so nothing is taken from a queued job by this.
        List<String> rest = new ArrayList<>();
        for (int i = named ? 2 : 1; i < args.length; i++) {
            if ("--file".equals(args[i]) && i + 1 < args.length) {
                file = Path.of(args[++i]);
            } else {
                rest.add(args[i]);
            }
        }

        return switch (sub) {
            case "add" -> add(file, rest);
            case "list" -> list(file);
            case "remove" -> remove(file, rest);
            case "clear" -> clear(file, rest);
            case "run" -> worker(file, rest);
            default -> {
                // 'goblin queue shows <url> <name>' - no subcommand, so the
                // whole thing is the job. Guessing here is safe: none of the
                // subcommands is also a goblin command.
                List<String> whole = new ArrayList<>();
                whole.add(sub);
                whole.addAll(rest);
                yield add(file, whole);
            }
        };
    }

    // ------------------------------------------------------------------
    // Managing the list
    // ------------------------------------------------------------------

    private static int add(Path file, List<String> command) throws IOException {
        if (command.isEmpty()) {
            System.err.println("Usage: goblin queue add <command> [arguments...]");
            return 2;
        }
        String line = join(command);
        String rejected = reject(line);
        if (rejected != null) {
            System.err.println(rejected);
            return 2;
        }

        int id = append(file, line);
        System.out.println("Queued as #" + id + ": " + line);
        return 0;
    }

    /**
     * Appends one job and returns its number.
     *
     * An append rather than a rewrite, so that adding from a second shell
     * while the worker runs cannot lose what the worker just wrote.
     */
    private static synchronized int append(Path file, String command) throws IOException {
        List<Job> jobs = read(file);
        int id = 1;
        for (Job job : jobs) {
            id = Math.max(id, job.id() + 1);
        }

        Job job = new Job(id, PENDING, LocalDateTime.now().format(STAMP), command);
        Files.writeString(file, job.line() + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return id;
    }

    private static int list(Path file) throws IOException {
        List<Job> jobs = read(file);
        if (jobs.isEmpty()) {
            System.out.println("The queue is empty (" + file + ").");
            return 0;
        }

        System.out.printf("%-5s %-8s %-17s %s%n", "#", "state", "added", "command");
        for (Job job : jobs) {
            System.out.printf("%-5d %-8s %-17s %s%n",
                    job.id(), job.state(), job.added(), job.command());
        }

        long pending = jobs.stream().filter(j -> PENDING.equals(j.state())).count();
        long failed = jobs.stream().filter(j -> FAILED.equals(j.state())).count();
        System.out.printf("%n%d waiting, %d failed, %d in the list.%n",
                pending, failed, jobs.size());
        return 0;
    }

    private static int remove(Path file, List<String> args) throws IOException {
        if (args.isEmpty()) {
            System.err.println("Usage: goblin queue remove <number>");
            return 2;
        }

        int id;
        try {
            id = Integer.parseInt(args.get(0).replace("#", ""));
        } catch (NumberFormatException e) {
            System.err.println("Not a job number: " + args.get(0));
            return 2;
        }

        List<Job> jobs = read(file);
        List<Job> kept = new ArrayList<>();
        Job dropped = null;
        for (Job job : jobs) {
            if (job.id() == id && !RUNNING.equals(job.state())) {
                dropped = job;
            } else {
                kept.add(job);
            }
        }

        if (dropped == null) {
            System.err.println("No job #" + id + " that could be removed. "
                    + "A running job has to finish or the worker has to be stopped.");
            return 1;
        }

        write(file, kept);
        System.out.println("Removed #" + id + ": " + dropped.command());
        return 0;
    }

    /**
     * Bare: drops what is still waiting. --done: drops what succeeded, and
     * only that - a failed job is the one you want to look at and put back,
     * so it takes --all to lose it.
     */
    private static int clear(Path file, List<String> args) throws IOException {
        boolean succeeded = args.contains("--done");
        boolean all = args.contains("--all");

        List<Job> jobs = read(file);
        List<Job> kept = new ArrayList<>();
        for (Job job : jobs) {
            boolean drop = all
                    || (succeeded && DONE.equals(job.state()))
                    || (!succeeded && PENDING.equals(job.state()));
            // A running job is never dropped from under the worker.
            if (drop && !RUNNING.equals(job.state())) {
                continue;
            }
            kept.add(job);
        }

        write(file, kept);
        System.out.println((jobs.size() - kept.size()) + " removed, " + kept.size() + " left.");
        return 0;
    }

    // ------------------------------------------------------------------
    // The worker
    // ------------------------------------------------------------------

    private static int worker(Path file, List<String> args) throws Exception {
        long delay = 0;
        boolean once = false;

        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--delay" -> delay = Long.parseLong(args.get(++i)) * 1000;
                case "--once" -> once = true;
                default -> {
                    System.err.println("Unknown option: " + args.get(i));
                    return 2;
                }
            }
        }

        // A job left as running belonged to a run that was killed - the
        // container restarted, the console was closed. It never finished, so
        // it goes back in the queue rather than counting as done.
        int recovered = recover(file);
        if (recovered > 0) {
            System.out.println(recovered + " job(s) were interrupted by the last run "
                    + "and are pending again.");
        }

        System.out.println("Goblin queue, working through " + file + ".");
        System.out.println("Type a command to add it. 'list', 'pause', 'resume', 'quit'.");
        if (delay > 0) {
            System.out.println("Pausing " + (delay / 1000) + " s between jobs.");
        }
        System.out.println();

        Thread console = Thread.ofVirtual().start(() -> readConsole(file));

        int failures = 0;
        boolean first = true;

        while (!stopping.get()) {
            Job job = claim(file);

            if (job == null) {
                if (once) {
                    break;
                }
                idle();
                continue;
            }

            if (!first && delay > 0) {
                System.out.println("Waiting " + (delay / 1000) + " s before the next job.");
                if (!sleep(delay)) {
                    break;
                }
            }
            first = false;

            if (!execute(file, job)) {
                failures++;
            }
        }

        console.interrupt();
        System.out.println();
        System.out.println("Queue stopped. " + failures + " job(s) failed.");
        return (failures > 0) ? 1 : 0;
    }

    /** @return true when the job succeeded */
    private static boolean execute(Path file, Job job) throws IOException {
        System.out.println("=========== #" + job.id() + "  " + job.command() + " ===========");
        long started = System.currentTimeMillis();

        boolean ok;
        try {
            int code = Goblin.run(split(job.command()).toArray(String[]::new));
            ok = (code == 0);
            if (!ok) {
                System.out.println("#" + job.id() + " exited with " + code + ".");
            }
        } catch (Exception e) {
            // One job that throws is one job that failed. The rest of the
            // queue has nothing to do with it and carries on.
            ok = false;
            System.out.println("#" + job.id() + " failed: " + e);
        }

        finish(file, job.id(), ok ? DONE : FAILED);
        System.out.printf("#%d %s after %d s.%n%n", job.id(), ok ? "done" : "FAILED",
                (System.currentTimeMillis() - started) / 1000);
        return ok;
    }

    /** Takes the first pending job and marks it running, in one pass over the file. */
    private static synchronized Job claim(Path file) throws IOException {
        if (paused.get()) {
            return null;
        }

        List<Job> jobs = read(file);
        for (int i = 0; i < jobs.size(); i++) {
            Job job = jobs.get(i);
            if (!PENDING.equals(job.state())) {
                continue;
            }
            Job running = new Job(job.id(), RUNNING, job.added(), job.command());
            jobs.set(i, running);
            write(file, jobs);
            return running;
        }
        return null;
    }

    private static synchronized void finish(Path file, int id, String state) throws IOException {
        List<Job> jobs = read(file);
        for (int i = 0; i < jobs.size(); i++) {
            if (jobs.get(i).id() == id) {
                Job job = jobs.get(i);
                jobs.set(i, new Job(job.id(), state, job.added(), job.command()));
            }
        }
        write(file, jobs);
    }

    private static synchronized int recover(Path file) throws IOException {
        List<Job> jobs = read(file);
        int count = 0;
        for (int i = 0; i < jobs.size(); i++) {
            Job job = jobs.get(i);
            if (RUNNING.equals(job.state())) {
                jobs.set(i, new Job(job.id(), PENDING, job.added(), job.command()));
                count++;
            }
        }
        if (count > 0) {
            write(file, jobs);
        }
        return count;
    }

    private static void idle() {
        try {
            Thread.sleep(IDLE_POLL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopping.set(true);
        }
    }

    /** @return false when the wait was cut short and the worker should stop */
    private static boolean sleep(long millis) {
        long until = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < until) {
            if (stopping.get()) {
                return false;
            }
            try {
                Thread.sleep(Math.min(500, until - System.currentTimeMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * Reads the console for as long as the worker runs.
     *
     * On its own thread because the job loop is busy running a download, and
     * the point of the console is that you can queue the next thing while it
     * does. The child processes are started without a standard input of their
     * own, so nothing else is reading this.
     */
    private static void readConsole(Path file) {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String input = line.strip();
                if (input.isEmpty()) {
                    continue;
                }

                switch (input.toLowerCase(Locale.ROOT)) {
                    case "quit", "exit", "stop" -> {
                        System.out.println("Stopping after the current job.");
                        stopping.set(true);
                        return;
                    }
                    case "pause" -> {
                        paused.set(true);
                        System.out.println("Paused. The current job finishes, "
                                + "then nothing new starts. 'resume' continues.");
                    }
                    case "resume" -> {
                        paused.set(false);
                        System.out.println("Resumed.");
                    }
                    case "list" -> list(file);
                    default -> {
                        String rejected = reject(input);
                        if (rejected != null) {
                            System.out.println(rejected);
                        } else {
                            System.out.println("Queued as #" + append(file, input)
                                    + ": " + input);
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("Console closed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // The file
    // ------------------------------------------------------------------

    private static List<Job> read(Path file) throws IOException {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }

        List<Job> jobs = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t", 4);
            if (parts.length < 4) {
                continue;
            }
            try {
                jobs.add(new Job(Integer.parseInt(parts[1]), parts[0], parts[2], parts[3]));
            } catch (NumberFormatException e) {
                // A line nobody can read is a line nobody should act on.
            }
        }
        return jobs;
    }

    /** Written beside the file and moved into place, so a crash cannot halve it. */
    private static void write(Path file, List<Job> jobs) throws IOException {
        StringBuilder out = new StringBuilder();
        for (Job job : jobs) {
            out.append(job.line()).append(System.lineSeparator());
        }

        Path partial = file.resolveSibling(file.getFileName() + ".part");
        Files.writeString(partial, out.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** @return why the command cannot be queued, or null when it can */
    static String reject(String command) {
        if (command.indexOf('\t') >= 0) {
            return "A command cannot contain a tab - that is what separates the columns "
                    + "in the queue file.";
        }
        List<String> parts = split(command);
        if (parts.isEmpty()) {
            return "Nothing to queue.";
        }
        if ("queue".equals(parts.get(0))) {
            return "A queue cannot queue itself.";
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Command lines
    // ------------------------------------------------------------------

    /**
     * Splits a command line the way a shell would.
     *
     * Titles have spaces in them, so quoting has to survive the trip through
     * the file. Handles both quote characters and a backslash escape, which is
     * as much as anything typed into a console needs.
     */
    static List<String> split(String line) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean started = false;
        char quote = 0;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == '\\' && i + 1 < line.length() && quote != '\'') {
                current.append(line.charAt(++i));
                started = true;
            } else if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                // An empty "" is an argument, so remember that one began.
                started = true;
            } else if (Character.isWhitespace(c)) {
                if (started) {
                    parts.add(current.toString());
                    current.setLength(0);
                    started = false;
                }
            } else {
                current.append(c);
                started = true;
            }
        }

        if (started) {
            parts.add(current.toString());
        }
        return parts;
    }

    /** The inverse of {@link #split}: arguments back into one quotable line. */
    static String join(List<String> parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            if (part.isEmpty()) {
                out.append("\"\"");
            } else if (part.chars().anyMatch(c -> Character.isWhitespace(c)
                    || c == '"' || c == '\'' || c == '\\')) {
                out.append('"');
                for (int i = 0; i < part.length(); i++) {
                    char c = part.charAt(i);
                    if (c == '"' || c == '\\') {
                        out.append('\\');
                    }
                    out.append(c);
                }
                out.append('"');
            } else {
                out.append(part);
            }
        }
        return out.toString();
    }
}
