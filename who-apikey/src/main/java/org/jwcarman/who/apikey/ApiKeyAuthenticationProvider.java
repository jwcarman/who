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
package org.jwcarman.who.apikey;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SignInFailed;
import org.jwcarman.who.core.event.SignInFailureReason;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.SignInSucceeded;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.service.Resolution.Inactive;
import org.jwcarman.who.core.service.Resolution.NoIdentity;
import org.jwcarman.who.core.service.Resolution.NotLinked;
import org.jwcarman.who.core.service.Resolution.Resolved;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;

/**
 * Signs in with an API key: looks the key up by its hash and resolves the credential to an active
 * identity.
 *
 * <p>The raw key is erased from the request token as soon as it is hashed, so it reaches no event,
 * exception or log line; neither does its hash.
 *
 * <p>A key that signs in returns a {@link WhoAuthenticationToken} with {@link SignInMethod#API_KEY}
 * and publishes {@link SignInSucceeded}. API keys sign in on every request and save no session, so
 * the sign-in is complete here and this provider, not a success handler, publishes it.
 *
 * <p>Every refusal is the same {@link BadCredentialsException} ({@value #INVALID_API_KEY}). It
 * publishes {@link SignInFailed} with {@link SignInFailureReason#UNKNOWN_KEY} when the key is
 * unknown, revoked or linked to no identity, or {@link SignInFailureReason#INACTIVE_IDENTITY} when
 * its identity is not active, and logs one DEBUG line. A fault looking the key up propagates.
 *
 * <p>Not a bean: an {@link AuthenticationProvider} bean would join the application's global
 * authentication manager. {@link ApiKeyAuthenticationFilter} uses it.
 */
public class ApiKeyAuthenticationProvider implements AuthenticationProvider {

  private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationProvider.class);

  static final String INVALID_API_KEY = "Invalid API key";

  private final ApiKeyCredentialRepository apiKeyCredentialRepository;
  private final WhoService whoService;
  private final WhoEventPublisher eventPublisher;
  private final Clock clock;

  /**
   * Creates the provider.
   *
   * @param apiKeyCredentialRepository repository for looking up hashed API keys
   * @param whoService service for resolving a credential to a principal
   * @param eventPublisher where {@link SignInSucceeded} and {@link SignInFailed} are published
   * @param clock stamps the events
   */
  public ApiKeyAuthenticationProvider(
      ApiKeyCredentialRepository apiKeyCredentialRepository,
      WhoService whoService,
      WhoEventPublisher eventPublisher,
      Clock clock) {
    this.apiKeyCredentialRepository =
        requireNonNull(apiKeyCredentialRepository, "apiKeyCredentialRepository must not be null");
    this.whoService = requireNonNull(whoService, "whoService must not be null");
    this.eventPublisher = requireNonNull(eventPublisher, "eventPublisher must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
  }

  @Override
  public Authentication authenticate(Authentication authentication) {
    ApiKeyAuthenticationToken request = (ApiKeyAuthenticationToken) authentication;
    String key = request.getCredentials();
    if (key == null) {
      // already erased: nothing to look up, and nothing to report
      throw new BadCredentialsException(INVALID_API_KEY);
    }
    String keyHash = ApiKeyService.sha256Hex(key);
    // the request token reaches Spring's failure events and exceptions; it must not hold the key
    request.eraseCredentials();
    Optional<ApiKeyCredential> found = apiKeyCredentialRepository.findByKeyHash(keyHash);
    if (found.isEmpty()) {
      throw failed(SignInFailureReason.UNKNOWN_KEY, null, null);
    }
    ApiKeyCredential credential = found.get();
    UUID credentialId = credential.id();
    return switch (whoService.resolveDetailed(credential)) {
      case Resolved(WhoPrincipal principal) -> succeeded(principal, credentialId);
      case Inactive(UUID identityId) ->
          throw failed(SignInFailureReason.INACTIVE_IDENTITY, identityId, credentialId);
      // its identity is gone: as good as revoked
      case NoIdentity(UUID identityId) ->
          throw failed(SignInFailureReason.UNKNOWN_KEY, identityId, credentialId);
      case NotLinked() -> throw failed(SignInFailureReason.UNKNOWN_KEY, null, credentialId);
    };
  }

  private WhoAuthenticationToken succeeded(WhoPrincipal principal, UUID credentialId) {
    eventPublisher.publish(
        new SignInSucceeded(
            clock.instant(), SignInMethod.API_KEY, principal.identity().id(), credentialId));
    return new WhoAuthenticationToken(principal, credentialId, SignInMethod.API_KEY);
  }

  /** Logs and publishes a failure, and returns the uniform exception to throw. */
  private BadCredentialsException failed(
      SignInFailureReason reason, UUID identityId, UUID credentialId) {
    log.debug(
        "API key sign-in failed: method={}, reason={}, identityId={}, credentialId={}",
        SignInMethod.API_KEY,
        reason,
        identityId,
        credentialId);
    eventPublisher.publish(
        new SignInFailed(
            clock.instant(), SignInMethod.API_KEY, reason, null, identityId, credentialId));
    return new BadCredentialsException(INVALID_API_KEY);
  }

  @Override
  public boolean supports(Class<?> authentication) {
    return ApiKeyAuthenticationToken.class.isAssignableFrom(authentication);
  }
}
