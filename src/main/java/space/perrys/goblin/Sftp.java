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
 * Key authentication only. Passwords would be visible in plain text in the
 * file and in the process list.
 */
final class Sftp {

    private final String host;
    private final int port;
    private final String user;
    private final Path key;
    private final String base;

    private Sftp(String host, int port, String user, Path key, String base) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.key = key;
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

        if (host == null || user == null || base == null || keyPath == null) {
            System.out.println(configFile + " is incomplete - "
                    + "sftp.host, sftp.user, sftp.key and sftp.base are required.");
            return null;
        }

        Path key = Path.of(keyPath);
        if (!Files.isReadable(key)) {
            System.out.println("Key not readable: " + key);
            return null;
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

        return new Sftp(host, port, user, key, base);
    }

    String describe() {
        return user + "@" + host + ":" + port + " -> " + base;
    }

    /**
     * Uploads a file. Missing directories are created by curl.
     *
     * @param remoteRelative path below sftp.base, with / as the separator
     */
    void upload(Path localFile, String remoteRelative) throws IOException, InterruptedException {
        String url = "sftp://" + host + ":" + port + base + "/" + encodePath(remoteRelative);

        List<String> cmd = new ArrayList<>(List.of(
                "curl", "--silent", "--show-error", "--fail",
                "--ftp-create-dirs",
                "--key", key.toString(),
                "--user", user + ":",
                "--upload-file", localFile.toString(),
                url));

        Path pub = Path.of(key + ".pub");
        if (Files.isReadable(pub)) {
            cmd.add("--pubkey");
            cmd.add(pub.toString());
        }

        Proc.capture(cmd);
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
