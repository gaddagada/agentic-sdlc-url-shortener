package com.agenticsdlc.orchestrator.core.artifact;

import java.util.Collection;
import java.util.List;

/**
 * Minimal, safe GitHub-flavoured Markdown builder used by payload renderings. Table cells are escaped
 * (pipes and line breaks) so that untrusted text cannot break the table structure.
 */
public final class Markdown {

    private final StringBuilder out = new StringBuilder();

    /**
     * Creates an empty builder.
     */
    public Markdown() {
        // empty
    }

    /**
     * Appends a heading.
     *
     * @param level heading level (1..6)
     * @param text heading text
     * @return this builder
     */
    public Markdown heading(int level, String text) {
        out.append("#".repeat(Math.max(1, Math.min(6, level)))).append(' ').append(inline(text)).append("\n\n");
        return this;
    }

    /**
     * Appends a paragraph.
     *
     * @param text paragraph text
     * @return this builder
     */
    public Markdown paragraph(String text) {
        if (!text.isBlank()) {
            out.append(text.strip()).append("\n\n");
        }
        return this;
    }

    /**
     * Appends a bold label followed by a value on its own line.
     *
     * @param label the label
     * @param value the value
     * @return this builder
     */
    public Markdown field(String label, Object value) {
        out.append("**").append(inline(label)).append(":** ").append(inline(String.valueOf(value))).append("\n\n");
        return this;
    }

    /**
     * Appends a bullet list; an empty list renders as a single "none" bullet.
     *
     * @param items items
     * @return this builder
     */
    public Markdown bullets(Collection<String> items) {
        if (items.isEmpty()) {
            out.append("- _none_\n");
        }
        for (String item : items) {
            out.append("- ").append(inline(item)).append('\n');
        }
        out.append('\n');
        return this;
    }

    /**
     * Appends a table.
     *
     * @param headers column headers
     * @param rows rows (each the same width as the headers)
     * @return this builder
     */
    public Markdown table(List<String> headers, List<List<String>> rows) {
        out.append('|');
        for (String header : headers) {
            out.append(' ').append(cell(header)).append(" |");
        }
        out.append("\n|");
        out.append(" --- |".repeat(headers.size()));
        out.append('\n');
        if (rows.isEmpty()) {
            out.append('|').append(" - |".repeat(headers.size())).append('\n');
        }
        for (List<String> row : rows) {
            out.append('|');
            for (int i = 0; i < headers.size(); i++) {
                out.append(' ').append(cell(i < row.size() ? row.get(i) : "")).append(" |");
            }
            out.append('\n');
        }
        out.append('\n');
        return this;
    }

    /**
     * Appends a fenced code block.
     *
     * @param language info string (may be empty)
     * @param code the code
     * @return this builder
     */
    public Markdown code(String language, String code) {
        String fence = code.contains("```") ? "~~~~" : "```";
        out.append(fence).append(language).append('\n').append(code.stripTrailing()).append('\n').append(fence)
                .append("\n\n");
        return this;
    }

    /**
     * Appends raw, already formatted Markdown.
     *
     * @param markdown Markdown text
     * @return this builder
     */
    public Markdown raw(String markdown) {
        out.append(markdown);
        return this;
    }

    /**
     * Returns the rendered Markdown.
     *
     * @return Markdown text
     */
    public String render() {
        return out.toString().stripTrailing() + "\n";
    }

    /**
     * Escapes text for a table cell: pipes are escaped, line breaks collapse to spaces, empty becomes "-".
     *
     * @param text cell text
     * @return safe cell text
     */
    public static String cell(String text) {
        String single = inline(text).replace("|", "\\|");
        return single.isBlank() ? "-" : single;
    }

    /**
     * Collapses line breaks so that text stays on one line.
     *
     * @param text text
     * @return single-line text
     */
    public static String inline(String text) {
        return text.replace("\r", " ").replace("\n", " ").strip();
    }

    /**
     * Truncates text to about {@code maxChars} characters, preferably at a line boundary, and appends a
     * {@code [truncated]} marker when anything was cut.
     *
     * @param text the text
     * @param maxChars maximum characters kept (at least 1)
     * @return the text, or its truncated prefix followed by {@code [truncated]}
     */
    public static String truncate(String text, int maxChars) {
        int max = Math.max(1, maxChars);
        if (text.length() <= max) {
            return text;
        }
        int cut = text.lastIndexOf('\n', max);
        if (cut < max / 2) {
            cut = max;
        }
        return text.substring(0, cut).stripTrailing() + "\n[truncated]\n";
    }

    /**
     * Joins items with ", " or returns "-" when empty.
     *
     * @param items items
     * @return joined text
     */
    public static String join(Collection<String> items) {
        return items.isEmpty() ? "-" : String.join(", ", items);
    }
}
