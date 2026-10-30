package com.agenticsdlc.shortener.service;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;

import org.springframework.stereotype.Component;

import com.agenticsdlc.shortener.config.ShortenerProperties;

/**
 * Generates random Base62 short codes with {@link SecureRandom}.
 *
 * <p>Codes are unpredictable, so links cannot be enumerated by walking a counter; with the default length of 7 the
 * keyspace is 62^7 (about 3.5 trillion), which makes collisions negligible. {@link LinkService} still retries on
 * collision.
 */
@Component
public class ShortCodeGenerator {

    /** Base62 alphabet: digits, upper-case and lower-case ASCII letters. */
    public static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private final int length;
    private final RandomGenerator random;

    /**
     * Creates the generator.
     *
     * @param properties provides {@code shortener.code-length}
     */
    public ShortCodeGenerator(ShortenerProperties properties) {
        this.length = properties.codeLength();
        this.random = new SecureRandom();
    }

    /** {@return a new random code of the configured length} */
    public String generate() {
        char[] code = new char[length];
        for (int i = 0; i < length; i++) {
            // nextInt(bound) is unbiased, so every symbol is equally likely.
            code[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        return new String(code);
    }
}
