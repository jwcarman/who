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
package org.jwcarman.who.passkey;

import static java.util.Objects.requireNonNull;

import java.util.Optional;
import java.util.UUID;

import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthenticationRequestToken;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;

/**
 * Signs in with a passkey: verifies the assertion through the relying party, maps the verified
 * credential to who's credential id, and resolves it to an active identity.
 *
 * <p>Every failure, whether the assertion does not verify, the credential is unknown, the identity
 * is not active, or anything else goes wrong, is the same {@link BadCredentialsException} with the
 * message {@code invalid_credential}.
 */
public class WhoPasskeyAuthenticationProvider implements AuthenticationProvider {

  static final String INVALID_CREDENTIAL = "invalid_credential";

  private final WebAuthnRelyingPartyOperations rpOperations;
  private final JdbcWhoPasskeyCredentialRepository credentialRepository;
  private final WhoService whoService;

  /**
   * Creates the provider.
   *
   * @param rpOperations verifies assertions; the same instance Spring's WebAuthn filters use
   * @param credentialRepository maps WebAuthn credential ids to who's credential ids
   * @param whoService resolves credentials to principals
   */
  public WhoPasskeyAuthenticationProvider(
      WebAuthnRelyingPartyOperations rpOperations,
      JdbcWhoPasskeyCredentialRepository credentialRepository,
      WhoService whoService) {
    this.rpOperations = requireNonNull(rpOperations, "rpOperations must not be null");
    this.credentialRepository =
        requireNonNull(credentialRepository, "credentialRepository must not be null");
    this.whoService = requireNonNull(whoService, "whoService must not be null");
  }

  @Override
  public Authentication authenticate(Authentication authentication) {
    RelyingPartyAuthenticationRequest request =
        ((WebAuthnAuthenticationRequestToken) authentication).getWebAuthnRequest();
    Optional<WhoAuthenticationToken> result;
    try {
      result = signIn(request);
    } catch (RuntimeException e) {
      throw new BadCredentialsException(INVALID_CREDENTIAL, e);
    }
    return result.orElseThrow(() -> new BadCredentialsException(INVALID_CREDENTIAL));
  }

  private Optional<WhoAuthenticationToken> signIn(RelyingPartyAuthenticationRequest request) {
    // Verifies the signature, user verification and origin, and updates the signature count.
    rpOperations.authenticate(request);
    // The raw id is the credential the relying party just looked up and verified.
    Optional<UUID> credentialUuid =
        credentialRepository.credentialUuid(request.getPublicKey().getRawId());
    return credentialUuid.flatMap(
        uuid ->
            whoService
                .resolve(() -> uuid)
                .map(principal -> new WhoAuthenticationToken(principal, uuid)));
  }

  @Override
  public boolean supports(Class<?> authentication) {
    return WebAuthnAuthenticationRequestToken.class.isAssignableFrom(authentication);
  }
}
