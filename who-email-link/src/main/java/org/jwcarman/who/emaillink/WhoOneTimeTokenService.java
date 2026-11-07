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
import java.time.Duration;
import java.time.Instant;

import org.jwcarman.who.core.crypto.MessageDigests;
import org.jwcarman.who.core.crypto.Secrets;
import org.springframework.security.authentication.ott.DefaultOneTimeToken;
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest;
import org.springframework.security.authentication.ott.OneTimeToken;
import org.springframework.security.authentication.ott.OneTimeTokenAuthenticationToken;
import org.springframework.security.authentication.ott.OneTimeTokenService;

/**
 * Spring Security {@link OneTimeTokenService} for email links.
 *
 * <p>{@link #generate} only creates a random value and touches no table, so it takes the same time
 * whether or not the address is known; {@link EmailLinkIssuer} stores the token later, on another
 * thread. {@link #consume} atomically consumes the stored token by its hash. Only the SHA-256 hash
 * of a token value ever reaches the repository.
 */
public class WhoOneTimeTokenService implements OneTimeTokenService {

  private final EmailLinkTokenRepository repository;
  private final Clock clock;
  private final Duration tokenTtl;

  /**
   * Creates a new service.
   *
   * @param repository the token repository
   * @param clock the clock
   * @param tokenTtl how long a token stays valid
   */
  public WhoOneTimeTokenService(
      EmailLinkTokenRepository repository, Clock clock, Duration tokenTtl) {
    this.repository = requireNonNull(repository, "repository must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.tokenTtl = requireNonNull(tokenTtl, "tokenTtl must not be null");
  }

  /**
   * Generates a random token for the requested address without storing anything.
   *
   * @param request the request; its username is the address
   * @return the token
   */
  @Override
  public OneTimeToken generate(GenerateOneTimeTokenRequest request) {
    return new DefaultOneTimeToken(
        Secrets.token(), request.getUsername(), clock.instant().plus(tokenTtl));
  }

  /**
   * Consumes the token.
   *
   * @param authenticationToken the presented token
   * @return a token whose username is the email credential id, or {@code null} if the token is
   *     unknown, used, or expired
   */
  @Override
  public OneTimeToken consume(OneTimeTokenAuthenticationToken authenticationToken) {
    String value = authenticationToken.getTokenValue();
    Instant now = clock.instant();
    return repository
        .consume(MessageDigests.sha256Hex(value), now)
        .map(
            credentialId ->
                (OneTimeToken) new DefaultOneTimeToken(value, credentialId.toString(), now))
        .orElse(null);
  }
}
