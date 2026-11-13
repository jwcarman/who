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
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.emaillink.EmailSignInProof.EmailCode;
import org.jwcarman.who.emaillink.EmailSignInProof.LinkToken;
import org.jwcarman.who.emaillink.EmailSignInProof.Malformed;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
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
 */
public class EmailLinkAuthenticationProvider implements AuthenticationProvider {

  private static final String INVALID_TOKEN = "invalid_token";
  private static final UUID DUMMY_ID = new UUID(0, 0);
  private static final int MAX_ADDRESS_LENGTH = 320;

  private final EmailLinkTokenRepository repository;
  private final WhoService whoService;
  private final Clock clock;
  private final EmailCodeHasher hasher;
  private final int codeLength;
  private final int codeMaxAttempts;

  /**
   * Creates a new provider.
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
    this.repository = requireNonNull(repository, "repository must not be null");
    requireNonNull(settings, "settings must not be null");
    this.whoService = requireNonNull(whoService, "whoService must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.hasher = new EmailCodeHasher(settings.codeSecret());
    this.codeLength = settings.codeLength();
    this.codeMaxAttempts = settings.codeMaxAttempts();
  }

  @Override
  public Authentication authenticate(Authentication authentication) {
    EmailSignInProof proof = ((EmailSignInAuthenticationToken) authentication).proof();
    UUID credentialId =
        redeem(proof, clock.instant()).orElseThrow(EmailLinkAuthenticationProvider::invalid);
    WhoPrincipal principal =
        whoService
            .resolve(() -> credentialId)
            .orElseThrow(EmailLinkAuthenticationProvider::invalid);
    return new WhoAuthenticationToken(principal, credentialId);
  }

  private Optional<UUID> redeem(EmailSignInProof proof, Instant now) {
    return switch (proof) {
      case LinkToken(String token) -> redeemLink(token, now);
      case EmailCode(String address, String code) -> redeemCode(address, code, now);
      case Malformed() -> Optional.empty();
    };
  }

  private Optional<UUID> redeemLink(String token, Instant now) {
    return repository.consume(MessageDigests.sha256Hex(token), now);
  }

  private Optional<UUID> redeemCode(String address, String typedCode, Instant now) {
    Optional<String> normalized = EmailCodes.normalize(typedCode, codeLength);
    if (normalized.isEmpty() || !isSafeAddress(address)) {
      return Optional.empty();
    }
    String code = normalized.get();
    Optional<EmailCodeAttempt> attempt =
        repository.attemptCode(EmailCredential.normalize(address), codeMaxAttempts, now);
    if (attempt.isEmpty()) {
      hasher.hash(DUMMY_ID, code);
      return Optional.empty();
    }
    UUID credentialId = attempt.get().credentialId();
    String presented = hasher.hash(credentialId, code);
    if (!MessageDigest.isEqual(
        presented.getBytes(UTF_8), attempt.get().codeHash().getBytes(UTF_8))) {
      return Optional.empty();
    }
    return repository.consumeCode(credentialId, presented, now);
  }

  /**
   * Rejects an address by its shape alone, before it reaches the database: blank, longer than an
   * email address can be, or carrying a control character such as NUL.
   */
  private static boolean isSafeAddress(String address) {
    return !address.isBlank()
        && address.length() <= MAX_ADDRESS_LENGTH
        && address.chars().noneMatch(Character::isISOControl);
  }

  private static InvalidOneTimeTokenException invalid() {
    return new InvalidOneTimeTokenException(INVALID_TOKEN);
  }

  @Override
  public boolean supports(Class<?> authentication) {
    return EmailSignInAuthenticationToken.class.isAssignableFrom(authentication);
  }
}
