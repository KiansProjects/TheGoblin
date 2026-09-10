package space.perrys.goblin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * What an EPUB says about itself.
 *
 * An EPUB is a zip holding an .opf - an XML file carrying the Dublin Core
 * fields that whoever produced the book filled in. That makes it the same kind
 * of source as a comic's ComicInfo.xml: written at the source, and it survives
 * every rename on the way here.
 *
 * The .opf is found by extension rather than by name. The specification says
 * to read META-INF/container.xml and follow the path in it, but that is two
 * lookups to find the only .opf in the archive, and the name of that file is
 * up to whoever packed it - content.opf, package.opf, volume.opf all occur.
 *
 * A PDF has no equivalent. Those are identified from their file name.
 */
final class Epub {

    /**
     * @param authors in the order the file lists them, duplicates removed
     */
    record Info(String title, List<String> authors, String publisher, Integer year, String isbn) {
    }

    /** Enough for any .opf; they are metadata, not content. */
    private static final int MAX_OPF = 512 * 1024;

    private static final Pattern YEAR = Pattern.compile("(1[0-9]{3}|20[0-9]{2})");

    /** 13 digits, or 10 with a trailing X, hyphens and spaces allowed. */
    private static final Pattern ISBN =
            Pattern.compile("(?<![0-9])((?:97[89][- ]?)?(?:[0-9][- ]?){9}[0-9Xx])(?![0-9])");

    private Epub() {
    }

    /** Reads a local file. */
    static Info read(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (isOpf(entry.getName()) && entry.getSize() <= MAX_OPF) {
                    try (var in = zip.getInputStream(entry)) {
                        return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (Exception e) {
            // Not a zip, no .opf, broken XML - all one thing to the caller.
        }
        return null;
    }

    /** Reads through byte ranges, for a file on another machine. */
    static Info read(ZipRange.Reader reader, long size) {
        try {
            byte[] opf = ZipRange.entry(reader, size, Epub::isOpf);
            return (opf == null) ? null : parse(new String(opf, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    static boolean isOpf(String entryName) {
        return entryName.toLowerCase(java.util.Locale.ROOT).endsWith(".opf");
    }

    /**
     * @return null when there is no title, because a book without one cannot
     *         be filed anywhere useful
     */
    static Info parse(String opf) {
        String title = first(opf, "title");
        if (title == null) {
            return null;
        }

        // dc:creator is the author; a contributor is the translator or the
        // illustrator and does not belong in the folder name.
        Set<String> authors = new LinkedHashSet<>(all(opf, "creator"));

        Integer year = null;
        for (String date : all(opf, "date")) {
            Matcher m = YEAR.matcher(date);
            if (m.find()) {
                year = Integer.valueOf(m.group(1));
                break;
            }
        }

        String isbn = null;
        for (String identifier : all(opf, "identifier")) {
            String found = isbn(identifier);
            if (found != null) {
                isbn = found;
                break;
            }
        }

        return new Info(title, new ArrayList<>(authors), first(opf, "publisher"), year, isbn);
    }

    /**
     * @return the ISBN in the text with its separators removed, or null
     */
    static String isbn(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = ISBN.matcher(text);
        if (!m.find()) {
            return null;
        }
        String digits = m.group(1).replaceAll("[- ]", "");
        return (digits.length() == 10 || digits.length() == 13) ? digits.toUpperCase() : null;
    }

    private static String first(String xml, String field) {
        List<String> found = all(xml, field);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Every value of one Dublin Core field.
     *
     * The prefix is optional and not always "dc" - some producers bind the
     * namespace to something else, and a few leave it off entirely.
     */
    private static List<String> all(String xml, String field) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile(
                "<(?:[A-Za-z0-9_.-]+:)?" + field + "\\b[^>]*>(.*?)</(?:[A-Za-z0-9_.-]+:)?" + field + ">",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(xml);
        while (m.find()) {
            String value = unescape(m.group(1)).strip();
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return out;
    }

    static String unescape(String text) {
        String out = text.replaceAll("(?s)<!\\[CDATA\\[(.*?)]]>", "$1");
        Matcher m = Pattern.compile("&#x?([0-9A-Fa-f]+);").matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int radix = m.group().startsWith("&#x") ? 16 : 10;
            try {
                m.appendReplacement(sb, Matcher.quoteReplacement(
                        String.valueOf((char) Integer.parseInt(m.group(1), radix))));
            } catch (NumberFormatException e) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(sb);

        return sb.toString()
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                // Last, so that "&amp;lt;" does not become "<".
                .replace("&amp;", "&");
    }
}
