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
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SignInFailed;
import org.jwcarman.who.core.event.SignInFailureReason;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.service.Resolution;
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
 * Signs in with an API key: checks the key's shape, looks it up by its hash, refuses it once
 * expired, and resolves it to an active identity whose permissions are cut down to the key's
 * scopes. A key's use is recorded at most once per interval; a failure to record it is logged and
 * never refuses the key.
 *
 * <p>The raw key is erased from the request token as soon as it is hashed, so it reaches no event,
 * exception or log line; neither does its hash.
 *
 * <p>A key that signs in returns a {@link WhoAuthenticationToken} with {@link SignInMethod#API_KEY}
 * and publishes no who event: a key authenticates every request, and Spring Security's {@code
 * AuthenticationSuccessEvent} already reports each one.
 *
 * <p>Every refusal is the same {@link BadCredentialsException} ({@value #INVALID_API_KEY}). It
 * publishes {@link SignInFailed} with {@link SignInFailureReason#UNKNOWN_KEY} when the key has the
 * wrong shape or checksum (without a lookup), is unknown, revoked or linked to no identity, {@link
 * SignInFailureReason#EXPIRED_KEY} once it has expired, or {@link
 * SignInFailureReason#INACTIVE_IDENTITY} when its identity is not active, and logs one DEBUG line.
 * A fault looking the key up propagates.
 *
 * <p>Not a bean: an {@link AuthenticationProvider} bean would join the application's global
 * authentication manager. {@link ApiKeyAuthenticationFilter} uses it.
 */
public class ApiKeyAuthenticationProvider implements AuthenticationProvider {

  private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationProvider.class);

  static final String INVALID_API_KEY = "Invalid API key";

  private final ApiKeyCredentialRepository apiKeyCredentialRepository;
  private final WhoService whoService;
  private final ApiKeyFormat format;
  private final Duration lastUsedInterval;
  private final WhoEventPublisher eventPublisher;
  private final Clock clock;

  /**
   * Creates the provider.
   *
   * @param apiKeyCredentialRepository looks keys up by their hash and records their use
   * @param whoService resolves a key to its identity
   * @param format checks a key's shape before any lookup
   * @param lastUsedInterval how often, at most, a key's use is recorded; positive
   * @param eventPublisher where refusals are published
   * @param clock decides expiry and stamps events and use
   * @throws IllegalArgumentException if {@code lastUsedInterval} is not positive
   */
  public ApiKeyAuthenticationProvider(
      ApiKeyCredentialRepository apiKeyCredentialRepository,
      WhoService whoService,
      ApiKeyFormat format,
      Duration lastUsedInterval,
      WhoEventPublisher eventPublisher,
      Clock clock) {
    this.apiKeyCredentialRepository =
        requireNonNull(apiKeyCredentialRepository, "apiKeyCredentialRepository must not be null");
    this.whoService = requireNonNull(whoService, "whoService must not be null");
    this.format = requireNonNull(format, "format must not be null");
    this.lastUsedInterval = requireNonNull(lastUsedInterval, "lastUsedInterval must not be null");
    if (lastUsedInterval.isNegative() || lastUsedInterval.isZero()) {
      throw new IllegalArgumentException(
          "lastUsedInterval must be positive, got " + lastUsedInterval);
    }
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
    boolean wellFormed = format.looksValid(key);
    String keyHash = ApiKeyService.sha256Hex(key);
    // the request token reaches Spring's failure events and exceptions; it must not hold the key
    request.eraseCredentials();
    if (!wellFormed) {
      // not a key at all: nothing to look up
      throw failed(SignInFailureReason.UNKNOWN_KEY, null, null);
    }
    Optional<ApiKeyCredential> found = apiKeyCredentialRepository.findByKeyHash(keyHash);
    if (found.isEmpty()) {
      throw failed(SignInFailureReason.UNKNOWN_KEY, null, null);
    }
    ApiKeyCredential credential = found.get();
    UUID credentialId = credential.id();
    Instant now = clock.instant();
    Resolution resolution =
        Objects.requireNonNullElse(whoService.resolveDetailed(credential), new NotLinked());
    if (credential.expiredAt(now)) {
      throw failed(SignInFailureReason.EXPIRED_KEY, identityOf(resolution), credentialId);
    }
    return switch (resolution) {
      case Resolved(WhoPrincipal principal) -> {
        recordUse(credentialId, now);
        yield succeeded(scoped(principal, credential.scopes()), credentialId);
      }
      case Inactive(UUID identityId) ->
          throw failed(SignInFailureReason.INACTIVE_IDENTITY, identityId, credentialId);
      // its identity is gone: as good as revoked
      case NoIdentity(UUID identityId) ->
          throw failed(SignInFailureReason.UNKNOWN_KEY, identityId, credentialId);
      case NotLinked() -> throw failed(SignInFailureReason.UNKNOWN_KEY, null, credentialId);
    };
  }

  /** The owner's permissions now, cut down to what the key may use. */
  private static WhoPrincipal scoped(WhoPrincipal owner, Set<String> scopes) {
    Set<String> permissions = new HashSet<>(owner.permissions());
    permissions.retainAll(scopes);
    return new WhoPrincipal(owner.identity(), permissions);
  }

  private static UUID identityOf(Resolution resolution) {
    return switch (resolution) {
      case Resolved(WhoPrincipal principal) -> principal.identity().id();
      case Inactive(UUID identityId) -> identityId;
      case NoIdentity(UUID identityId) -> identityId;
      case NotLinked() -> null;
    };
  }

  private void recordUse(UUID credentialId, Instant now) {
    try {
      apiKeyCredentialRepository.touch(credentialId, now, now.minus(lastUsedInterval));
    } catch (RuntimeException e) {
      // a key that signs in is not refused for want of a timestamp
      log.warn("Failed to record an API key's last use: {}", e.getClass().getName());
    }
  }

  private static WhoAuthenticationToken succeeded(WhoPrincipal principal, UUID credentialId) {
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
