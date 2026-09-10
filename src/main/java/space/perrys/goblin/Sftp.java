package space.perrys.goblin;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Uploads finished files to another machine over SFTP.
 *
 * Implemented through curl rather than an sftp binary: curl is present in the
 * yolk anyway and can do SFTP, while an openssh-client would have to be
 * installed first.
 *
 * Configured in goblin.properties in the working directory:
 *
 *   sftp.host = 192.168.1.50
 *   sftp.port = 22
 *   sftp.user = perry
 *   sftp.key  = /home/container/.ssh/id_ed25519
 *   sftp.base = /srv/media
 *
 * Key authentication through sftp.key is the recommended way. sftp.password
 * exists as a fallback for targets that do not accept keys, but it is the
 * weaker option: the password sits in plain text in goblin.properties, and on
 * a Panel's built-in SFTP it is the panel account password rather than a
 * credential scoped to one directory. It is never passed on the command line -
 * curl reads it from stdin - but the file itself stays readable.
 */
final class Sftp {

    private final String host;
    private final int port;
    private final String user;
    private final Path key;
    private final String password;
    private final String hostKey;
    private final boolean insecure;
    private final String base;

    /** The doubled-base note is worth saying once per run, not per file. */
    private boolean warnedAboutBase;

    private Sftp(String host, int port, String user, Path key, String password,
                 String hostKey, boolean insecure, String base) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.key = key;
        this.password = password;
        this.hostKey = hostKey;
        this.insecure = insecure;
        this.base = stripTrailingSlash(base);
    }

    /**
     * @return null when there is no configuration or an incomplete one
     */
    static Sftp fromConfig(Path configFile) throws IOException {
        if (!Files.isReadable(configFile)) {
            return null;
        }

        Properties p = new Properties();
        try (var in = Files.newInputStream(configFile)) {
            p.load(in);
        }

        String host = value(p, "sftp.host");
        String user = value(p, "sftp.user");
        String base = value(p, "sftp.base");
        String keyPath = value(p, "sftp.key");
        String password = value(p, "sftp.password");

        if (host == null || user == null || base == null) {
            System.out.println(configFile + " is incomplete - "
                    + "sftp.host, sftp.user and sftp.base are required.");
            return null;
        }
        if (keyPath == null && password == null) {
            System.out.println(configFile + " has neither sftp.key nor sftp.password.");
            return null;
        }

        Path key = null;
        if (keyPath != null) {
            key = Path.of(keyPath);
            if (!Files.isReadable(key)) {
                System.out.println("Key not readable: " + key);
                return null;
            }
        }

        // The key wins when both are present, so a leftover password line
        // cannot quietly downgrade a working key setup.
        if (key != null && password != null) {
            System.out.println("sftp.key and sftp.password are both set - using the key.");
            password = null;
        }

        if (password != null) {
            System.out.println("Warning: authenticating with sftp.password. It sits in plain text "
                    + "in " + configFile + ", and on a Panel's built-in SFTP it is your panel "
                    + "account password. A key in sftp.key avoids both.");
        }

        int port = 22;
        String portValue = value(p, "sftp.port");
        if (portValue != null) {
            try {
                port = Integer.parseInt(portValue);
            } catch (NumberFormatException e) {
                System.out.println("sftp.port is not a number, using 22.");
            }
        }

        // curl checks the server's SSH host key against known_hosts, which a
        // fresh container does not have - the upload then fails with code 60
        // before any transfer starts. Pinning the hash is the way out; the
        // curl CLI has no option to point at a known_hosts file.
        String hostKeyValue = value(p, "sftp.hostkey_sha256");
        String hostKey = (hostKeyValue == null)
                ? null
                // ssh-keygen prints "SHA256:<base64>", curl wants the base64 alone.
                : hostKeyValue.replaceFirst("(?i)^sha256:", "").strip();

        String insecureValue = value(p, "sftp.insecure");
        boolean insecure = insecureValue != null
                && List.of("1", "true", "yes").contains(insecureValue.toLowerCase());

        if (hostKey != null && insecure) {
            System.out.println("sftp.hostkey_sha256 and sftp.insecure are both set - "
                    + "verifying against the hash.");
            insecure = false;
        }

        if (insecure) {
            System.out.println("Warning: sftp.insecure skips the SSH host key check. "
                    + "Anything that can answer on " + host + ":" + port + " will be trusted.");
        }

        return new Sftp(host, port, user, key, password, hostKey, insecure, base);
    }

    String describe() {
        return user + "@" + host + ":" + port + " -> " + (base.isEmpty() ? "/" : base)
                + (password != null ? " (password)" : "");
    }

    /** One line of a remote directory listing. */
    record Entry(String name, long size, boolean directory) {
    }

    /**
     * Uploads a file. Missing directories are created by curl.
     *
     * @param remoteRelative path below sftp.base, with / as the separator
     */
    void upload(Path localFile, String remoteRelative) throws IOException, InterruptedException {
        if (!warnedAboutBase) {
            warnedAboutBase = true;
            if (doublesBase(base, remoteRelative)) {
                System.out.println("  Note: sftp.base is \"" + base + "\" and --out starts with the "
                        + "same folder, so this lands in " + base + "/" + firstSegment(remoteRelative)
                        + "/... - drop it from --out if that is not what you meant.");
            }
        }

        run(List.of("--ftp-create-dirs", "--upload-file", localFile.toString(),
                url(remoteRelative)));
    }

    /**
     * Lists one remote directory, not recursing.
     *
     * curl answers a directory URL with a long listing in the shape of ls -l.
     * The name is taken as everything after the eighth field so that spaces and
     * brackets survive; a server that answers with bare names instead is
     * handled too, at the cost of not knowing sizes or which entries are
     * directories.
     */
    List<Entry> list(String remoteRelative) throws IOException, InterruptedException {
        String listing = run(List.of(url(remoteRelative) + "/"));

        List<Entry> entries = new ArrayList<>();
        for (String line : listing.split("\n")) {
            Entry entry = parseListing(line);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /**
     * @return null for blank lines, totals, and the . and .. entries
     */
    static Entry parseListing(String line) {
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("total ")) {
            return null;
        }

        String name;
        long size = -1;
        boolean directory = false;

        if (trimmed.matches("^[-dlbcps][rwxsStT-]{9}[.+]?\\s.*")) {
            String[] fields = trimmed.split("\\s+", 9);
            if (fields.length < 9) {
                return null;
            }
            name = fields[8];
            directory = trimmed.charAt(0) == 'd';
            try {
                size = Long.parseLong(fields[4]);
            } catch (NumberFormatException e) {
                size = -1;
            }
            // A symlink line reads "name -> target"; the target is not ours.
            int arrow = name.indexOf(" -> ");
            if (trimmed.charAt(0) == 'l' && arrow > 0) {
                name = name.substring(0, arrow);
            }
        } else {
            name = trimmed;
        }

        return (name.equals(".") || name.equals("..") || name.isEmpty())
                ? null
                : new Entry(name, size, directory);
    }

    /**
     * Fetches a byte range, for reading metadata out of an archive without
     * fetching the archive. See {@link ZipRange}.
     *
     * @return null when the range could not be fetched
     */
    byte[] range(String remoteRelative, long from, int length) {
        if (length <= 0) {
            return null;
        }
        try {
            Path temp = Files.createTempFile("goblin-range", null);
            try {
                run(List.of("--range", from + "-" + (from + length - 1),
                        "--output", temp.toString(), url(remoteRelative)));
                byte[] bytes = Files.readAllBytes(temp);
                // A server that ignores the range answers with the whole file.
                // Taking the front of that would be silently wrong, so it is
                // only usable when the range started at nothing.
                if (bytes.length > length) {
                    return (from == 0) ? java.util.Arrays.copyOf(bytes, length) : null;
                }
                return bytes;
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    /** Fetches a whole remote file. */
    void download(String remoteRelative, Path localFile) throws IOException, InterruptedException {
        Files.createDirectories(localFile.toAbsolutePath().getParent());
        run(List.of("--output", localFile.toString(), url(remoteRelative)));
    }

    /**
     * Moves a remote file, creating the target's directory first.
     *
     * SFTP rename does not create directories and does not move across
     * filesystems, which is why this is a rename below one base rather than a
     * general move.
     */
    void rename(String fromRelative, String toRelative) throws IOException, InterruptedException {
        int slash = toRelative.lastIndexOf('/');
        if (slash > 0) {
            mkdirs(toRelative.substring(0, slash));
        }
        quote("rename " + quoted(absolute(fromRelative)) + " " + quoted(absolute(toRelative)));
    }

    /** Creates a directory and every missing parent below sftp.base. */
    void mkdirs(String remoteRelative) throws IOException, InterruptedException {
        StringBuilder path = new StringBuilder();
        for (String segment : remoteRelative.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (path.length() > 0) {
                path.append('/');
            }
            path.append(segment);
            // Prefixed with * so curl carries on when the directory is already
            // there, which for every parent but the last is the normal case.
            quote("*mkdir " + quoted(absolute(path.toString())));
        }
    }

    /** Removes a directory, quietly doing nothing when it is not empty. */
    void rmdir(String remoteRelative) throws IOException, InterruptedException {
        quote("*rmdir " + quoted(absolute(remoteRelative)));
    }

    /**
     * Runs an SFTP command without transferring anything.
     *
     * curl needs a URL even for a pure command, so it is pointed at the base
     * directory and the listing that comes back is thrown away.
     */
    private void quote(String command) throws IOException, InterruptedException {
        run(List.of("--quote", command, "--output", "/dev/null", "sftp://" + host + ":" + port
                + base + "/"));
    }

    /**
     * The one place the credentials are attached, so every operation
     * authenticates the same way.
     */
    private String run(List<String> args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(
                // --globoff is load-bearing, not cosmetic: curl treats [] and {}
                // as glob ranges, and it applies that to --upload-file as well as
                // to the URL. A folder like "Show (2012) [tmdbid-34391]" makes
                // curl fail with "bad range in URL" before it ever connects.
                "curl", "--globoff", "--silent", "--show-error", "--fail"));
        cmd.addAll(args);

        Limits.addRateLimit(cmd);

        if (hostKey != null) {
            cmd.addAll(List.of("--hostpubsha256", hostKey));
        } else if (insecure) {
            cmd.add("--insecure");
        }

        if (key != null) {
            cmd.addAll(List.of("--key", key.toString(), "--user", user + ":"));

            Path pub = Path.of(key + ".pub");
            if (Files.isReadable(pub)) {
                cmd.add("--pubkey");
                cmd.add(pub.toString());
            }
            return Proc.capture(cmd);
        }

        // --config - makes curl read the credentials from stdin, so they never
        // appear in the argument list of the process.
        cmd.addAll(List.of("--config", "-"));
        return Proc.captureWithInput(cmd, "user = \"" + escape(user) + ":" + escape(password) + "\"\n");
    }

    private String url(String remoteRelative) {
        return "sftp://" + host + ":" + port + base + "/" + encodePath(remoteRelative);
    }

    /** The raw remote path for a quote command, which is not URL-encoded. */
    private String absolute(String remoteRelative) {
        return base + "/" + remoteRelative;
    }

    /**
     * Quotes a path for curl's SFTP command parser, which understands double
     * quotes and backslash escapes. Without this every comic with a space in
     * its name would be read as two arguments.
     */
    static String quoted(String path) {
        return '"' + path.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /** Quotes for a curl config line, which understands backslash escapes. */
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Encodes spaces and special characters but keeps the separators. */
    private static String encodePath(String path) {
        List<String> parts = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (!segment.isEmpty()) {
                parts.add(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
            }
        }
        return String.join("/", parts);
    }

    private static String value(Properties p, String key) {
        String v = p.getProperty(key);
        return (v == null || v.isBlank()) ? null : v.strip();
    }

    /**
     * Whether --out repeats the last folder of sftp.base.
     *
     * The remote path is sftp.base plus the local path, so "sftp.base =
     * /media" together with "--out media/shows" writes to /media/media/shows.
     * That is a configuration mistake nobody notices until they look at the
     * target, so it is worth one line of output.
     */
    static boolean doublesBase(String base, String remoteRelative) {
        int slash = base.lastIndexOf('/');
        String last = base.substring(slash + 1);
        return !last.isEmpty() && last.equals(firstSegment(remoteRelative));
    }

    private static String firstSegment(String path) {
        int slash = path.indexOf('/');
        return (slash < 0) ? path : path.substring(0, slash);
    }

    /**
     * Normalises sftp.base so that every path built from it has exactly one
     * slash in each join.
     *
     * A bare "/" - which is what a Panel's own SFTP gives you, since it drops
     * you straight into the server directory - becomes the empty string.
     * Leaving it as "/" would produce sftp://host//media/... and a rename
     * argument of //media/..., which some servers accept and some do not.
     */
    static String stripTrailingSlash(String s) {
        String out = s.strip();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}
