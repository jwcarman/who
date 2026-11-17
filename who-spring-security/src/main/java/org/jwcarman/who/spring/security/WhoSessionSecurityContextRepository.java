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
package org.jwcarman.who.spring.security;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.jwcarman.who.core.domain.SessionRecord;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SessionEndReason;
import org.jwcarman.who.core.event.SessionEnded;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.SignedOut;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.service.Resolution;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.service.WhoSessions;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Keeps only a {@link WhoSession} (ids) in the HTTP session and re-resolves the principal from
 * {@link WhoService} on every request, so a suspended identity, a removed role or a revoked
 * credential takes effect immediately.
 */
public class WhoSessionSecurityContextRepository implements SecurityContextRepository {

  /** Name of the HTTP session attribute holding the {@link WhoSession}. */
  public static final String SESSION_ATTRIBUTE = "org.jwcarman.who.session";

  /**
   * Name of the session attribute Spring Session indexes by principal name. who sets it to the
   * identity id, so {@code FindByIndexNameSessionRepository.findByPrincipalName(identityId)} finds
   * every session of an identity. The literal avoids a dependency on Spring Session.
   */
  public static final String PRINCIPAL_INDEX_ATTRIBUTE =
      "org.springframework.session.FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME";

  private static final String LOADED_ATTRIBUTE =
      WhoSessionSecurityContextRepository.class.getName() + ".LOADED";

  private final WhoService whoService;
  private final WhoSessions sessions;
  private final Clock clock;
  private final WhoEventPublisher eventPublisher;
  private final RequestMatcher statelessCredentials;

  /**
   * Creates the repository.
   *
   * @param whoService resolves the principal
   * @param sessions records each sign-in and says whether its session is live
   * @param clock stamps the sign-in time
   * @param eventPublisher publishes session events
   * @param statelessCredentials matches a request that presents a stateless method's credential,
   *     such as an API key or a bearer token; such a request is never a {@link WhoCaller.Session}
   *     {@link #caller caller}. An application with no stateless sign-in methods passes {@code
   *     request -> false}
   */
  public WhoSessionSecurityContextRepository(
      WhoService whoService,
      WhoSessions sessions,
      Clock clock,
      WhoEventPublisher eventPublisher,
      RequestMatcher statelessCredentials) {
    this.whoService = requireNonNull(whoService, "whoService must not be null");
    this.sessions = requireNonNull(sessions, "sessions must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.eventPublisher = requireNonNull(eventPublisher, "eventPublisher must not be null");
    this.statelessCredentials =
        requireNonNull(statelessCredentials, "statelessCredentials must not be null");
  }

  @Override
  public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
    return new LazyContext(() -> load(request));
  }

  /**
   * {@inheritDoc}
   *
   * @deprecated use {@link #loadDeferredContext(HttpServletRequest)}
   */
  @Override
  @Deprecated(since = "5.8")
  public SecurityContext loadContext(HttpRequestResponseHolder holder) {
    return load(holder.getRequest());
  }

  @Override
  public void saveContext(
      SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
    Authentication authentication = context.getAuthentication();
    if (authentication instanceof WhoAuthenticationToken token
        && token.credentialId().isPresent()) {
      UUID identityId = token.getPrincipal().identity().id();
      UUID credentialId = token.credentialId().get();
      Optional<WhoSession> existing = currentSession(request);
      if (authentication == request.getAttribute(LOADED_ATTRIBUTE)
          && existing.isPresent()
          && existing.get().identityId().equals(identityId)
          && existing.get().credentialId().equals(credentialId)) {
        return;
      }
      SignInMethod method =
          token
              .signInMethod()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "A new sign-in must say how it signed in: build the token with its"
                              + " SignInMethod"));
      // signing in again in this browser ends the session it replaces
      existing.map(WhoSession::sessionId).ifPresent(sessions::close);
      Instant now = clock.instant();
      SessionRecord row =
          sessions.start(
              identityId, credentialId, method, request.getHeader(HttpHeaders.USER_AGENT), now);
      HttpSession session = request.getSession(true);
      session.setAttribute(
          SESSION_ATTRIBUTE, new WhoSession(identityId, credentialId, now, method, row.id()));
      session.setAttribute(PRINCIPAL_INDEX_ATTRIBUTE, identityId.toString());
    } else {
      // signed out or replaced by something who does not record: the browser leaves the list
      currentSessionId(request).ifPresent(sessions::close);
      HttpSession session = request.getSession(false);
      if (session != null) {
        session.removeAttribute(SESSION_ATTRIBUTE);
        session.removeAttribute(PRINCIPAL_INDEX_ATTRIBUTE);
      }
    }
  }

  @Override
  public boolean containsContext(HttpServletRequest request) {
    return currentSession(request).isPresent();
  }

  /**
   * Returns the session recorded for this request, without resolving the principal.
   *
   * @param request the current request
   * @return the stored session, or empty if there is none
   */
  public Optional<WhoSession> currentSession(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session == null) {
      return Optional.empty();
    }
    return session.getAttribute(SESSION_ATTRIBUTE) instanceof WhoSession whoSession
        ? Optional.of(whoSession)
        : Optional.empty();
  }

  /**
   * Returns the id of who's record of this request's session, the one {@code /who/me/sessions}
   * marks as current.
   *
   * @param request the current request
   * @return the session's id, or empty when there is no who session or it has no record
   */
  public Optional<UUID> currentSessionId(HttpServletRequest request) {
    return currentSession(request).map(WhoSession::sessionId);
  }

  /**
   * Whether {@code authentication} is the one this repository loaded from the browser session for
   * {@code request}, rather than one another filter put in its place, such as an API key or a
   * bearer token beside a session cookie.
   */
  private static boolean isLoaded(HttpServletRequest request, Authentication authentication) {
    return authentication != null && authentication == request.getAttribute(LOADED_ATTRIBUTE);
  }

  /**
   * Whether the request presents a stateless method's credential, such as an API key or a bearer
   * token, whether or not it signed the request in.
   */
  private boolean presentsStatelessCredential(HttpServletRequest request) {
    return statelessCredentials.matches(request);
  }

  /**
   * Classifies this request's caller by what signed the request in, never by which headers it
   * carries:
   *
   * <ul>
   *   <li>{@link WhoCaller.SignedOut}: no who sign-in;
   *   <li>{@link WhoCaller.Session}: the browser session loaded the authentication and the request
   *       presents no stateless credential (one the constructor's matcher matches);
   *   <li>{@link WhoCaller.SessionWithForeignCredential}: the session loaded it, and the request
   *       also presents an API key or bearer token, ignored because the session signed it in;
   *   <li>{@link WhoCaller.Stateless}: a bearer token or an API key signed it in, and no session
   *       loaded it;
   *   <li>{@link WhoCaller.Other}: any other who sign-in.
   * </ul>
   *
   * @param request the current request
   * @param authentication the request's authentication, or {@code null}
   * @return the caller
   */
  public WhoCaller caller(HttpServletRequest request, Authentication authentication) {
    if (!(authentication instanceof WhoAuthenticationToken token)) {
      return new WhoCaller.SignedOut();
    }
    if (isLoaded(request, token)) {
      if (presentsStatelessCredential(request)) {
        return new WhoCaller.SessionWithForeignCredential(token);
      }
      UUID identityId = token.getPrincipal().identity().id();
      return currentSession(request)
          .filter(session -> session.identityId().equals(identityId))
          .<WhoCaller>map(session -> new WhoCaller.Session(token, session))
          .orElseGet(() -> new WhoCaller.Other(token));
    }
    return token
        .signInMethod()
        .filter(method -> method == SignInMethod.JWT || method == SignInMethod.API_KEY)
        .<WhoCaller>map(method -> new WhoCaller.Stateless(token, method))
        .orElseGet(() -> new WhoCaller.Other(token));
  }

  private SecurityContext load(HttpServletRequest request) {
    SecurityContext context = new SecurityContextImpl();
    Optional<WhoSession> stored = currentSession(request);
    if (stored.isEmpty()) {
      return context;
    }
    WhoSession whoSession = stored.get();
    // ended here or elsewhere, idle past the timeout, or saved before who recorded sessions
    Optional<SessionRecord> row =
        Optional.ofNullable(whoSession.sessionId()).flatMap(sessions::findLive);
    if (row.isEmpty()) {
      invalidate(request);
      return context;
    }
    UUID credentialId = whoSession.credentialId();
    Resolution resolution = whoService.resolveDetailed(() -> credentialId);
    if (resolution instanceof Resolution.Resolved(WhoPrincipal principal)
        && principal.identity().id().equals(whoSession.identityId())) {
      // not a sign-in, so no method; the factor still says when the person signed in
      WhoAuthenticationToken token =
          new WhoAuthenticationToken(principal, credentialId, null, signInFactor(whoSession));
      context.setAuthentication(token);
      request.setAttribute(LOADED_ATTRIBUTE, token);
      sessions.seen(row.get());
    } else {
      // the identity or credential ended it: close the row too, so the list stops showing it
      sessions.close(row.get().id());
      if (resolution != null) {
        publishEnded(whoSession, resolution);
      }
      invalidate(request);
    }
    return context;
  }

  private static FactorGrantedAuthority signInFactor(WhoSession whoSession) {
    return whoSession.signInMethod() == null
        ? null
        : WhoSignInFactors.of(whoSession.signInMethod(), whoSession.signedInAt()).orElse(null);
  }

  private void publishEnded(WhoSession whoSession, Resolution resolution) {
    UUID credentialId = whoSession.credentialId();
    eventPublisher.publish(
        new SessionEnded(
            clock.instant(), whoSession.identityId(), credentialId, endReason(resolution)));
  }

  /** A credential that still resolves, to someone else or to no one active, ended the identity. */
  private static SessionEndReason endReason(Resolution resolution) {
    return switch (resolution) {
      case Resolution.Resolved _, Resolution.Inactive _ -> SessionEndReason.IDENTITY_INACTIVE;
      case Resolution.NotLinked _, Resolution.NoIdentity _ -> SessionEndReason.CREDENTIAL_REVOKED;
    };
  }

  /**
   * Publishes {@link SignedOut} for a logout of a who session; wired in as a logout handler by
   * {@link PasswordlessConfigurer}. Anything else, an anonymous request or another kind of
   * authentication, is ignored.
   */
  void onLogout(HttpServletRequest request, Authentication authentication) {
    currentSessionId(request).ifPresent(sessions::close);
    if (authentication instanceof WhoAuthenticationToken token
        && token.credentialId().isPresent()
        && currentSession(request).isPresent()) {
      eventPublisher.publish(
          new SignedOut(
              clock.instant(), token.getPrincipal().identity().id(), token.credentialId().get()));
    }
  }

  static void invalidate(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session != null) {
      try {
        session.invalidate();
      } catch (IllegalStateException _) {
        // a concurrent request ended it first; carry on unauthenticated
      }
    }
  }

  private static final class LazyContext implements DeferredSecurityContext {

    private final Supplier<SecurityContext> supplier;
    private SecurityContext context;

    private LazyContext(Supplier<SecurityContext> supplier) {
      this.supplier = supplier;
    }

    @Override
    public SecurityContext get() {
      if (context == null) {
        context = supplier.get();
      }
      return context;
    }

    @Override
    public boolean isGenerated() {
      return get().getAuthentication() == null;
    }
  }
}
