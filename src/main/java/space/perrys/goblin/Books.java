package space.perrys.goblin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a shelf of books is, independent of where the files live.
 *
 * The counterpart to {@link Comics}: the same three sources in the same order
 * - what the file says about itself, what its name says, what the database
 * says - and no knowledge of Path or of any remote handle.
 *
 * Books differ from comics in one way that shapes everything here. A comic is
 * identified by series and number, which a file name carries reliably. A book
 * is identified by its ISBN, and where a file name carries one it is worth
 * more than everything else in the name put together: it turns a search into a
 * lookup.
 */
final class Books {

    /** How the download sites separate the fields in a file name. */
    private static final String SEPARATOR = " -- ";

    private static final Pattern YEAR_ONLY = Pattern.compile(".*?\\b(1[5-9][0-9]{2}|20[0-9]{2})\\b.*");

    /** The content hash those sites append, and the site's own name. */
    private static final Pattern HASH = Pattern.compile("[0-9a-fA-F]{16,}");
    private static final Set<String> SITES = Set.of(
            "anna's archive", "annas archive", "libgen", "library genesis", "z-library", "zlibrary");

    /**
     * @param how which of the three sources identified it, for the report
     */
    record Id(String title, List<String> authors, String publisher, Integer year,
              String isbn, String how) {

        String author() {
            return authors.isEmpty() ? null : authors.get(0);
        }
    }

    private Books() {
    }

    /**
     * @param metadata what the file said about itself, or null
     */
    static Id identify(String fileName, Epub.Info metadata) {
        Id fromName = fromFileName(fileName);

        if (metadata == null) {
            return fromName;
        }

        // The file's own metadata leads, but a file name from a download site
        // often carries an ISBN that the EPUB itself leaves out - and that is
        // the field worth the most, so it is taken from wherever it exists.
        String isbn = (metadata.isbn() != null) ? metadata.isbn()
                : (fromName == null ? null : fromName.isbn());

        return new Id(metadata.title(), metadata.authors(), metadata.publisher(),
                metadata.year(), isbn, "EPUB metadata");
    }

    /**
     * Reads the "Title -- Authors -- Year -- Publisher -- ISBN -- hash --
     * Anna's Archive" shape the download sites write.
     *
     * The order of the fields after the authors is not fixed, so they are not
     * read by position: each is examined for what it is. A field that is an
     * ISBN is the ISBN wherever it sits.
     *
     * @return null when the name yields no title at all
     */
    static Id fromFileName(String fileName) {
        String stem = Guess.stripExtension(fileName).strip();
        if (stem.isEmpty()) {
            return null;
        }

        String[] fields = stem.split(Pattern.quote(SEPARATOR));
        String title = fields[0].strip();
        if (title.isEmpty()) {
            return null;
        }

        List<String> authors = (fields.length > 1) ? authors(fields[1]) : List.of();
        String publisher = null;
        Integer year = null;
        String isbn = null;

        for (int i = 2; i < fields.length; i++) {
            String field = fields[i].strip();
            if (field.isEmpty() || SITES.contains(field.toLowerCase(Locale.ROOT))
                    || HASH.matcher(field).matches()) {
                continue;
            }

            if (isbn == null) {
                String found = Epub.isbn(field);
                if (found != null) {
                    isbn = found;
                    continue;
                }
            }

            // "Westermann, 2020" carries both, so the year is taken out of the
            // field rather than the field being spent on it.
            Matcher m = YEAR_ONLY.matcher(field);
            if (m.matches()) {
                if (year == null) {
                    year = Integer.valueOf(m.group(1));
                }
                String rest = field.replace(m.group(1), "").replaceAll("[,;\\s]+", " ").strip();
                if (!rest.isEmpty() && publisher == null) {
                    publisher = rest;
                }
                continue;
            }

            if (publisher == null) {
                publisher = field;
            }
        }

        return new Id(title, authors, publisher, year, isbn,
                fields.length > 1 ? "file name" : "file name (title only)");
    }

    /** "A. Smith, B. Jones" and "A. Smith; B. Jones" both occur. */
    static List<String> authors(String field) {
        Set<String> out = new LinkedHashSet<>();
        for (String part : field.split("[;,]|\\band\\b|&")) {
            String name = part.strip();
            if (!name.isEmpty() && name.length() < 80) {
                out.add(name);
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * Fills in what the files leave out, and corrects what they get wrong.
     *
     * A file name from a download site has the punctuation of its title beaten
     * out of it - a colon and a full stop both become an underscore, and there
     * is no way back from that. The database has the title as it is printed,
     * which is the reason to ask at all.
     *
     * @param library may be null, in which case the files stand as they are
     * @return keyed the way {@link #key} keys them
     */
    static Map<String, Id> resolve(Collection<Id> ids, OpenLibrary library) {
        Map<String, Id> out = new LinkedHashMap<>();

        for (Id id : ids) {
            if (id == null) {
                continue;
            }
            String key = key(id);
            if (out.containsKey(key)) {
                continue;
            }

            OpenLibrary.Book book = (library == null) ? null : library.find(id.isbn(),
                    id.title(), id.author());

            if (book == null) {
                out.put(key, id);
                continue;
            }

            System.out.printf("  Open Library: %s%s%s%n",
                    book.title(),
                    book.authors().isEmpty() ? "" : " - " + String.join(", ", book.authors()),
                    book.year() == null ? "" : " (" + book.year() + ")");

            out.put(key, new Id(
                    book.title(),
                    book.authors().isEmpty() ? id.authors() : book.authors(),
                    (book.publisher() != null) ? book.publisher() : id.publisher(),
                    (book.year() != null) ? book.year() : id.year(),
                    (id.isbn() != null) ? id.isbn() : book.isbn(),
                    id.how() + " + Open Library"));
        }
        return out;
    }

    /** Two files are the same book when their ISBN matches, else their title. */
    static String key(Id id) {
        return (id.isbn() != null)
                ? "isbn:" + id.isbn()
                : "title:" + id.title().toLowerCase(Locale.ROOT);
    }

    /** What the level above the book is named after. */
    enum Group {
        AUTHOR, PUBLISHER, NONE;

        static Group of(String value) {
            if (value == null) {
                return AUTHOR;
            }
            return switch (value.strip().toLowerCase(Locale.ROOT)) {
                case "publisher", "verlag" -> PUBLISHER;
                case "none", "flat", "" -> NONE;
                default -> AUTHOR;
            };
        }
    }

    /**
     * The folder a book goes in.
     *
     * Which field the top level carries is a real choice, not a default worth
     * defending: a novel is looked for under its author, a textbook under its
     * publisher and series. Both layouts are one line apart, so neither is
     * baked in.
     *
     * A book missing the chosen field keeps its title folder and loses the
     * level above it, rather than being filed under an invented "Unknown" -
     * one folder of strays is easier to deal with than a folder that lies.
     */
    static String folder(Id id, Group group, boolean flat) {
        String title = Naming.sanitize(id.title());
        if (id.year() != null) {
            title = title + " (" + id.year() + ")";
        }

        String top = switch (group) {
            case AUTHOR -> id.author();
            case PUBLISHER -> id.publisher();
            case NONE -> null;
        };
        String parent = (top == null || top.isBlank()) ? null : Naming.sanitize(top);

        if (flat) {
            return (parent == null) ? "" : parent;
        }
        return (parent == null) ? title : parent + "/" + title;
    }

    /**
     * The file itself.
     *
     * Flat means there is no folder carrying the year, so the name takes it -
     * otherwise two editions of the same book would collide.
     */
    static String fileName(Id id, boolean flat, String extension) {
        String name = Naming.sanitize(id.title());
        if (flat && id.year() != null) {
            name = name + " (" + id.year() + ")";
        }
        return name + "." + extension;
    }

    /** Where a book's cover goes, beside it rather than inside it. */
    static String coverName(boolean flat, Id id) {
        return flat ? Naming.sanitize(id.title()) + ".jpg" : "cover.jpg";
    }
}
