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

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.jwcarman.who.core.crypto.MessageDigests;
import org.jwcarman.who.core.crypto.Secrets;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.email.WhoEmails;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.id.Identifiers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds an email address to a signed-in identity, or makes one theirs in place of another, only once
 * the person confirms a link or code sent to it. {@link EmailCredentialService#register} attaches
 * an address at once and suits an application vouching for it, such as an invite; this is for a
 * person asking for themselves.
 *
 * <p>{@link #request} never throws and does not say whether an address is taken, so run it off the
 * request thread and answer the caller the same way whatever it returns. Do not call the confirm
 * methods inside a transaction: a wrong code's attempt must count even though the call fails.
 */
public class EmailAddressChanges {

  /** The fragment that carries the token on the confirmation link. */
  public static final String TOKEN_FRAGMENT = "#confirm-address=";

  private static final Logger log = LoggerFactory.getLogger(EmailAddressChanges.class);
  private static final UUID DUMMY_ID = new UUID(0, 0);
  private static final int MAX_TOKEN_LENGTH = 512;

  private final EmailCredentialService credentials;
  private final EmailAddressRequestRepository requests;
  private final WhoEmails emails;
  private final EmailLinkSettings settings;
  private final EmailCodeHasher codeHasher;
  private final String confirmationUrl;
  private final Clock clock;
  private final WhoEventPublisher eventPublisher;

  /**
   * Creates the service.
   *
   * @param credentials registers and replaces the addresses
   * @param requests keeps the requests waiting to be confirmed
   * @param emails sends the confirmation and the notice to a replaced address
   * @param settings the link lifetime, resend interval, code length, attempts and code secret
   * @param confirmationUrl the page that receives the confirmation link; the token follows {@link
   *     #TOKEN_FRAGMENT}
   * @param clock the current time
   * @param eventPublisher where {@link EmailAddressConfirmationRequested} is published
   */
  public EmailAddressChanges(
      EmailCredentialService credentials,
      EmailAddressRequestRepository requests,
      WhoEmails emails,
      EmailLinkSettings settings,
      String confirmationUrl,
      Clock clock,
      WhoEventPublisher eventPublisher) {
    this.credentials = requireNonNull(credentials, "credentials must not be null");
    this.requests = requireNonNull(requests, "requests must not be null");
    this.emails = requireNonNull(emails, "emails must not be null");
    this.settings = requireNonNull(settings, "settings must not be null");
    this.codeHasher = new EmailCodeHasher(settings.codeSecret());
    this.confirmationUrl = requireNonNull(confirmationUrl, "confirmationUrl must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.eventPublisher = requireNonNull(eventPublisher, "eventPublisher must not be null");
  }

  /**
   * Whether the credential is one of the identity's email addresses, which a change may replace.
   *
   * @param identityId the identity
   * @param credentialId the credential
   * @return {@code true} if it is the identity's email credential
   */
  public boolean ownsAddress(UUID identityId, UUID credentialId) {
    return credentials.isOwnedBy(credentialId, identityId);
  }

  /**
   * Asks to add an address to the identity, or with {@code replaces}, to make it the identity's in
   * place of that address. Sends the address a link and a code, unless it is not an address, is
   * registered already (to anyone), or a request by this identity or for this address was made
   * within the resend interval. A new request replaces the identity's earlier one. Publishes {@link
   * EmailAddressConfirmationRequested}.
   *
   * @param identityId the identity asking; the only one that can confirm
   * @param address the address as typed
   * @param replaces the identity's email credential it replaces, or {@code null} to add it; the
   *     caller checks it is the identity's
   * @return what became of the request
   */
  public EmailAddressRequestOutcome request(UUID identityId, String address, UUID replaces) {
    requireNonNull(identityId, "identityId must not be null");
    String normalized = looksLikeAnAddress(address) ? EmailCredential.normalize(address) : null;
    EmailAddressRequestOutcome outcome =
        normalized == null
            ? EmailAddressRequestOutcome.INVALID_ADDRESS
            : requestFor(identityId, normalized, replaces);
    try {
      eventPublisher.publish(
          new EmailAddressConfirmationRequested(
              clock.instant(), identityId, normalized, replaces != null, outcome));
    } catch (RuntimeException e) {
      log.warn("Failed to publish an email address request: {}", e.getClass().getName());
    }
    return outcome;
  }

  private EmailAddressRequestOutcome requestFor(UUID identityId, String address, UUID replaces) {
    Instant now = clock.instant();
    EmailAddressRequest request =
        new EmailAddressRequest(
            Identifiers.uuid(), identityId, address, replaces, now, now.plus(settings.tokenTtl()));
    String token = Secrets.token();
    String code = Secrets.code(settings.codeLength());
    try {
      if (credentials.findByAddress(address).isPresent()) {
        return EmailAddressRequestOutcome.ADDRESS_TAKEN;
      }
      if (!requests.save(
          request,
          MessageDigests.sha256Hex(token),
          codeHasher.hash(request.id(), code),
          settings.resendInterval())) {
        return EmailAddressRequestOutcome.RESEND_SUPPRESSED;
      }
    } catch (RuntimeException e) {
      log.warn(
          "Failed to save an email address request for identity {}: {}",
          identityId,
          e.getClass().getName());
      return EmailAddressRequestOutcome.SEND_FAILED;
    }
    try {
      emails.send(
          new EmailAddressConfirmationMessage(
              address,
              confirmationUrl + TOKEN_FRAGMENT + token,
              code,
              request.expiresAt(),
              settings.tokenTtl(),
              request.change()));
      return EmailAddressRequestOutcome.SENT;
    } catch (RuntimeException e) {
      log.warn(
          "Failed to send an email address confirmation for identity {}: {}",
          identityId,
          e.getClass().getName());
      deleteQuietly(request.id());
      return EmailAddressRequestOutcome.SEND_FAILED;
    }
  }

  /**
   * Confirms the identity's request with the token from the confirmation link.
   *
   * @param identity the identity signed in; a token works only for the identity that asked
   * @param token the token from the link
   * @return the identity's credential for the confirmed address
   * @throws InvalidEmailAddressConfirmationException if no live request of the identity matches
   * @throws EmailAddressTakenException if the address was registered since
   */
  public EmailCredential confirmToken(Identity identity, String token) {
    requireNonNull(identity, "identity must not be null");
    if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
      throw new InvalidEmailAddressConfirmationException();
    }
    EmailAddressRequest request =
        requests
            .consumeToken(identity.id(), MessageDigests.sha256Hex(token), clock.instant())
            .orElseThrow(InvalidEmailAddressConfirmationException::new);
    return apply(identity, request);
  }

  /**
   * Confirms the identity's request with the code from the confirmation email. Each try counts
   * against {@code codeMaxAttempts}; once they run out, even the right code fails.
   *
   * @param identity the identity signed in
   * @param code the code as typed
   * @return the identity's credential for the confirmed address
   * @throws InvalidEmailAddressConfirmationException if the code is malformed or wrong, or the
   *     identity has no live request with attempts left
   * @throws EmailAddressTakenException if the address was registered since
   */
  public EmailCredential confirmCode(Identity identity, String code) {
    requireNonNull(identity, "identity must not be null");
    String normalized =
        EmailCodes.normalize(code, settings.codeLength())
            .orElseThrow(InvalidEmailAddressConfirmationException::new);
    Instant now = clock.instant();
    Optional<EmailAddressCodeAttempt> attempt =
        requests.attemptCode(identity.id(), settings.codeMaxAttempts(), now);
    if (attempt.isEmpty()) {
      // the same work as a real comparison, so timing does not tell
      codeHasher.hash(DUMMY_ID, normalized);
      throw new InvalidEmailAddressConfirmationException();
    }
    String presented = codeHasher.hash(attempt.get().requestId(), normalized);
    if (!MessageDigest.isEqual(
        presented.getBytes(UTF_8), attempt.get().codeHash().getBytes(UTF_8))) {
      throw new InvalidEmailAddressConfirmationException();
    }
    EmailAddressRequest request =
        requests
            .consumeCode(attempt.get().requestId(), presented, now)
            .orElseThrow(InvalidEmailAddressConfirmationException::new);
    return apply(identity, request);
  }

  private EmailCredential apply(Identity identity, EmailAddressRequest request) {
    Optional<EmailCredential> old =
        request.change()
            ? credentials
                .findById(request.replacesCredentialId())
                .filter(c -> credentials.isOwnedBy(c.id(), identity.id()))
            : Optional.empty();
    if (old.isEmpty()) {
      return credentials.register(identity, request.address());
    }
    EmailCredential added = credentials.replace(identity, request.address(), old.get().id());
    try {
      emails.send(new EmailAddressChangedMessage(old.get().address(), added.address()));
    } catch (RuntimeException e) {
      log.warn(
          "Failed to tell a replaced address of identity {}: {}",
          identity.id(),
          e.getClass().getName());
    }
    return added;
  }

  private void deleteQuietly(UUID requestId) {
    try {
      requests.delete(requestId);
    } catch (RuntimeException e) {
      log.warn("Failed to remove an unsent email address request: {}", e.getClass().getName());
    }
  }

  private static boolean looksLikeAnAddress(String address) {
    return EmailAddresses.isSafe(address) && address.indexOf('@') >= 0;
  }
}
