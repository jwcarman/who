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
package org.jwcarman.who.spring.security.web;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.service.LastCredentialGuard;
import org.jwcarman.who.spring.security.RecentSignInAuthorizationManager;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.jwcarman.who.spring.security.WhoSession;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * who's one endpoint of its own: {@code GET /who/me} tells an app who is signed in.
 *
 * <p>Registered as a bean by who's autoconfiguration; it lives in its own package so an
 * application's component scan does not pick it up by accident.
 */
@RestController
public class WhoMeController {

  private final WhoSessionSecurityContextRepository repository;
  private final RecentSignInAuthorizationManager recentSignIn;
  private final ObjectProvider<LastCredentialGuard> guard;

  /**
   * Creates the controller.
   *
   * @param repository says whether a request is a browser session, and which
   * @param recentSignIn says how long a sign-in stays recent
   * @param guard counts the caller's sign-in credentials; the guard needs a single transaction
   *     manager, so it may be absent, and {@code credentials} is then empty
   */
  public WhoMeController(
      WhoSessionSecurityContextRepository repository,
      RecentSignInAuthorizationManager recentSignIn,
      ObjectProvider<LastCredentialGuard> guard) {
    this.repository = requireNonNull(repository, "repository must not be null");
    this.recentSignIn = requireNonNull(recentSignIn, "recentSignIn must not be null");
    this.guard = requireNonNull(guard, "guard must not be null");
  }

  /**
   * The signed-in identity.
   *
   * @param identityId the identity's id
   * @param permissions the identity's current permissions
   * @param signInMethod the {@link SignInMethod} name of the sign-in that authenticated this
   *     request, such as {@code PASSKEY} or {@code API_KEY}; for a browser, how the session signed
   *     in. {@code null} when that is not known, as for a session saved before who stored it
   * @param recentSignInUntil when the browser session's sign-in stops being recent, which may
   *     already have passed; {@code null} unless the request is a browser session's, since an API
   *     key or a bearer token stands for no sign-in
   * @param credentials how many sign-in credentials the identity holds, by kind ({@code email},
   *     {@code passkey}, {@code account}); empty when who cannot count them
   */
  public record WhoMeResponse(
      UUID identityId,
      Set<String> permissions,
      String signInMethod,
      Instant recentSignInUntil,
      Map<String, Long> credentials) {}

  /**
   * Returns the current principal, freshly resolved for this request.
   *
   * @param request the request
   * @param authentication the request's authentication, or {@code null} if there is none
   * @return {@code 200} with the identity
   * @throws NotSignedInException when no one is signed in; the application formats it
   */
  @GetMapping("${who.base-path:/who}/me")
  public ResponseEntity<WhoMeResponse> me(
      HttpServletRequest request, Authentication authentication) {
    if (authentication == null
        || !(authentication.getPrincipal() instanceof WhoPrincipal principal)) {
      throw new NotSignedInException();
    }
    UUID identityId = principal.identity().id();
    // only a browser session's sign-in can be recent; a session cookie beside an API key or a
    // bearer token is not one, and that token is what authenticated the request
    Optional<WhoSession> session = repository.browserSession(request, authentication);
    String method =
        session
            .map(WhoSession::signInMethod)
            .or(() -> tokenMethod(authentication))
            .map(SignInMethod::name)
            .orElse(null);
    Instant recentUntil = session.map(s -> s.signedInAt().plus(recentSignIn.window())).orElse(null);
    Map<String, Long> credentials =
        Optional.ofNullable(guard.getIfAvailable()).map(g -> g.counts(identityId)).orElse(Map.of());
    return ResponseEntity.ok(
        new WhoMeResponse(identityId, principal.permissions(), method, recentUntil, credentials));
  }

  private static Optional<SignInMethod> tokenMethod(Authentication authentication) {
    return authentication instanceof WhoAuthenticationToken token
        ? token.signInMethod()
        : Optional.empty();
  }
}
