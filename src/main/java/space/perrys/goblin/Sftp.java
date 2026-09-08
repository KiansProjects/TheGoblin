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
        return user + "@" + host + ":" + port + " -> " + base
                + (password != null ? " (password)" : "");
    }

    /**
     * Uploads a file. Missing directories are created by curl.
     *
     * @param remoteRelative path below sftp.base, with / as the separator
     */
    void upload(Path localFile, String remoteRelative) throws IOException, InterruptedException {
        String url = "sftp://" + host + ":" + port + base + "/" + encodePath(remoteRelative);

        // --globoff is load-bearing, not cosmetic: curl treats [] and {} as glob
        // ranges, and it applies that to --upload-file as well as to the URL.
        // A folder like "Serie (2012) [tmdbid-34391]" makes curl fail with
        // "bad range in URL" before it ever opens a connection.
        List<String> cmd = new ArrayList<>(List.of(
                "curl", "--globoff", "--silent", "--show-error", "--fail",
                "--ftp-create-dirs",
                "--upload-file", localFile.toString(),
                url));

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

            Proc.capture(cmd);
            return;
        }

        // --config - makes curl read the credentials from stdin, so they never
        // appear in the argument list of the process.
        cmd.addAll(List.of("--config", "-"));
        Proc.captureWithInput(cmd, "user = \"" + escape(user) + ":" + escape(password) + "\"\n");
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

    private static String stripTrailingSlash(String s) {
        String out = s.strip();
        while (out.endsWith("/") && out.length() > 1) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}
