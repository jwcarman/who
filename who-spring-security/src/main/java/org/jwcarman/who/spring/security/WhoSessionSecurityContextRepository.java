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

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.service.WhoService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Keeps only a {@link WhoSession} (ids) in the HTTP session and re-resolves the principal from
 * {@link WhoService} on every request, so a suspended identity, a removed role or a revoked
 * credential takes effect immediately.
 */
public class WhoSessionSecurityContextRepository implements SecurityContextRepository {

  /** Name of the HTTP session attribute holding the {@link WhoSession}. */
  public static final String SESSION_ATTRIBUTE = "org.jwcarman.who.session";

  private static final String LOADED_ATTRIBUTE =
      WhoSessionSecurityContextRepository.class.getName() + ".LOADED";

  private final WhoService whoService;
  private final Clock clock;

  /**
   * Creates the repository.
   *
   * @param whoService used to re-resolve the principal on every request
   * @param clock source of the sign-in time
   */
  public WhoSessionSecurityContextRepository(WhoService whoService, Clock clock) {
    this.whoService = whoService;
    this.clock = clock;
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
  @Deprecated
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
      request
          .getSession(true)
          .setAttribute(
              SESSION_ATTRIBUTE, new WhoSession(identityId, credentialId, clock.instant()));
    } else {
      HttpSession session = request.getSession(false);
      if (session != null) {
        session.removeAttribute(SESSION_ATTRIBUTE);
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

  private SecurityContext load(HttpServletRequest request) {
    SecurityContext context = new SecurityContextImpl();
    Optional<WhoSession> stored = currentSession(request);
    if (stored.isEmpty()) {
      return context;
    }
    WhoSession whoSession = stored.get();
    UUID credentialId = whoSession.credentialId();
    Optional<WhoPrincipal> principal = whoService.resolve(() -> credentialId);
    if (principal.isPresent() && principal.get().identity().id().equals(whoSession.identityId())) {
      WhoAuthenticationToken token = new WhoAuthenticationToken(principal.get(), credentialId);
      context.setAuthentication(token);
      request.setAttribute(LOADED_ATTRIBUTE, token);
    } else {
      invalidate(request);
    }
    return context;
  }

  private static void invalidate(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session != null) {
      try {
        session.invalidate();
      } catch (IllegalStateException alreadyInvalidated) {
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
