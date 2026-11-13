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
package org.jwcarman.who.jwt;

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
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;

/**
 * Converts a Spring Security {@link Jwt} (already validated by the OAuth2 resource server) into a
 * {@link WhoAuthenticationToken} by resolving the JWT's issuer/subject pair to a {@link
 * WhoPrincipal}.
 *
 * <p>Throws {@link BadCredentialsException} — causing Spring Security to return a 401 — when:
 *
 * <ul>
 *   <li>the JWT has no {@code iss} or no {@code sub} claim,
 *   <li>no {@link JwtCredential} is registered for the issuer/subject pair, or
 *   <li>{@link WhoService#resolveDetailed} finds no active identity linked to the credential.
 * </ul>
 *
 * <p>This converter does <em>not</em> auto-provision identities. The credential must be explicitly
 * registered before access is granted.
 *
 * <p>JWTs sign in on every request and save no session, and do not pass through the form-login
 * handlers, so this converter publishes the sign-in events itself: {@link SignInSucceeded} for a
 * JWT that signs in, and {@link SignInFailed} with {@link SignInFailureReason#MISSING_CLAIMS},
 * {@link SignInFailureReason#UNKNOWN_CREDENTIAL} or {@link SignInFailureReason#INACTIVE_IDENTITY}
 * for one that does not. Each failure also logs one DEBUG line. The token is never published or
 * logged; the subject appears only in that DEBUG line. Spring calls the converter once per request,
 * so a request publishes one event. A JWT that Spring rejects before conversion (bad signature,
 * expired) never reaches it and publishes nothing.
 */
public class WhoJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

  private static final Logger log = LoggerFactory.getLogger(WhoJwtAuthenticationConverter.class);

  private final JwtCredentialRepository jwtCredentialRepository;
  private final WhoService whoService;
  private final WhoEventPublisher eventPublisher;
  private final Clock clock;

  /**
   * Creates a new converter that publishes no events.
   *
   * @param jwtCredentialRepository repository for looking up registered JWT credentials
   * @param whoService service for resolving a credential to a principal
   */
  public WhoJwtAuthenticationConverter(
      JwtCredentialRepository jwtCredentialRepository, WhoService whoService) {
    this(jwtCredentialRepository, whoService, WhoEventPublisher.NOOP, Clock.systemUTC());
  }

  /**
   * Creates a new converter.
   *
   * @param jwtCredentialRepository repository for looking up registered JWT credentials
   * @param whoService service for resolving a credential to a principal
   * @param eventPublisher where {@link SignInSucceeded} and {@link SignInFailed} are published
   * @param clock stamps the events
   */
  public WhoJwtAuthenticationConverter(
      JwtCredentialRepository jwtCredentialRepository,
      WhoService whoService,
      WhoEventPublisher eventPublisher,
      Clock clock) {
    this.jwtCredentialRepository =
        requireNonNull(jwtCredentialRepository, "jwtCredentialRepository must not be null");
    this.whoService = requireNonNull(whoService, "whoService must not be null");
    this.eventPublisher = requireNonNull(eventPublisher, "eventPublisher must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
  }

  @Override
  public AbstractAuthenticationToken convert(Jwt jwt) {
    String issuer = jwt.getClaimAsString(JwtClaimNames.ISS);
    String subject = jwt.getClaimAsString(JwtClaimNames.SUB);
    if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
      throw failed(
          SignInFailureReason.MISSING_CLAIMS,
          "JWT is missing iss or sub",
          issuer,
          subject,
          null,
          null);
    }
    Optional<JwtCredential> found = jwtCredentialRepository.findByIssuerAndSubject(issuer, subject);
    if (found.isEmpty()) {
      throw unknown(issuer, subject, null, null);
    }
    UUID credentialId = found.get().id();
    return switch (whoService.resolveDetailed(found.get())) {
      case Resolved(WhoPrincipal principal) -> succeeded(principal, credentialId);
      case Inactive(UUID identityId) ->
          throw failed(
              SignInFailureReason.INACTIVE_IDENTITY,
              "Unknown JWT credential",
              issuer,
              subject,
              identityId,
              credentialId);
      case NoIdentity(UUID identityId) -> throw unknown(issuer, subject, identityId, credentialId);
      case NotLinked() -> throw unknown(issuer, subject, null, credentialId);
    };
  }

  private WhoAuthenticationToken succeeded(WhoPrincipal principal, UUID credentialId) {
    eventPublisher.publish(
        new SignInSucceeded(
            clock.instant(), SignInMethod.JWT, principal.identity().id(), credentialId));
    return new WhoAuthenticationToken(principal, credentialId, SignInMethod.JWT);
  }

  private BadCredentialsException unknown(
      String issuer, String subject, UUID identityId, UUID credentialId) {
    return failed(
        SignInFailureReason.UNKNOWN_CREDENTIAL,
        "Unknown JWT credential",
        issuer,
        subject,
        identityId,
        credentialId);
  }

  /** Logs and publishes a failure, and returns the exception to throw. */
  private BadCredentialsException failed(
      SignInFailureReason reason,
      String message,
      String issuer,
      String subject,
      UUID identityId,
      UUID credentialId) {
    log.debug(
        "JWT sign-in failed: method={}, reason={}, issuer={}, subject={}, identityId={}, credentialId={}",
        SignInMethod.JWT,
        reason,
        issuer,
        subject,
        identityId,
        credentialId);
    eventPublisher.publish(
        new SignInFailed(
            clock.instant(), SignInMethod.JWT, reason, null, identityId, credentialId));
    return new BadCredentialsException(message);
  }
}
