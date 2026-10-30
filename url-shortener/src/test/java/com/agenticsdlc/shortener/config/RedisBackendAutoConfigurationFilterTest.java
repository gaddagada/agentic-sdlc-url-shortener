package com.agenticsdlc.shortener.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.mock.env.MockEnvironment;

class RedisBackendAutoConfigurationFilterTest {

    private static final String[] CANDIDATES = {
        "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.health.DataRedisHealthContributorAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        null
    };

    @ParameterizedTest(name = "backend={0}")
    @CsvSource(nullValues = "NULL", textBlock = """
        redis,  true
        REDIS,  true
        ' redis ', true
        memory, false
        NULL,   false
        """)
    void keepsRedisAutoConfigurationOnlyForTheRedisBackend(String backend, boolean redisKept) {
        MockEnvironment environment = new MockEnvironment();
        if (backend != null) {
            environment.setProperty("shortener.rate-limit.backend", backend);
        }
        RedisBackendAutoConfigurationFilter filter = new RedisBackendAutoConfigurationFilter();
        filter.setEnvironment(environment);

        boolean[] matches = filter.match(CANDIDATES, mock(AutoConfigurationMetadata.class));

        assertThat(matches).containsExactly(redisKept, redisKept, true, true);
    }
}
