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
 * thread. {@link #consume} is never used: {@link EmailLinkAuthenticationProvider} redeems sign-ins
 * itself, by link token or by code.
 */
public class WhoOneTimeTokenService implements OneTimeTokenService {

  private final Clock clock;
  private final Duration tokenTtl;

  /**
   * Creates a new service.
   *
   * @param clock the clock
   * @param tokenTtl how long a token stays valid
   */
  public WhoOneTimeTokenService(Clock clock, Duration tokenTtl) {
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
   * Not supported; {@link EmailLinkAuthenticationProvider} redeems sign-ins.
   *
   * @param authenticationToken ignored
   * @return never
   * @throws UnsupportedOperationException always
   */
  @Override
  public OneTimeToken consume(OneTimeTokenAuthenticationToken authenticationToken) {
    throw new UnsupportedOperationException(
        "who's EmailLinkAuthenticationProvider redeems sign-ins");
  }
}
