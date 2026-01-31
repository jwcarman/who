package org.jwcarman.who.core.domain;

/**
 * Composite key identifying an external identity by issuer and subject.
 */
public record ExternalIdentityKey(String issuer, String subject) {
}
