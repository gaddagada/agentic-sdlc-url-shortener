package com.agenticsdlc.shortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

import com.agenticsdlc.shortener.config.ShortenerProperties;
import com.agenticsdlc.shortener.support.TestProperties;

class IpAnonymizerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 1, 15);
    private static final String IP = "203.0.113.9";

    private final IpAnonymizer anonymizer = new IpAnonymizer(TestProperties.defaults(), new MockEnvironment());

    @Test
    void producesHexHmacSha256KeyedWithTheSaltWithoutTheRawIp() throws Exception {
        String hash = anonymizer.hash(IP, DAY);

        assertThat(hash).matches("^[0-9a-f]{64}$").doesNotContain(IP);
        // HMAC-SHA256(key = "test-salt", message = "2026-01-15|203.0.113.9")
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-salt".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal("2026-01-15|203.0.113.9"
                .getBytes(StandardCharsets.UTF_8)));
        assertThat(hash).isEqualTo(expected);
    }

    @Test
    void isNoLongerAPlainSaltedSha256() {
        assertThat(anonymizer.hash(IP, DAY)).isNotEqualTo(Sha256.hex(IP + ":test-salt:2026-01-15"));
    }

    @Test
    void sameDayGivesTheSameHash() {
        assertThat(anonymizer.hash(IP, DAY)).isEqualTo(anonymizer.hash(IP, DAY));
        assertThat(new IpAnonymizer(TestProperties.defaults(), new MockEnvironment()).hash(IP, DAY))
                .isEqualTo(anonymizer.hash(IP, DAY));
    }

    @Test
    void differentDaysGiveDifferentHashes() {
        assertThat(anonymizer.hash(IP, DAY)).isNotEqualTo(anonymizer.hash(IP, DAY.plusDays(1)));
    }

    @Test
    void dependsOnTheSecretKey() {
        ShortenerProperties otherSalt = TestProperties.withAnalytics(
                TestProperties.analytics("other-salt", Duration.ofDays(90)));

        assertThat(new IpAnonymizer(otherSalt, new MockEnvironment()).hash(IP, DAY))
                .isNotEqualTo(anonymizer.hash(IP, DAY));
    }

    @Test
    void distinguishesVisitors() {
        assertThat(anonymizer.hash(IP, DAY)).isNotEqualTo(anonymizer.hash("203.0.113.10", DAY));
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "postgres"})
    void placeholderKeyIsRejectedAtStartupUnderProductionProfiles(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test", profile);

        assertThatIllegalStateException()
                .isThrownBy(() -> new IpAnonymizer(placeholder(), environment))
                .withMessageContaining("shortener.analytics.ip-salt")
                .withMessageContaining(IpAnonymizer.PLACEHOLDER_SALT);
    }

    @Test
    void placeholderKeyIsOnlyAWarningForLocalDevelopment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");

        assertThatCode(() -> new IpAnonymizer(placeholder(), new MockEnvironment())).doesNotThrowAnyException();
        assertThatCode(() -> new IpAnonymizer(placeholder(), environment)).doesNotThrowAnyException();
    }

    @Test
    void secretKeyIsAcceptedUnderProductionProfiles() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod", "postgres");

        assertThat(new IpAnonymizer(TestProperties.defaults(), environment).hash(IP, DAY))
                .isEqualTo(anonymizer.hash(IP, DAY));
    }

    @Test
    void applicationContextRefusesToStartWithThePlaceholderUnderTheProdProfile() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class, IpAnonymizer.class)
                .withPropertyValues("shortener.analytics.ip-salt=" + IpAnonymizer.PLACEHOLDER_SALT);

        runner.withPropertyValues("spring.profiles.active=prod").run(context -> assertThat(context)
                .hasFailed()
                .getFailure().rootCause().isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder"));
        runner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(IpAnonymizer.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ShortenerProperties.class)
    static class PropertiesConfig {
    }

    private static ShortenerProperties placeholder() {
        return TestProperties.withAnalytics(TestProperties.analytics(IpAnonymizer.PLACEHOLDER_SALT,
                Duration.ofDays(90)));
    }
}
