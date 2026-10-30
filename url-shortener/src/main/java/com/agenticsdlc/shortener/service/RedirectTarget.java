package com.agenticsdlc.shortener.service;

import java.net.URI;

/**
 * Where to redirect a resolved short link.
 *
 * @param linkId   id of the link (for click recording)
 * @param location the target URL
 */
public record RedirectTarget(long linkId, URI location) {
}
