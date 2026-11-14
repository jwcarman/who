/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.who.emaillink;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jwcarman.who.core.crypto.MessageDigests;
import org.jwcarman.who.core.crypto.Secrets;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.service.Resolution;
import org.jwcarman.who.core.service.Resolution.Inactive;
import org.jwcarman.who.core.service.Resolution.NoIdentity;
import org.jwcarman.who.core.service.Resolution.NotLinked;
import org.jwcarman.who.core.service.Resolution.Resolved;
import org.jwcarman.who.core.service.WhoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Does the real work behind {@code POST /ott/generate}, off the request thread: looks up the
 * address, stores the token's hash (the upsert that is also the resend limit), and sends the link.
 *
 * <p>Nothing here is visible to the {@code /ott/generate} caller, who has already been answered
 * {@code 202}; that path ignores the {@link EmailLinkOutcome}. {@link #issue} never throws: every
 * failure is logged at WARN with only the credential id (when known) and the exception class, never
 * the exception message, the address or the token value.
 *
 * <p>Each call to {@link #issue} publishes one {@link SignInEmailRequested} with its outcome.
 */
public class EmailLinkIssuer {

  private static final Logger log = LoggerFactory.getLogger(EmailLinkIssuer.class);

  private final EmailCredentialService credentialService;
  private final WhoService whoService;
  private final EmailLinkTokenRepository repository;
  private final EmailLinkSender sender;
  private final EmailLinkSettings settings;
  private final Clock clock;
  private final WhoEventPublisher eventPublisher;
  private final EmailCodeHasher codeHasher;

  /**
   * Creates a new issuer that publishes no events.
   *
   * @param credentialService finds email credentials
   * @param whoService resolves credentials to active identities
   * @param repository stores token hashes
   * @param sender sends the link
   * @param settings the link settings
   * @param clock the clock
   */
  public EmailLinkIssuer(
      EmailCredentialService credentialService,
      WhoService whoService,
      EmailLinkTokenRepository repository,
      EmailLinkSender sender,
      EmailLinkSettings settings,
      Clock clock) {
    this(
        credentialService, whoService, repository, sender, settings, clock, WhoEventPublisher.NOOP);
  }

  /**
   * Creates a new issuer.
   *
   * @param credentialService finds email credentials
   * @param whoService resolves credentials to active identities
   * @param repository stores token hashes
   * @param sender sends the link
   * @param settings the link settings
   * @param clock the clock
   * @param eventPublisher where {@link SignInEmailRequested} is published
   */
  public EmailLinkIssuer(
      EmailCredentialService credentialService,
      WhoService whoService,
      EmailLinkTokenRepository repository,
      EmailLinkSender sender,
      EmailLinkSettings settings,
      Clock clock,
      WhoEventPublisher eventPublisher) {
    this.credentialService =
        requireNonNull(credentialService, "credentialService must not be null");
    this.whoService = requireNonNull(whoService, "whoService must not be null");
    this.repository = requireNonNull(repository, "repository must not be null");
    this.sender = requireNonNull(sender, "sender must not be null");
    this.settings = requireNonNull(settings, "settings must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.eventPublisher = requireNonNull(eventPublisher, "eventPublisher must not be null");
    this.codeHasher = new EmailCodeHasher(settings.codeSecret());
  }

  /**
   * Issues and sends a link if the address belongs to an active identity and no recent link is
   * outstanding, and reports what happened. Never throws: a failure is logged and reported as
   * {@link EmailLinkOutcome#SEND_FAILED}. Publishes one {@link SignInEmailRequested} with the
   * outcome.
   *
   * <p>This method is synchronous and uses its own transactions. A caller already in a transaction
   * should call it after that transaction commits, for example from {@code
   * TransactionSynchronization.afterCommit} with {@code REQUIRES_NEW}.
   *
   * @param address the requested address, as typed; the link goes to the registered (normalized)
   *     address
   * @param tokenValue the generated token value
   * @return what became of the request
   */
  public EmailLinkOutcome issue(String address, String tokenValue) {
    Issued issued = issueIfKnown(address, tokenValue);
    String reported = looksLikeAnAddress(address) ? EmailCredential.normalize(address) : null;
    try {
      eventPublisher.publish(
          new SignInEmailRequested(
              clock.instant(), reported, issued.outcome(), issued.identityId()));
    } catch (RuntimeException e) {
      log.warn("Failed to publish a sign-in email request: {}", e.getClass().getName());
    }
    return issued.outcome();
  }

  private Issued issueIfKnown(String address, String tokenValue) {
    if (!looksLikeAnAddress(address)) {
      return new Issued(EmailLinkOutcome.UNKNOWN_ADDRESS, null);
    }
    Optional<EmailCredential> credential;
    try {
      credential = credentialService.findByAddress(address);
    } catch (RuntimeException e) {
      log.warn("Failed to look up a sign-in link address: {}", e.getClass().getName());
      return new Issued(EmailLinkOutcome.SEND_FAILED, null);
    }
    if (credential.isEmpty()) {
      log.debug("Sign-in link requested for an unknown address");
      return new Issued(EmailLinkOutcome.UNKNOWN_ADDRESS, null);
    }
    return issueTo(credential.get(), tokenValue);
  }

  private Issued issueTo(EmailCredential credential, String tokenValue) {
    String tokenHash = MessageDigests.sha256Hex(tokenValue);
    String code = Secrets.code(settings.codeLength());
    Instant now = clock.instant();
    Instant expiresAt = now.plus(settings.tokenTtl());
    UUID identityId = null;
    try {
      Resolution resolution =
          Objects.requireNonNullElse(whoService.resolveDetailed(credential), new NotLinked());
      Optional<UUID> resolved = resolution.principal().map(p -> p.identity().id());
      if (resolved.isEmpty()) {
        return new Issued(EmailLinkOutcome.INACTIVE_IDENTITY, unresolvedIdentityId(resolution));
      }
      identityId = resolved.get();
      boolean issued =
          repository.issue(
              credential.id(),
              tokenHash,
              codeHasher.hash(credential.id(), code),
              now,
              expiresAt,
              settings.resendInterval());
      if (!issued) {
        log.debug(
            "Sign-in link for credential {} suppressed by the resend interval", credential.id());
        return new Issued(EmailLinkOutcome.RESEND_SUPPRESSED, identityId);
      }
    } catch (RuntimeException e) {
      log.warn(
          "Failed to issue sign-in link for credential {}: {}",
          credential.id(),
          e.getClass().getName());
      return new Issued(EmailLinkOutcome.SEND_FAILED, identityId);
    }
    return new Issued(send(credential, tokenValue, code, expiresAt, tokenHash), identityId);
  }

  private static UUID unresolvedIdentityId(Resolution resolution) {
    return switch (resolution) {
      case Inactive(UUID identityId) -> identityId;
      case NoIdentity(UUID identityId) -> identityId;
      case Resolved _, NotLinked _ -> null;
    };
  }

  private EmailLinkOutcome send(
      EmailCredential credential,
      String tokenValue,
      String code,
      Instant expiresAt,
      String tokenHash) {
    try {
      sender.send(
          new EmailLinkMessage(
              credential.address(),
              settings.linkBaseUrl() + "#token=" + tokenValue,
              code,
              expiresAt));
      return EmailLinkOutcome.SENT;
    } catch (RuntimeException e) {
      log.warn(
          "Failed to send sign-in link for credential {}: {}",
          credential.id(),
          e.getClass().getName());
      deleteQuietly(credential, tokenHash);
      return EmailLinkOutcome.SEND_FAILED;
    }
  }

  private void deleteQuietly(EmailCredential credential, String tokenHash) {
    try {
      repository.delete(credential.id(), tokenHash);
    } catch (RuntimeException e) {
      log.warn(
          "Failed to remove unsent sign-in link for credential {}: {}",
          credential.id(),
          e.getClass().getName());
    }
  }

  /** What {@link #issue} did, and the identity it found, if any. */
  private record Issued(EmailLinkOutcome outcome, UUID identityId) {}

  private static boolean looksLikeAnAddress(String address) {
    return EmailAddresses.isSafe(address) && address.indexOf('@') >= 0;
  }
}
