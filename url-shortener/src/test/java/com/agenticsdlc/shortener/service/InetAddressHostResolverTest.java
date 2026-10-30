package com.agenticsdlc.shortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class InetAddressHostResolverTest {

    private static final InetAddress PUBLIC = InetAddress.ofLiteral("93.184.216.34");

    @Test
    void returnsEveryAddressOfTheLookup() throws Exception {
        try (InetAddressHostResolver resolver = new InetAddressHostResolver(
                host -> new InetAddress[] {PUBLIC, InetAddress.ofLiteral("::1")}, Duration.ofSeconds(1), 2, 2)) {

            assertThat(resolver.resolve("example.com")).containsExactly(PUBLIC, InetAddress.ofLiteral("::1"));
        }
    }

    @Test
    void propagatesUnknownHosts() {
        try (InetAddressHostResolver resolver = new InetAddressHostResolver(host -> {
            throw new UnknownHostException(host);
        }, Duration.ofSeconds(1), 2, 2)) {

            assertThatThrownBy(() -> resolver.resolve("nope.example"))
                    .isExactlyInstanceOf(UnknownHostException.class);
        }
    }

    @Test
    void treatsAnEmptyAnswerAsUnknown() {
        try (InetAddressHostResolver resolver = new InetAddressHostResolver(host -> new InetAddress[0],
                Duration.ofSeconds(1), 2, 2)) {

            assertThatThrownBy(() -> resolver.resolve("empty.example"))
                    .isExactlyInstanceOf(UnknownHostException.class);
        }
    }

    @Test
    void givesUpAfterTheTimeoutWhileTheLookupIsStillRunning() {
        CountDownLatch release = new CountDownLatch(1);
        try (InetAddressHostResolver resolver = new InetAddressHostResolver(host -> {
            awaitQuietly(release);
            return new InetAddress[] {PUBLIC};
        }, Duration.ofMillis(100), 2, 2)) {
            long start = System.nanoTime();

            assertThatThrownBy(() -> resolver.resolve("slow.example"))
                    .isInstanceOf(HostResolver.ResolutionTimeoutException.class)
                    .hasMessageContaining("timed out after 100 ms");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        } finally {
            release.countDown();
        }
    }

    @Test
    void refusesLookupsAtOnceWhenAllThreadsAreStuckAndTheQueueIsFull() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        try (InetAddressHostResolver resolver = new InetAddressHostResolver(host -> {
            started.countDown();
            awaitQuietly(release);
            return new InetAddress[] {PUBLIC};
        }, Duration.ofSeconds(30), 1, 1);
             ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            callers.submit(() -> resolver.resolve("stuck-1.example")); // occupies the only thread
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            callers.submit(() -> resolver.resolve("stuck-2.example")); // fills the queue
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (resolver.queuedLookups() < 1 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(resolver.queuedLookups()).isEqualTo(1);

            long start = System.nanoTime();
            assertThatThrownBy(() -> resolver.resolve("third.example"))
                    .isInstanceOf(HostResolver.ResolutionTimeoutException.class)
                    .hasMessageContaining("saturated");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
            release.countDown();
        } finally {
            release.countDown();
        }
    }

    @Test
    void rejectsANonPositiveTimeout() {
        assertThatThrownBy(() -> new InetAddressHostResolver(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
