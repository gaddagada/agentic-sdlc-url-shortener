package com.agenticsdlc.shortener.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.agenticsdlc.shortener.config.ShortenerProperties;
import com.agenticsdlc.shortener.support.TestProperties;

class ShortCodeGeneratorTest {

    private final ShortCodeGenerator generator = new ShortCodeGenerator(TestProperties.defaults());

    @Test
    void generatesCodesOfConfiguredDefaultLength() {
        assertThat(generator.generate()).hasSize(7);
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 10, 32})
    void honoursConfiguredLength(int length) {
        ShortenerProperties properties = new ShortenerProperties("https://sho.rt", length, Duration.ofDays(1), true,
                false, new ShortenerProperties.RateLimit(true, 1, 1), TestProperties.analytics("s", Duration.ofDays(90)));

        assertThat(new ShortCodeGenerator(properties).generate()).hasSize(length);
    }

    @Test
    void usesOnlyBase62Characters() {
        IntStream.range(0, 1_000).mapToObj(i -> generator.generate())
                .forEach(code -> assertThat(code).matches("^[0-9A-Za-z]{7}$"));
    }

    @Test
    void producesDistinctCodes() {
        Set<String> codes = new HashSet<>();
        IntStream.range(0, 5_000).forEach(i -> codes.add(generator.generate()));

        // 5 000 draws from 62^7 values: a duplicate has a probability of about 3.5e-6.
        assertThat(codes).hasSize(5_000);
    }

    @Test
    void usesEverySymbolWithRoughlyUniformFrequency() {
        int[] counts = new int[128];
        int codes = 10_000;
        for (int i = 0; i < codes; i++) {
            for (char c : generator.generate().toCharArray()) {
                counts[c]++;
            }
        }
        double expected = codes * 7.0 / ShortCodeGenerator.ALPHABET.length(); // ~1129 per symbol, sigma ~33
        for (char symbol : ShortCodeGenerator.ALPHABET.toCharArray()) {
            assertThat(counts[symbol]).as("frequency of '%s'", symbol)
                    .isBetween((int) (expected * 0.75), (int) (expected * 1.25));
        }
    }
}
