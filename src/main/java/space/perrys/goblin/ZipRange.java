package space.perrys.goblin;

import java.io.IOException;
import java.util.zip.Inflater;

/**
 * Reads a single entry out of a zip without having the whole zip.
 *
 * A .cbz holding twenty scanned pages is thirty megabytes, and the two
 * kilobytes of ComicInfo.xml inside it are the only part worth reading. Over a
 * network that ratio matters: pulling a shelf of comics across just to look at
 * their metadata costs more than the sorting is worth.
 *
 * A zip is built to be read from the end, which is what makes this possible:
 *
 *   1. The end-of-central-directory record sits in the last bytes and says
 *      where the central directory is.
 *   2. The central directory lists every entry with its offset and size.
 *   3. That offset holds the local header and the compressed bytes.
 *
 * Three small reads instead of one large one. {@link Reader} is whatever can
 * fetch a byte range - a local file, or curl against an SFTP server.
 *
 * Everything here is little-endian, which is what the zip format specifies.
 */
final class ZipRange {

    /** Fetches {@code length} bytes starting at {@code from}. */
    interface Reader {
        /**
         * @return the bytes, or fewer than asked for at the end of the file;
         *         null when the range could not be fetched at all
         */
        byte[] read(long from, int length) throws IOException;
    }

    /** How much of the tail to take on the first read. */
    private static final int TAIL = 64 * 1024;

    private static final int EOCD_SIGNATURE = 0x06054b50;
    private static final int CENTRAL_SIGNATURE = 0x02014b50;
    private static final int LOCAL_SIGNATURE = 0x04034b50;

    /** Marks a field that has overflowed into the zip64 extensions. */
    private static final long OVERFLOW_32 = 0xFFFFFFFFL;

    private ZipRange() {
    }

    /**
     * @param name the entry to look for, matched on the last path segment and
     *             ignoring case, the way a .cbz may carry it in a subfolder
     * @return the entry's bytes, or null when the archive has no such entry,
     *         is not a zip, or cannot be read
     */
    static byte[] entry(Reader reader, long fileSize, String name) throws IOException {
        return entry(reader, fileSize, entryName -> matches(entryName, name));
    }

    /**
     * The same, for an entry whose exact name is not known in advance - an
     * EPUB keeps its metadata in a .opf whose name is up to whoever packed it.
     *
     * @param wanted decides on the entry's full path inside the archive
     */
    static byte[] entry(Reader reader, long fileSize, java.util.function.Predicate<String> wanted)
            throws IOException {
        if (fileSize <= 0) {
            return null;
        }

        int tailLength = (int) Math.min(TAIL, fileSize);
        long tailStart = fileSize - tailLength;
        byte[] tail = reader.read(tailStart, tailLength);
        if (tail == null || tail.length < 22) {
            return null;
        }

        int eocd = findEocd(tail);
        if (eocd < 0) {
            // Either not a zip, or the comment at the end is longer than our
            // tail. Both are cases for the caller to fall back on.
            return null;
        }

        long centralSize = u32(tail, eocd + 12);
        long centralOffset = u32(tail, eocd + 16);
        if (centralOffset == OVERFLOW_32 || centralSize == OVERFLOW_32) {
            // zip64. A comic is never four gigabytes; treating this as
            // unreadable is honest and keeps the format handling small.
            return null;
        }

        byte[] central = slice(reader, tail, tailStart, centralOffset, (int) centralSize);
        if (central == null) {
            return null;
        }

        long localOffset = findEntry(central, wanted);
        if (localOffset < 0) {
            return null;
        }

        return readLocal(reader, localOffset, fileSize);
    }

    /**
     * The end-of-central-directory record, searched from the back because a
     * zip comment may follow it - and because its signature can occur inside
     * compressed data, so the last match is the safer one.
     */
    private static int findEocd(byte[] tail) {
        for (int i = tail.length - 22; i >= 0; i--) {
            if (u32(tail, i) == EOCD_SIGNATURE) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Serves a range out of the tail we already hold when it falls inside it,
     * and reads it otherwise. For a comic the central directory is small and
     * almost always inside the tail already, which saves one round trip.
     */
    private static byte[] slice(Reader reader, byte[] tail, long tailStart,
                                long from, int length) throws IOException {
        if (length < 0) {
            return null;
        }
        if (from >= tailStart && from - tailStart + length <= tail.length) {
            byte[] out = new byte[length];
            System.arraycopy(tail, (int) (from - tailStart), out, 0, length);
            return out;
        }
        byte[] read = reader.read(from, length);
        return (read == null || read.length < length) ? null : read;
    }

    /**
     * @return the local header offset of the wanted entry, or -1
     */
    private static long findEntry(byte[] central, java.util.function.Predicate<String> wanted) {
        int at = 0;
        while (at + 46 <= central.length && u32(central, at) == CENTRAL_SIGNATURE) {
            int nameLength = u16(central, at + 28);
            int extraLength = u16(central, at + 30);
            int commentLength = u16(central, at + 32);

            if (at + 46 + nameLength > central.length) {
                return -1;
            }
            String entryName = new String(central, at + 46, nameLength,
                    java.nio.charset.StandardCharsets.UTF_8);

            if (wanted.test(entryName)) {
                long offset = u32(central, at + 42);
                return (offset == OVERFLOW_32) ? -1 : offset;
            }
            at += 46 + nameLength + extraLength + commentLength;
        }
        return -1;
    }

    static boolean matches(String entryName, String wanted) {
        int slash = entryName.lastIndexOf('/');
        return entryName.substring(slash + 1).equalsIgnoreCase(wanted);
    }

    /**
     * Reads the local header and the bytes behind it.
     *
     * The sizes in the local header are not trusted - a streamed zip leaves
     * them zero and puts them in a trailing descriptor instead. The central
     * directory would have them, but reading generously from the local header
     * and letting the inflater stop on its own is simpler and works for both.
     */
    private static byte[] readLocal(Reader reader, long offset, long fileSize) throws IOException {
        byte[] header = reader.read(offset, 30);
        if (header == null || header.length < 30 || u32(header, 0) != LOCAL_SIGNATURE) {
            return null;
        }

        int method = u16(header, 8);
        long compressed = u32(header, 18);
        int nameLength = u16(header, 26);
        int extraLength = u16(header, 28);
        long dataStart = offset + 30 + nameLength + extraLength;

        // A ComicInfo.xml is kilobytes. Reading a generous window covers the
        // streamed case where the header size is zero, and caps what a corrupt
        // header can make us fetch.
        long available = fileSize - dataStart;
        int want = (int) Math.min(available,
                (compressed > 0 && compressed <= TAIL) ? compressed : TAIL);
        if (want <= 0) {
            return null;
        }

        byte[] data = reader.read(dataStart, want);
        if (data == null || data.length == 0) {
            return null;
        }

        if (method == 0) {
            return (compressed > 0 && compressed <= data.length)
                    ? java.util.Arrays.copyOf(data, (int) compressed)
                    : data;
        }
        if (method != 8) {
            // Anything but stored or deflated needs a codec we do not have.
            return null;
        }
        return inflate(data);
    }

    /** Raw deflate, stopping when the stream ends rather than when input runs out. */
    private static byte[] inflate(byte[] data) {
        Inflater inflater = new Inflater(true);
        inflater.setInput(data);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0) {
                    // needsInput means the entry was longer than our window;
                    // what we have is a truncated document, which is useless.
                    break;
                }
                out.write(buffer, 0, n);
            }
        } catch (java.util.zip.DataFormatException e) {
            return null;
        } finally {
            inflater.end();
        }
        return inflater.finished() ? out.toByteArray() : null;
    }

    private static int u16(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int at) {
        return (b[at] & 0xFFL)
                | ((b[at + 1] & 0xFFL) << 8)
                | ((b[at + 2] & 0xFFL) << 16)
                | ((b[at + 3] & 0xFFL) << 24);
    }
}
