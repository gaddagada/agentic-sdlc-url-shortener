package com.agenticsdlc.shortener.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.agenticsdlc.shortener.service.HostResolver;
import com.agenticsdlc.shortener.service.InetAddressHostResolver;

/**
 * Provides the {@link HostResolver} used by the optional resolve-time target check
 * ({@code shortener.resolve-targets=true}). Its lookup threads are only started on first use, so the bean costs
 * nothing while the check is off; Spring closes it on shutdown. Tests replace it with a {@code @Primary} fake.
 */
@Configuration(proxyBeanMethods = false)
class HostResolverConfig {

    @Bean
    InetAddressHostResolver hostResolver(ShortenerProperties properties) {
        return new InetAddressHostResolver(properties.resolveTimeout());
    }
}
