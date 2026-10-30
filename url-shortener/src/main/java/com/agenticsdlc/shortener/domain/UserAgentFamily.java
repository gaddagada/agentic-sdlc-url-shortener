package com.agenticsdlc.shortener.domain;

/**
 * Coarse client family derived from the {@code User-Agent} header. Only the family is stored, never the raw header.
 */
public enum UserAgentFamily {
    CHROME,
    EDGE,
    FIREFOX,
    SAFARI,
    OPERA,
    IE,
    BOT,
    HTTP_CLIENT,
    OTHER,
    UNKNOWN
}
