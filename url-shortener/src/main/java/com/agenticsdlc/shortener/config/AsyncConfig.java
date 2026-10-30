package com.agenticsdlc.shortener.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;

import com.agenticsdlc.shortener.service.ClickRecorder;

/**
 * Asynchronous execution for click recording.
 *
 * <p>Click persistence runs on a dedicated virtual-thread-per-task executor so a redirect never waits for the
 * analytics write. On shutdown the executor waits briefly for in-flight writes before the datasource closes.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);
    private static final long TERMINATION_TIMEOUT_MILLIS = 5_000;

    @Bean(name = ClickRecorder.EXECUTOR)
    SimpleAsyncTaskExecutor clickRecorderExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("click-recorder-");
        executor.setVirtualThreads(true);
        executor.setTaskTerminationTimeout(TERMINATION_TIMEOUT_MILLIS);
        return executor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (error, method, params) ->
                log.warn("async task failed method={} error={}", method.getName(), error.toString(), error);
    }
}
