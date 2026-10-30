package com.agenticsdlc.shortener.repository;

import com.agenticsdlc.shortener.domain.UserAgentFamily;

/**
 * Query projection: number of clicks per user-agent family.
 *
 * @param family user-agent family
 * @param clicks clicks from that family
 */
public record UserAgentClickCount(UserAgentFamily family, long clicks) {
}
