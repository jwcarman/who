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

import java.util.UUID;

import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ott.InvalidOneTimeTokenException;
import org.springframework.security.authentication.ott.OneTimeToken;
import org.springframework.security.authentication.ott.OneTimeTokenAuthenticationToken;
import org.springframework.security.authentication.ott.OneTimeTokenService;
import org.springframework.security.core.Authentication;

/**
 * Authenticates an email-link token: consumes it, then resolves its email credential to an active
 * identity. Every failure is the same {@link InvalidOneTimeTokenException}.
 */
public class EmailLinkAuthenticationProvider implements AuthenticationProvider {

  private static final String INVALID_TOKEN = "invalid_token";

  private final OneTimeTokenService tokenService;
  private final WhoService whoService;

  /**
   * Creates a new provider.
   *
   * @param tokenService the one-time token service
   * @param whoService resolves credentials to principals
   */
  public EmailLinkAuthenticationProvider(OneTimeTokenService tokenService, WhoService whoService) {
    this.tokenService = requireNonNull(tokenService, "tokenService must not be null");
    this.whoService = requireNonNull(whoService, "whoService must not be null");
  }

  @Override
  public Authentication authenticate(Authentication authentication) {
    OneTimeTokenAuthenticationToken presented = (OneTimeTokenAuthenticationToken) authentication;
    if (JsonOneTimeTokenAuthenticationConverter.NO_TOKEN.equals(presented.getTokenValue())) {
      throw new InvalidOneTimeTokenException(INVALID_TOKEN);
    }
    OneTimeToken token = tokenService.consume(presented);
    if (token == null) {
      throw new InvalidOneTimeTokenException(INVALID_TOKEN);
    }
    UUID credentialId = parse(token.getUsername());
    WhoPrincipal principal =
        whoService
            .resolve(() -> credentialId)
            .orElseThrow(() -> new InvalidOneTimeTokenException(INVALID_TOKEN));
    return new WhoAuthenticationToken(principal, credentialId);
  }

  private static UUID parse(String username) {
    try {
      return UUID.fromString(username);
    } catch (IllegalArgumentException _) {
      throw new InvalidOneTimeTokenException(INVALID_TOKEN);
    }
  }

  @Override
  public boolean supports(Class<?> authentication) {
    return OneTimeTokenAuthenticationToken.class.isAssignableFrom(authentication);
  }
}
