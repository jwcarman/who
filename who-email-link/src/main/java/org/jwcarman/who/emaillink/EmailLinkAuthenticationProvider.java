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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jwcarman.who.core.crypto.MessageDigests;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SignInFailed;
import org.jwcarman.who.core.event.SignInFailureReason;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.service.Resolution.Inactive;
import org.jwcarman.who.core.service.Resolution.NoIdentity;
import org.jwcarman.who.core.service.Resolution.NotLinked;
import org.jwcarman.who.core.service.Resolution.Resolved;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.emaillink.EmailSignInProof.EmailCode;
import org.jwcarman.who.emaillink.EmailSignInProof.LinkToken;
import org.jwcarman.who.emaillink.EmailSignInProof.Malformed;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ott.InvalidOneTimeTokenException;
import org.springframework.security.core.Authentication;

/**
 * Authenticates an email sign-in, by the token from the link or by the address and the code typed
 * by hand, then resolves its email credential to an active identity. Every failure is the same
 * {@link InvalidOneTimeTokenException}, whatever the cause.
 *
 * <p>A code attempt is counted before the code is compared, so a token allows at most {@link
 * EmailLinkSettings#codeMaxAttempts()} guesses. When no attempt is available, a dummy hash is still
 * computed, so the work done does not reveal whether the address has a live token.
 *
 * <p>Each failure publishes {@link SignInFailed} with its server-side reason, and logs one DEBUG
 * line; the address appears in no log line above DEBUG. A success is returned as a {@link
 * WhoAuthenticationToken} that carries its {@link SignInMethod}, and is published by who's success
 * handler once the session is saved, not here.
 */
public class EmailLinkAuthenticationProvider implements AuthenticationProvider {

  private static final Logger log = LoggerFactory.getLogger(EmailLinkAuthenticationProvider.class);

  private static final String INVALID_TOKEN = "invalid_token";
  private static final UUID DUMMY_ID = new UUID(0, 0);

  private final EmailLinkTokenRepository repository;
  private final WhoService whoService;
  private final Clock clock;
  private final WhoEventPublisher eventPublisher;
  private final EmailCodeHasher hasher;
  private final int codeLength;
  private final int codeMaxAttempts;

  /**
   * Creates a new provider that publishes no events.
   *
   * @param repository the token repository
   * @param settings the email-link settings
   * @param whoService resolves credentials to principals
   * @param clock the clock
   */
  public EmailLinkAuthenticationProvider(
      EmailLinkTokenRepository repository,
      EmailLinkSettings settings,
      WhoService whoService,
      Clock clock) {
    this(repository, settings, whoService, clock, WhoEventPublisher.NOOP);
  }

  /**
   * Creates a new provider.
   *
   * @param repository the token repository
   * @param settings the email-link settings
   * @param whoService resolves credentials to principals
   * @param clock the clock
   * @param eventPublisher where {@link SignInFailed} is published
   */
  public EmailLinkAuthenticationProvider(
      EmailLinkTokenRepository repository,
      EmailLinkSettings settings,
      WhoService whoService,
      Clock clock,
      WhoEventPublisher eventPublisher) {
    this.repository = requireNonNull(repository, "repository must not be null");
    requireNonNull(settings, "settings must not be null");
    this.whoService = requireNonNull(whoService, "whoService must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.eventPublisher = requireNonNull(eventPublisher, "eventPublisher must not be null");
    this.hasher = new EmailCodeHasher(settings.codeSecret());
    this.codeLength = settings.codeLength();
    this.codeMaxAttempts = settings.codeMaxAttempts();
  }

  @Override
  public Authentication authenticate(Authentication authentication) {
    EmailSignInProof proof = ((EmailSignInAuthenticationToken) authentication).proof();
    Instant now = clock.instant();
    return switch (proof) {
      case LinkToken(String token) -> authenticateLink(token, now);
      case EmailCode(String address, String code) -> authenticateCode(address, code, now);
      // a request that is neither form cannot say which it meant; it is reported as a link
      case Malformed() ->
          throw failed(
              now, SignInMethod.EMAIL_LINK, SignInFailureReason.MALFORMED, null, null, null);
    };
  }

  private Authentication authenticateLink(String token, Instant now) {
    Optional<UUID> credentialId = repository.consume(MessageDigests.sha256Hex(token), now);
    if (credentialId.isEmpty()) {
      throw failed(
          now, SignInMethod.EMAIL_LINK, SignInFailureReason.INVALID_LINK, null, null, null);
    }
    return signIn(now, SignInMethod.EMAIL_LINK, null, credentialId.get());
  }

  private Authentication authenticateCode(String typedAddress, String typedCode, Instant now) {
    // the address goes into the event and the log only once it is known to be safe
    String address =
        EmailAddresses.isSafe(typedAddress) ? EmailCredential.normalize(typedAddress) : null;
    Optional<String> normalized = EmailCodes.normalize(typedCode, codeLength);
    if (normalized.isEmpty() || address == null) {
      throw failed(
          now, SignInMethod.EMAIL_CODE, SignInFailureReason.MALFORMED, address, null, null);
    }
    String code = normalized.get();
    Optional<EmailCodeAttempt> attempt = repository.attemptCode(address, codeMaxAttempts, now);
    if (attempt.isEmpty()) {
      hasher.hash(DUMMY_ID, code);
      throw failed(
          now, SignInMethod.EMAIL_CODE, SignInFailureReason.NO_LIVE_CODE, address, null, null);
    }
    UUID credentialId = attempt.get().credentialId();
    String presented = hasher.hash(credentialId, code);
    if (!MessageDigest.isEqual(
        presented.getBytes(UTF_8), attempt.get().codeHash().getBytes(UTF_8))) {
      throw failed(
          now,
          SignInMethod.EMAIL_CODE,
          SignInFailureReason.WRONG_CODE,
          address,
          null,
          credentialId);
    }
    if (repository.consumeCode(credentialId, presented, now).isEmpty()) {
      throw failed(
          now,
          SignInMethod.EMAIL_CODE,
          SignInFailureReason.CODE_ALREADY_USED,
          address,
          null,
          credentialId);
    }
    return signIn(now, SignInMethod.EMAIL_CODE, address, credentialId);
  }

  private Authentication signIn(
      Instant now, SignInMethod method, String address, UUID credentialId) {
    // a credential that resolves to no identity reads as a spent link or code
    SignInFailureReason unresolved =
        method == SignInMethod.EMAIL_LINK
            ? SignInFailureReason.INVALID_LINK
            : SignInFailureReason.NO_LIVE_CODE;
    return switch (Objects.requireNonNullElse(
        whoService.resolveDetailed(() -> credentialId), new NotLinked())) {
      case Resolved(WhoPrincipal principal) ->
          new WhoAuthenticationToken(principal, credentialId, method);
      case Inactive(UUID identityId) ->
          throw failed(
              now,
              method,
              SignInFailureReason.INACTIVE_IDENTITY,
              address,
              identityId,
              credentialId);
      case NoIdentity(UUID identityId) ->
          throw failed(now, method, unresolved, address, identityId, credentialId);
      case NotLinked() -> throw failed(now, method, unresolved, address, null, credentialId);
    };
  }

  /** Logs and publishes a failure, and returns the uniform exception to throw. */
  private InvalidOneTimeTokenException failed(
      Instant now,
      SignInMethod method,
      SignInFailureReason reason,
      String address,
      UUID identityId,
      UUID credentialId) {
    log.debug(
        "Email sign-in failed: method={}, reason={}, identityId={}, credentialId={}, address={}",
        method,
        reason,
        identityId,
        credentialId,
        address);
    eventPublisher.publish(
        new SignInFailed(now, method, reason, address, identityId, credentialId));
    return new InvalidOneTimeTokenException(INVALID_TOKEN);
  }

  @Override
  public boolean supports(Class<?> authentication) {
    return EmailSignInAuthenticationToken.class.isAssignableFrom(authentication);
  }
}
