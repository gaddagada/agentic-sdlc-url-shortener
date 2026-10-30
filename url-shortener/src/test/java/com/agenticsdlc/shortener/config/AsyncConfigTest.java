package com.agenticsdlc.shortener.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.agenticsdlc.shortener.config.ShortenerProperties.Analytics;
import com.agenticsdlc.shortener.config.ShortenerProperties.Recorder;
import com.agenticsdlc.shortener.support.TestProperties;

class AsyncConfigTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void clickRecorderIsBoundedWithTheDefaultSizing() {
        ThreadPoolTaskExecutor executor = new AsyncConfig().clickRecorderExecutor(TestProperties.defaults(), registry);
        executor.initialize();
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(4);
            assertThat(executor.getMaxPoolSize()).isEqualTo(8);
            assertThat(executor.getQueueCapacity()).isEqualTo(10_000);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("click-recorder-");
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isNotInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
            assertThat(registry.get(AsyncConfig.DROPPED_CLICKS_METRIC).counter().count()).isZero();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void saturatedRecorderDropsAndCountsTheClickWithoutBlockingOrFailingTheCaller() throws Exception {
        ShortenerProperties properties = TestProperties.withAnalytics(
                new Analytics("salt", Duration.ofDays(90), new Recorder(1, 1)));
        ThreadPoolTaskExecutor executor = new AsyncConfig().clickRecorderExecutor(properties, registry);
        executor.initialize();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();
        AtomicReference<Thread> droppedTaskThread = new AtomicReference<>();
        try {
            executor.submit(() -> { // occupies the only thread
                running.countDown();
                release.await(10, TimeUnit.SECONDS);
                return completed.incrementAndGet();
            });
            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
            executor.submit(completed::incrementAndGet); // fills the queue (capacity 1)

            // @Async void methods are submitted like this; a saturated pool must neither throw nor run on the caller.
            assertThatCode(() -> executor.submit(() -> droppedTaskThread.set(Thread.currentThread())))
                    .doesNotThrowAnyException();
            assertThatCode(() -> executor.execute(() -> droppedTaskThread.set(Thread.currentThread())))
                    .doesNotThrowAnyException();

            assertThat(registry.get(AsyncConfig.DROPPED_CLICKS_METRIC).counter().count()).isEqualTo(2);
            release.countDown();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(completed).hasValue(2));
            assertThat(droppedTaskThread).hasNullValue();
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
