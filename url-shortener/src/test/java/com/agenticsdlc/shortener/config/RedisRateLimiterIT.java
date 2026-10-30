package com.agenticsdlc.shortener.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.boot.health.contributor.ReactiveHealthContributor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.agenticsdlc.shortener.api.LinkController;
import com.agenticsdlc.shortener.service.RateLimiter;

/**
 * {@link RedisRateLimiter} against a real Redis ({@code redis:7-alpine} via Testcontainers): the shared
 * {@link RateLimiterContract} runs the Lua script itself (atomicity included, through the concurrency test), and the
 * application wired with {@code shortener.rate-limit.backend=redis} limits link creation through Redis.
 *
 * <p>Run by failsafe in {@code verify}; skipped (not failed) where Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
    "shortener.rate-limit.backend=redis",
    "shortener.rate-limit.capacity=3",
    "shortener.rate-limit.refill-per-minute=1",
    "shortener.rate-limit.redis-key-prefix=it:wired:"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RedisRateLimiterIT extends RateLimiterContract {

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private RateLimiter wiredLimiter;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvcTester mvc;

    @Override
    protected RateLimiter limiter(int capacity, int refillPerMinute, Clock clock) {
        // A key prefix per limiter keeps the buckets of every contract test apart in the shared Redis.
        return new RedisRateLimiter(redis, "it:" + UUID.randomUUID() + ":", capacity, refillPerMinute, clock,
                new SimpleMeterRegistry());
    }

    @Test
    void theRedisBackendIsWiredWithItsHealthIndicator() {
        assertThat(wiredLimiter).isInstanceOf(RedisRateLimiter.class);
        // Lettuce brings Reactor, so Spring Boot registers the reactive Redis health contributor and the blocking
        // one backs off; either way the contributor is named redisHealthContributor.
        Set<String> contributors = new HashSet<>(context.getBeansOfType(HealthContributor.class).keySet());
        contributors.addAll(context.getBeansOfType(ReactiveHealthContributor.class).keySet());
        assertThat(contributors).anyMatch(name -> name.contains("redis"));
        assertThat(mvc.get().uri("/actuator/health")).hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("UP");
    }

    @Test
    void linkCreationIsLimitedThroughRedisAndTheBucketExpires() {
        String client = "198.51.100.77";
        for (int i = 0; i < 3; i++) {
            assertThat(post(LinkController.BASE_PATH, "{\"url\": \"https://example.com/redis\"}", client))
                    .hasStatus(HttpStatus.CREATED);
        }
        assertThat(post(LinkController.BASE_PATH, "{\"url\": \"https://example.com/redis\"}", client))
                .hasStatus(HttpStatus.TOO_MANY_REQUESTS).hasHeader("X-RateLimit-Remaining", "0");

        String key = "it:wired:" + client;
        Map<Object, Object> bucket = redis.opsForHash().entries(key);
        assertThat(bucket).containsKeys("units", "ts");
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isPositive();
    }

    @Test
    void bulkRequestsAreChargedOneTokenPerItemInRedis() {
        String client = "198.51.100.78";
        String twoItems = """
                {"items": [{"url": "https://example.com/redis/1"}, {"url": "https://example.com/redis/2"}]}
                """;

        MvcTestResult accepted = post(LinkController.BASE_PATH + "/bulk", twoItems, client);
        MvcTestResult limited = post(LinkController.BASE_PATH + "/bulk", twoItems, client);

        assertThat(accepted).hasStatusOk().hasHeader("X-RateLimit-Remaining", "1");
        assertThat(accepted).bodyJson().extractingPath("$.created").isEqualTo(2);
        assertThat(limited).hasStatus(HttpStatus.TOO_MANY_REQUESTS).hasHeader("X-RateLimit-Remaining", "1");
    }

    private MvcTestResult post(String path, String body, String client) {
        return mvc.post().uri(path).contentType(MediaType.APPLICATION_JSON).content(body)
                .with(request -> {
                    request.setRemoteAddr(client);
                    return request;
                }).exchange();
    }
}
