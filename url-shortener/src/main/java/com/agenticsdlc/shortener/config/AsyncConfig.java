package com.agenticsdlc.shortener.config;

import java.util.concurrent.RejectedExecutionHandler;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.agenticsdlc.shortener.service.ClickRecorder;

/**
 * Asynchronous execution for click recording.
 *
 * <p>Click persistence runs on a dedicated, bounded {@link ThreadPoolTaskExecutor} so a redirect never waits for the
 * analytics write. Each recorder task holds a database connection, so the pool size
 * ({@code shortener.analytics.recorder.threads}, default 8) caps how many connections click recording can take from
 * the pool and redirects are never starved. Bursts are buffered in a bounded queue
 * ({@code shortener.analytics.recorder.queue-capacity}, default 10 000); when the queue is full the click is dropped
 * and counted in {@value #DROPPED_CLICKS_METRIC} instead of blocking the redirect (which {@code CallerRunsPolicy}
 * would do). On shutdown the executor waits briefly for queued writes before the datasource closes.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
class AsyncConfig implements AsyncConfigurer {

    static final String DROPPED_CLICKS_METRIC = "shortener.clicks.dropped";
    static final String THREAD_NAME_PREFIX = "click-recorder-";

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);
    private static final int TERMINATION_TIMEOUT_MILLIS = 5_000;

    @Bean(name = ClickRecorder.EXECUTOR)
    ThreadPoolTaskExecutor clickRecorderExecutor(ShortenerProperties properties, MeterRegistry registry) {
        ShortenerProperties.Recorder recorder = properties.analytics().recorder();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(THREAD_NAME_PREFIX);
        executor.setCorePoolSize(recorder.coreThreads());
        executor.setMaxPoolSize(recorder.threads());
        executor.setQueueCapacity(recorder.queueCapacity());
        executor.setRejectedExecutionHandler(dropAndCount(Counter.builder(DROPPED_CLICKS_METRIC)
                .description("Clicks not recorded because the click-recorder queue was full or shutting down")
                .register(registry)));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationMillis(TERMINATION_TIMEOUT_MILLIS);
        return executor;
    }

    /**
     * Rejection policy of the click recorder: analytics are best effort, so a click that cannot be queued is dropped
     * and counted. It never throws and never runs the task on the caller (redirect) thread.
     *
     * @param dropped counter incremented for every dropped click
     * @return the rejection handler
     */
    static RejectedExecutionHandler dropAndCount(Counter dropped) {
        return (task, pool) -> {
            dropped.increment();
            log.debug("click dropped: recorder saturated activeThreads={} queued={}", pool.getActiveCount(),
                    pool.getQueue().size());
        };
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (error, method, params) ->
                log.warn("async task failed method={} error={}", method.getName(), error.toString(), error);
    }
}
