package com.agenticsdlc.orchestrator.core.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.artifact.RiskLevel;

import tools.jackson.core.type.TypeReference;

class StrictCanonicalJsonTest {

    private final JsonCodec codec = JsonCodec.standard();

    @Test
    void keysAreSortedAtEveryLevelWithoutWhitespace() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("zeta", List.of(Map.of("b", 1, "a", 2)));
        inner.put("alpha", null);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("b", inner);
        value.put("B", "upper sorts first");
        value.put("a", true);

        assertThat(codec.strictCanonicalJson(value))
                .isEqualTo("{\"B\":\"upper sorts first\",\"a\":true,\"b\":{\"alpha\":null,\"zeta\":[{\"a\":2,\"b\":1}]}}");
        Map<String, Object> reordered = new TreeMap<>(java.util.Comparator.reverseOrder());
        reordered.putAll(value);
        assertThat(codec.strictCanonicalJson(reordered)).isEqualTo(codec.strictCanonicalJson(value));
    }

    @Test
    void numbersAreNormalizedWhateverTheirJavaType() {
        assertThat(List.of(2, 2L, (short) 2, (byte) 2, 2.0, 2.0f, new BigDecimal("2.00"), new BigDecimal("2E0"),
                BigInteger.TWO)).allSatisfy(n -> assertThat(codec.strictCanonicalJson(n)).isEqualTo("2"));
        assertThat(codec.strictCanonicalJson(0.5)).isEqualTo("0.5");
        assertThat(codec.strictCanonicalJson(0.1f)).as("shortest float representation").isEqualTo("0.1");
        assertThat(codec.strictCanonicalJson(new BigDecimal("1.50"))).isEqualTo("1.5");
        assertThat(codec.strictCanonicalJson(1.0e20)).isEqualTo("100000000000000000000");
        assertThat(codec.strictCanonicalJson(1.5e-7)).isEqualTo("0.00000015");
        assertThat(codec.strictCanonicalJson(-0.0)).isEqualTo("0");
        assertThat(codec.strictCanonicalJson(Long.MAX_VALUE)).isEqualTo("9223372036854775807");
        assertThat(codec.strictCanonicalJson(Double.NaN)).isEqualTo("\"NaN\"");
        assertThat(codec.strictCanonicalJson(Double.NEGATIVE_INFINITY)).isEqualTo("\"-Infinity\"");
    }

    @Test
    void stringsEscapeOnlyWhatJsonRequires() {
        assertThat(codec.strictCanonicalJson("quote \" backslash \\ tab \t newline \n bell \u0007 é €"))
                .isEqualTo("\"quote \\\" backslash \\\\ tab \\t newline \\n bell \\u0007 é €\"");
        assertThat(codec.strictCanonicalJson("\u001f")).isEqualTo("\"\\u001f\"");
    }

    @Test
    void otherValuesAreWrittenAsTheyReadBackAfterAJsonRoundTrip() {
        assertThat(codec.strictCanonicalJson(Instant.parse("2026-10-30T10:00:00.120Z")))
                .isEqualTo("\"2026-10-30T10:00:00.120Z\"");
        assertThat(codec.strictCanonicalJson(RiskLevel.HIGH)).isEqualTo("\"HIGH\"");
        assertThat(codec.strictCanonicalJson(new Pair("x", 1))).isEqualTo("{\"left\":\"x\",\"right\":1}");
        assertThat(codec.strictCanonicalJson(new int[] {3, 1})).isEqualTo("[3,1]");
        assertThat(codec.strictCanonicalJson(new Object[] {"a", null})).isEqualTo("[\"a\",null]");
        assertThat(codec.strictCanonicalJson(codec.readTree("{\"b\":[1,2.50],\"a\":\"x\"}")))
                .isEqualTo("{\"a\":\"x\",\"b\":[1,2.5]}");
        assertThat(codec.strictCanonicalJson(null)).isEqualTo("null");
    }

    @Test
    void aJsonRoundTripDoesNotChangeTheCanonicalForm() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", 3L);
        data.put("ratio", 0.25f);
        data.put("when", Instant.parse("2026-10-30T10:00:00Z"));
        data.put("list", new ArrayList<>(Arrays.asList(1, "two", null, Map.of("y", 1.0, "x", -2))));
        data.put("risk", RiskLevel.MEDIUM);
        String before = codec.strictCanonicalJson(data);
        Map<String, Object> reread = codec.mapper().readValue(codec.toJson(data),
                new TypeReference<HashMap<String, Object>>() {
                });
        assertThat(codec.strictCanonicalJson(reread)).isEqualTo(before);
    }

    @Test
    void aValueTheMapperWritesAsNullIsNullAndUnicodeIsNotNormalized() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("absent", java.util.Optional.empty());
        value.put("present", java.util.Optional.of("x"));
        Object roundTripped = codec.fromJson(codec.toJson(value), Object.class);

        assertThat(codec.strictCanonicalJson(value)).isEqualTo(codec.strictCanonicalJson(roundTripped))
                .contains("null").doesNotContain("Optional");

        String composed = "caf" + (char) 0xE9;
        String decomposed = "cafe" + (char) 0x301;
        assertThat(java.text.Normalizer.normalize(decomposed, java.text.Normalizer.Form.NFC)).isEqualTo(composed);
        assertThat(codec.strictCanonicalJson(composed)).as("documented: no Unicode normalization")
                .isNotEqualTo(codec.strictCanonicalJson(decomposed));
    }

    @Test
    void invalidObjectsAreRejected() {
        Map<Object, Object> nullKey = new HashMap<>();
        nullKey.put(null, 1);
        assertThatThrownBy(() -> codec.strictCanonicalJson(nullKey)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null key");
        Map<Object, Object> duplicate = new LinkedHashMap<>();
        duplicate.put(1, "int");
        duplicate.put("1", "string");
        assertThatThrownBy(() -> codec.strictCanonicalJson(duplicate)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate key");
        List<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);
        assertThatThrownBy(() -> codec.strictCanonicalJson(cyclic)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nested deeper");
    }

    record Pair(String left, int right) {
    }
}
