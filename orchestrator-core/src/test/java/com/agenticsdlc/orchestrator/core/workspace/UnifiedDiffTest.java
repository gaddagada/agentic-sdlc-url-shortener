package com.agenticsdlc.orchestrator.core.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.workspace.UnifiedDiff.Hunk;
import com.agenticsdlc.orchestrator.core.workspace.UnifiedDiff.LineKind;
import com.agenticsdlc.orchestrator.core.workspace.UnifiedDiff.PatchException;

class UnifiedDiffTest {

    private static final String PATH = "url-shortener/src/main/java/demo/LinkService.java";

    /** 20 lines. */
    private static final String SOURCE = """
            package demo;

            public class LinkService {
                private final LinkRepository linkRepository;
                private final UrlValidator urlValidator;

                public LinkService(LinkRepository linkRepository, UrlValidator urlValidator) {
                    this.linkRepository = linkRepository;
                    this.urlValidator = urlValidator;
                }

                public Link create(String url) {
                    urlValidator.validate(url);
                    return linkRepository.save(new Link(url));
                }

                public Link get(String code) {
                    return linkRepository.find(code);
                }
            }
            """;

    private static final String EXPECTED = """
            package demo;

            public class LinkService {
                private final LinkRepository linkRepository;
                private final UrlValidator urlValidator;
                private final Clock clock;

                public LinkService(LinkRepository linkRepository, UrlValidator urlValidator) {
                    this.linkRepository = linkRepository;
                    this.urlValidator = urlValidator;
                }

                public Link create(String url) {
                    urlValidator.validate(url);
                    log(url);
                    return linkRepository.save(new Link(url));
                }

                public Link get(String code) {
                    return linkRepository.find(code);
                }
            }
            """;

    private static String diff(int firstOld, int secondOld) {
        return "--- a/" + PATH + "\n"
                + "+++ b/" + PATH + "\n"
                + "@@ -" + firstOld + ",6 +" + firstOld + ",7 @@\n"
                + " \n"
                + " public class LinkService {\n"
                + "     private final LinkRepository linkRepository;\n"
                + "     private final UrlValidator urlValidator;\n"
                + "+    private final Clock clock;\n"
                + " \n"
                + "     public LinkService(LinkRepository linkRepository, UrlValidator urlValidator) {\n"
                + "@@ -" + secondOld + ",5 +" + (secondOld + 1) + ",6 @@ public class LinkService {\n"
                + "     public Link create(String url) {\n"
                + "         urlValidator.validate(url);\n"
                + "+        log(url);\n"
                + "         return linkRepository.save(new Link(url));\n"
                + "     }\n"
                + " \n";
    }

    @Test
    void parsesHeadersAndHunksAndFormatsARoundTrip() {
        UnifiedDiff diff = UnifiedDiff.parse(diff(2, 12));

        assertThat(diff.oldPath()).isEqualTo(PATH);
        assertThat(diff.newPath()).isEqualTo(PATH);
        assertThat(diff.createsFile()).isFalse();
        assertThat(diff.hunks()).hasSize(2);
        Hunk first = diff.hunks().getFirst();
        assertThat(first.oldStart()).isEqualTo(2);
        assertThat(first.oldCount()).isEqualTo(6);
        assertThat(first.newStart()).isEqualTo(2);
        assertThat(first.newCount()).isEqualTo(7);
        assertThat(first.positionKnown()).isTrue();
        assertThat(first.oldLines()).hasSize(6).startsWith("", "public class LinkService {");
        assertThat(first.newLines()).hasSize(7).contains("    private final Clock clock;");
        assertThat(first.lines()).extracting(UnifiedDiff.Line::kind).containsExactly(LineKind.CONTEXT,
                LineKind.CONTEXT, LineKind.CONTEXT, LineKind.CONTEXT, LineKind.ADDED, LineKind.CONTEXT,
                LineKind.CONTEXT);
        Hunk second = diff.hunks().get(1);
        assertThat(second.section()).isEqualTo("public class LinkService {");
        assertThat(second.header()).isEqualTo("@@ -12,5 +13,6 @@ public class LinkService {");
        assertThat(second.oldLines()).hasSize(5);
        assertThat(second.newLines()).hasSize(6);

        String formatted = diff.format();
        assertThat(formatted).startsWith("--- a/" + PATH + "\n+++ b/" + PATH + "\n@@ -2,6 +2,7 @@\n")
                .contains("\n+        log(url);\n").contains("@@ -12,5 +13,6 @@ public class LinkService {\n");
        assertThat(UnifiedDiff.parse(formatted)).isEqualTo(diff);
        assertThat(UnifiedDiff.parse(formatted).format()).isEqualTo(formatted);
        assertThat(diff.describe()).isEqualTo(PATH + " (2 hunks)");
    }

    @Test
    void appliesAnExactDiff() {
        assertThat(UnifiedDiff.parse(diff(2, 12)).apply(SOURCE, PATH)).isEqualTo(EXPECTED);
    }

    @Test
    void appliesWithDifferentWhitespaceAndKeepsTheFileIndentation() {
        String tabbed = "@@ -3,3 +3,4 @@\n"
                + " public  class LinkService {\n"
                + " \tprivate final LinkRepository linkRepository;   \n"
                + "   private final UrlValidator   urlValidator;\n"
                + "+    private final Clock clock;\n";

        String result = UnifiedDiff.parse(tabbed).apply(SOURCE, PATH);

        assertThat(result).contains("public class LinkService {\n    private final LinkRepository linkRepository;\n"
                + "    private final UrlValidator urlValidator;\n    private final Clock clock;\n");
        assertThat(result).doesNotContain("\t").doesNotContain("public  class");
    }

    @Test
    void appliesHunksWhoseLineNumbersAreOffAndFilesWithExtraLinesAbove() {
        // the declared positions are 7 lines too far down
        assertThat(UnifiedDiff.parse(diff(9, 19)).apply(SOURCE, PATH)).isEqualTo(EXPECTED);
        // the declared positions are right for a file without the 12-line license header
        String header = "// line\n".repeat(12);
        assertThat(UnifiedDiff.parse(diff(2, 12)).apply(header + SOURCE, PATH)).isEqualTo(header + EXPECTED);
        // without line numbers the hunks are located by content, in order
        String unnumbered = diff(2, 12).replaceAll("@@ -\\d+,\\d+ \\+\\d+,\\d+ @@", "@@ @@");
        assertThat(UnifiedDiff.parse(unnumbered).hunks()).allSatisfy(h -> assertThat(h.positionKnown()).isFalse());
        assertThat(UnifiedDiff.parse(unnumbered).apply(SOURCE, PATH)).isEqualTo(EXPECTED);
    }

    @Test
    void aFileWithMixedLineEndingsKeepsEveryOriginalLineEnding() {
        String mixed = "a\r\nb\nc\r\nd\ne\r\n";
        UnifiedDiff diff = UnifiedDiff.parse("@@ -2,3 +2,4 @@\n-b\n+B\n c\n+c2\n d\n");

        assertThat(diff.apply(mixed, "mixed.txt")).as("B replaces an LF line, c2 follows a CRLF line")
                .isEqualTo("a\r\nB\nc\r\nc2\r\nd\ne\r\n");
        assertThat(UnifiedDiff.parse("@@ -0,0 +1 @@\n+top\n").apply("x\ny\r\nz\r\n", "top.txt"))
                .as("an added first line takes the dominant ending").isEqualTo("top\r\nx\ny\r\nz\r\n");
        assertThat(UnifiedDiff.parse("@@ -2,1 +2,2 @@\n y\n+z\n").apply("x\r\ny", "tail.txt"))
                .as("no trailing newline stays so; the former last line gets its neighbour's ending")
                .isEqualTo("x\r\ny\r\nz");
        assertThat(UnifiedDiff.parse("@@ -1,1 +1,1 @@\n-x\n+X\n").apply("x\r\n", "one.txt")).isEqualTo("X\r\n");
    }

    @Test
    void aHunkWithoutLineNumbersWhoseContextMatchesTwiceIsRejectedInsteadOfPatchingTheFirstPlace() {
        String source = "class A {\n    void a() {\n        run();\n    }\n\n    void b() {\n        run();\n    }\n}\n";
        UnifiedDiff ambiguous = UnifiedDiff.parse("@@ @@\n         run();\n+        log();\n     }\n");

        assertThatThrownBy(() -> ambiguous.apply(source, "A.java")).isInstanceOfSatisfying(PatchException.class,
                e -> {
                    assertThat(e.hunk()).isEqualTo(1);
                    assertThat(e.getMessage()).contains("match at line 3 but also elsewhere")
                            .contains("make the context unique");
                });
        assertThat(UnifiedDiff.parse("@@ @@\n     void b() {\n         run();\n+        log();\n").apply(source, "A.java"))
                .as("unique context still applies").isEqualTo(source.replace("    void b() {\n        run();\n",
                        "    void b() {\n        run();\n        log();\n"));
        assertThat(UnifiedDiff.parse("@@ -6,3 +6,4 @@\n     void b() {\n         run();\n+        log();\n     }\n")
                .apply(source, "A.java")).as("a numbered hunk picks the declared place")
                .contains("    void b() {\n        run();\n        log();\n");
    }

    @Test
    void aHunkThatDoesNotMatchFailsWithThePreciseLineAndTheNearestMismatch() {
        String wrong = diff(2, 12).replace("         urlValidator.validate(url);\n",
                "         private final LinkLookup lookup;\n");

        assertThatThrownBy(() -> UnifiedDiff.parse(wrong).apply(SOURCE, PATH))
                .isInstanceOfSatisfying(PatchException.class, e -> {
                    assertThat(e.hunk()).isEqualTo(2);
                    assertThat(e.getMessage()).startsWith("hunk 2/2 of " + PATH + " (@@ -12,5 +13,6 @@ public class "
                                    + "LinkService {): context line 'private final LinkLookup lookup;' (hunk line 2) "
                                    + "not found in the file")
                            .contains("nearest match at line 13 differs: 'urlValidator.validate(url);'")
                            .contains("4 of 5 context/removed lines match the block at line 12")
                            .endsWith("rewrite the hunk against the shown source");
                });

        String removedElsewhere = "@@ -17,2 +17,2 @@\n"
                + "     public Link get(String code) {\n"
                + "-        urlValidator.validate(url);\n"
                + "+        return null;\n";
        assertThatThrownBy(() -> UnifiedDiff.parse(removedElsewhere).apply(SOURCE, PATH))
                .isInstanceOfSatisfying(PatchException.class, e -> assertThat(e.getMessage())
                        .contains("hunk 1/1 of " + PATH)
                        .contains("removed line 'urlValidator.validate(url);' (hunk line 2) does not follow the "
                                + "hunk's previous lines; the file has it at line 13")
                        .contains("nearest match at line 18 differs: 'return linkRepository.find(code);'"));

        assertThatThrownBy(() -> UnifiedDiff.parse("@@ -1,3 +1,3 @@\n a\n-b\n+c\n d\n").apply("a\n", "short.txt"))
                .isInstanceOfSatisfying(PatchException.class, e -> assertThat(e.getMessage())
                        .contains("hunk 1/1 of short.txt").contains("expects 3 existing lines but only 1 remain"));
    }

    @Test
    void createsNewFilesHonoursNoNewlineMarkersAndKeepsCrlfLineEnds() {
        UnifiedDiff created = UnifiedDiff.parse("--- /dev/null\n+++ b/docs/new.md\n@@ -0,0 +1,2 @@\n+# Title\n+text\n");
        assertThat(created.createsFile()).isTrue();
        assertThat(created.apply("")).isEqualTo("# Title\ntext\n");

        UnifiedDiff noNewline = UnifiedDiff.parse("@@ -1,2 +1,2 @@\n a\n-b\n+c\n\\ No newline at end of file\n");
        assertThat(noNewline.hunks().getFirst().newNoNewlineAtEnd()).isTrue();
        assertThat(noNewline.apply("a\nb\n")).isEqualTo("a\nc");
        assertThat(noNewline.format()).endsWith("+c\n\\ No newline at end of file\n");

        assertThat(UnifiedDiff.parse("@@ -1,2 +1,2 @@\n a\n-b\n+c\n").apply("a\r\nb\r\n")).isEqualTo("a\r\nc\r\n");
        assertThat(UnifiedDiff.parse("@@ -1,2 +1,2 @@\r\n a\r\n-b\r\n+c\r\n").apply("a\nb")).isEqualTo("a\nc");
    }

    @Test
    void toleratesPreambleMissingContextSpacesFencesAndBlankSeparators() {
        String sloppy = "Here is the change:\n"
                + "diff --git a/x.txt b/x.txt\n"
                + "index 123..456 100644\n"
                + "--- a/x.txt\n"
                + "+++ b/x.txt\n"
                + "@@ -1,3 +1,3 @@\n"
                + "first\n"          // context line without its leading space
                + "-second\n"
                + "+SECOND\n"
                + " third\n"
                + "\n"               // blank separator beyond the declared counts
                + "@@ -5,2 +5,3 @@\n"
                + " fifth\n"
                + "+inserted\n"
                + " sixth\n"
                + "```\n";

        UnifiedDiff diff = UnifiedDiff.parse(sloppy);

        assertThat(diff.oldPath()).isEqualTo("x.txt");
        assertThat(diff.hunks()).hasSize(2);
        assertThat(diff.hunks().getFirst().lines()).hasSize(4);
        assertThat(diff.hunks().get(1).lines()).hasSize(3);
        assertThat(diff.apply("first\nsecond\nthird\nfourth\nfifth\nsixth\n"))
                .isEqualTo("first\nSECOND\nthird\nfourth\nfifth\ninserted\nsixth\n");
    }

    @Test
    void rejectsTextWithoutHunksEmptyHunksAndDiffsOfSeveralFiles() {
        assertThatThrownBy(() -> UnifiedDiff.parse("class A {}\n")).isInstanceOf(PatchException.class)
                .hasMessageContaining("contains no hunk");
        assertThatThrownBy(() -> UnifiedDiff.parse("@@ -1,1 +1,1 @@\n@@ -3,1 +3,1 @@\n-a\n+b\n"))
                .isInstanceOf(PatchException.class).hasMessageContaining("hunk 1").hasMessageContaining("has no lines");
        String twoFiles = "--- a/A.java\n+++ b/A.java\n@@ -1,1 +1,1 @@\n-a\n+b\n"
                + "--- a/B.java\n+++ b/B.java\n@@ -1,1 +1,1 @@\n-c\n+d\n";
        assertThatThrownBy(() -> UnifiedDiff.parse(twoFiles)).isInstanceOf(PatchException.class)
                .hasMessageContaining("more than one file (A.java and B.java)");
        // repeating the same file header before every hunk is tolerated
        String repeated = "--- a/A.java\n+++ b/A.java\n@@ -1,1 +1,1 @@\n-a\n+b\n"
                + "--- a/A.java\n+++ b/A.java\n@@ -3,1 +3,1 @@\n-c\n+d\n";
        assertThat(UnifiedDiff.parse(repeated).apply("a\nx\nc\n")).isEqualTo("b\nx\nd\n");
        assertThat(UnifiedDiff.parse(repeated).hunks()).extracting(Hunk::oldStart).isEqualTo(List.of(1, 3));
    }
}
