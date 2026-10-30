package com.agenticsdlc.orchestrator.app;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.agenticsdlc.orchestrator.app.persistence.JdbcRunLeaseProvider;
import com.agenticsdlc.orchestrator.core.port.RunLease;
import com.agenticsdlc.orchestrator.core.port.RunLeaseProvider;

/**
 * A {@link RunLeaseProvider} decorator for tests that records grants and releases per run and, for the JDBC
 * provider, whether the {@code run_leases} row of each grant was in the database right after it was granted (on the
 * engine's thread, before the engine could release it). {@link Registration} wraps the application's provider.
 */
final class RecordingLeases implements RunLeaseProvider {

    private final RunLeaseProvider delegate;
    private final Map<String, List<RunLease>> granted = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> released = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> rowsSeen = new ConcurrentHashMap<>();

    RecordingLeases(RunLeaseProvider delegate) {
        this.delegate = delegate;
    }

    RunLeaseProvider delegate() {
        return delegate;
    }

    List<RunLease> granted(String runId) {
        return List.copyOf(granted.getOrDefault(runId, List.of()));
    }

    Set<String> released(String runId) {
        return Set.copyOf(released.getOrDefault(runId, Set.of()));
    }

    Set<String> rowsSeen(String runId) {
        return Set.copyOf(rowsSeen.getOrDefault(runId, Set.of()));
    }

    @Override
    public Optional<RunLease> tryAcquire(String runId, String ownerId, Duration ttl) {
        Optional<RunLease> lease = delegate.tryAcquire(runId, ownerId, ttl);
        lease.ifPresent(l -> {
            granted.computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>()).add(l);
            if (delegate instanceof JdbcRunLeaseProvider jdbc
                    && jdbc.current(runId).map(RunLease::token).filter(l.token()::equals).isPresent()) {
                rowsSeen.computeIfAbsent(runId, k -> ConcurrentHashMap.newKeySet()).add(l.token());
            }
        });
        return lease;
    }

    @Override
    public boolean renew(RunLease lease, Duration ttl) {
        return delegate.renew(lease, ttl);
    }

    @Override
    public void release(RunLease lease) {
        delegate.release(lease);
        released.computeIfAbsent(lease.runId(), k -> ConcurrentHashMap.newKeySet()).add(lease.token());
    }

    @Override
    public Optional<String> holderOf(String runId) {
        return delegate.holderOf(runId);
    }

    /**
     * Wraps the application's {@link RunLeaseProvider} bean in a {@link RecordingLeases}.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class Registration {

        @Bean
        static BeanPostProcessor recordingLeaseProvider() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof RunLeaseProvider provider && !(bean instanceof RecordingLeases)
                            ? new RecordingLeases(provider) : bean;
                }
            };
        }
    }
}
