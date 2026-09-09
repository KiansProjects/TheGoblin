package space.perrys.goblin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Reads the metadata a comic archive carries inside itself.
 *
 * A .cbz is a zip, and by convention it holds a ComicInfo.xml written by
 * whatever tagged it - ComicRack, Kavita, Komga, the scanner. That file names
 * the series, the issue and the year, which is exactly what sorting the file
 * needs. No API key, no network, no guessing from a file name that somebody
 * mangled on the way.
 *
 * .cbr is a RAR archive and cannot be opened without a dependency, so those
 * fall back to the file name.
 */
final class Cbz {

    /**
     * @param series    series title, the folder it belongs in
     * @param number    issue number as written, may carry a suffix like "1AU"
     * @param volume    volume number, null when absent
     * @param year      publication year, null when absent
     * @param title     issue title, null when absent
     * @param publisher publisher, null when absent
     */
    record Info(String series, String number, Integer volume, Integer year,
                String title, String publisher) {
    }

    private Cbz() {
    }

    /**
     * @return the archive's ComicInfo.xml, or null when there is none or it
     *         names no series
     */
    static Info read(Path archive) {
        String xml = extract(archive);
        if (xml == null) {
            return null;
        }
        return parse(xml);
    }

    /** Split out from {@link #read} so the parsing can be exercised offline. */
    static Info parse(String xml) {
        Map<String, String> fields;
        try {
            fields = elements(xml);
        } catch (Exception e) {
            // A broken ComicInfo.xml is one unsorted file, not a failed run.
            return null;
        }

        String series = fields.get("series");
        if (series == null || series.isBlank()) {
            return null;
        }

        return new Info(
                series.strip(),
                blankToNull(fields.get("number")),
                integer(fields.get("volume")),
                year(fields.get("year"), integer(fields.get("volume"))),
                blankToNull(fields.get("title")),
                blankToNull(fields.get("publisher")));
    }

    private static String extract(Path archive) {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry entry = null;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry candidate = entries.nextElement();
                String name = candidate.getName();
                int slash = name.lastIndexOf('/');
                if (name.substring(slash + 1).equalsIgnoreCase("ComicInfo.xml")) {
                    entry = candidate;
                    break;
                }
            }
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The top-level elements of the document, keyed in lower case.
     *
     * The archive comes from somewhere else, so the parser is told to refuse
     * doctype declarations - an XML file that pulls in external entities has
     * no business reading the host's files.
     */
    private static Map<String, String> elements(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        var builder = factory.newDocumentBuilder();
        // Without this the parser prints its complaints straight to stderr,
        // past the caller that is already handling them.
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());

        var document = builder.parse(
                new org.xml.sax.InputSource(new java.io.StringReader(xml)));

        Map<String, String> fields = new HashMap<>();
        NodeList children = document.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element) {
                fields.put(element.getTagName().toLowerCase(Locale.ROOT),
                        element.getTextContent());
            }
        }
        return fields;
    }

    /**
     * ComicRack writes the year into Volume when the series has no volume
     * numbering, which is why a four-digit volume is treated as a year.
     */
    private static Integer year(String yearField, Integer volume) {
        Integer year = integer(yearField);
        if (year != null) {
            return year;
        }
        return (volume != null && volume >= 1900 && volume <= 2999) ? volume : null;
    }

    private static Integer integer(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.strip();
    }
}
