package space.perrys.goblin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Brakes for a machine that has to keep doing other things.
 *
 * A whole season is tens of gigabytes moving through one disk in a tight loop:
 * every episode is written twice (the separate streams, then the muxed file),
 * read again for the upload, and written once more at the target. On a single
 * VPS that also carries the panel, its database and every other server, an
 * unthrottled run starves all of them.
 *
 * Worse, a run whose uploads fail deletes nothing and keeps going, so the disk
 * fills up until something else breaks. That is what limit.upload_failures is
 * for, and it is the one that matters most.
 *
 * Read once from goblin.properties rather than taken as command line options:
 * these protect the machine, and a flag you have to remember is a flag you
 * forget on the run that hurts.
 */
final class Limits {

    /** Value for --limit-rate, e.g. "5M". Null means no limit. */
    private static String rate;

    /** Seconds to wait between two items. */
    private static double pause;

    /** Consecutive failed uploads after which a run gives up. 0 disables it. */
    private static int uploadFailures = 3;

    private Limits() {
    }

    /** Without a config file the defaults stay in place, including the upload guard. */
    static void load(Path configFile) {
        if (!Files.isReadable(configFile)) {
            return;
        }

        Properties p = new Properties();
        try (var in = Files.newInputStream(configFile)) {
            p.load(in);
        } catch (IOException e) {
            return;
        }

        rate = value(p, "limit.rate");
        pause = number(value(p, "limit.pause"), 0);
        uploadFailures = (int) number(value(p, "limit.upload_failures"), 3);
    }

    static String rate() {
        return rate;
    }

    static int uploadFailures() {
        return uploadFailures;
    }

    /** Adds --limit-rate when one is configured. yt-dlp and curl spell it the same. */
    static void addRateLimit(java.util.List<String> command) {
        if (rate != null) {
            command.add("--limit-rate");
            command.add(rate);
        }
    }

    /** Pauses between two items so the disk gets a moment to catch up. */
    static void waitBetweenItems() {
        if (pause <= 0) {
            return;
        }
        try {
            Thread.sleep((long) (pause * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String value(Properties p, String key) {
        String v = p.getProperty(key);
        return (v == null || v.isBlank()) ? null : v.strip();
    }

    private static double number(String value, double fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            System.out.println("Not a number, ignoring: " + value);
            return fallback;
        }
    }
}
