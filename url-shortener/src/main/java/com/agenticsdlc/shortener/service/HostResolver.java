package com.agenticsdlc.shortener.service;

import java.io.Serial;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Resolves a host name to its addresses for the optional resolve-time target check of {@link UrlValidator}
 * ({@code shortener.resolve-targets=true}). Injectable so that tests can use a fake resolver instead of DNS.
 *
 * @see InetAddressHostResolver
 */
@FunctionalInterface
public interface HostResolver {

    /**
     * Resolves {@code host}.
     *
     * @param host host name (never an IP literal)
     * @return every address of the host, never empty
     * @throws UnknownHostException when the name does not resolve; {@link ResolutionTimeoutException} when the
     *                              lookup did not finish in time or could not be started
     */
    List<InetAddress> resolve(String host) throws UnknownHostException;

    /**
     * The lookup did not complete within {@code shortener.resolve-timeout}, or the resolver was saturated. Transient,
     * unlike a plain {@link UnknownHostException}.
     */
    final class ResolutionTimeoutException extends UnknownHostException {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * Creates the exception.
         *
         * @param message description
         */
        public ResolutionTimeoutException(String message) {
            super(message);
        }
    }
}
