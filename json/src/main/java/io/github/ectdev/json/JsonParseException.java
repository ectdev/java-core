package io.github.ectdev.json;

/**
 * Thrown when text is not valid JSON. The message says where: {@code line 3, column 7: expected ',' or ']'}.
 *
 * <p>Lines are counted by {@code \n} (so {@code \r\n} counts once) and columns by UTF-16 code units from 1, so the
 * column is the index into the Java {@code String} plus one, not a count of visible characters.
 */
public final class JsonParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int offset;
    private final int line;
    private final int column;

    JsonParseException(String problem, String source, int offset) {
        super("line " + lineOf(source, offset) + ", column " + columnOf(source, offset) + ": " + problem);
        this.offset = offset;
        this.line = lineOf(source, offset);
        this.column = columnOf(source, offset);
    }

    /** The zero-based index into the input where the problem was detected. */
    public int offset() {
        return offset;
    }

    /** The one-based line number of {@link #offset()}. */
    public int line() {
        return line;
    }

    /** The one-based column of {@link #offset()}, in UTF-16 code units. */
    public int column() {
        return column;
    }

    private static int lineOf(String source, int offset) {
        int line = 1;
        for (int i = 0; i < Math.min(offset, source.length()); i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static int columnOf(String source, int offset) {
        int lineStart = 0;
        for (int i = 0; i < Math.min(offset, source.length()); i++) {
            if (source.charAt(i) == '\n') {
                lineStart = i + 1;
            }
        }
        return offset - lineStart + 1;
    }
}
