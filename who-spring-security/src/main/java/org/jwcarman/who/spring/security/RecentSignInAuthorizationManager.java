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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.spring.security.web.InsufficientUserAuthenticationException;
import org.jwcarman.who.spring.security.web.NotSignedInException;
import org.jwcarman.who.spring.security.web.ReauthenticationRequiredException;
import org.jwcarman.who.spring.security.web.RecentSignInUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AllRequiredFactorsAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.FactorAuthorizationDecision;
import org.springframework.security.authorization.RequiredFactor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.FactorGrantedAuthority;

/**
 * Grants a request whose caller signed in within a window, by the factor who stamped on their token
 * at sign-in. Usable as a URL rule, {@code .access(recentSignIn)}, and behind
 * {@code @RequiresRecentSignIn}.
 *
 * <ul>
 *   <li>A browser session signed in too long ago: {@link ReauthenticationRequiredException}.
 *   <li>A bearer token whose {@code auth_time} is too long ago: {@link
 *       InsufficientUserAuthenticationException}, with its RFC 9470 challenge.
 *   <li>A bearer token without {@code auth_time}, or an API key: {@link
 *       RecentSignInUnavailableException}.
 *   <li>Nobody signed in: {@link NotSignedInException}.
 * </ul>
 *
 * <p>The age check is Spring Security's {@link AllRequiredFactorsAuthorizationManager}, one per
 * factor who stamps, each requiring that factor within the window.
 */
public final class RecentSignInAuthorizationManager implements AuthorizationManager<Object> {

  /** Stands in for a missing secured object, which the factor check never reads. */
  private static final Object NO_OBJECT = new Object();

  private static final Logger log = LoggerFactory.getLogger(RecentSignInAuthorizationManager.class);

  private final Duration window;
  private final Clock clock;
  private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();
  private final Map<String, AllRequiredFactorsAuthorizationManager<Object>> byFactor;

  /**
   * Creates the manager.
   *
   * @param window how long a sign-in stays recent; must be positive
   * @param clock the current time
   */
  public RecentSignInAuthorizationManager(Duration window, Clock clock) {
    requireNonNull(window, "window must not be null");
    if (window.isNegative() || window.isZero()) {
      throw new IllegalArgumentException("window must be positive");
    }
    this.window = window;
    this.clock = requireNonNull(clock, "clock must not be null");
    this.byFactor =
        WhoSignInFactors.AUTHORITIES.stream()
            .collect(Collectors.toUnmodifiableMap(Function.identity(), this::requiring));
  }

  private AllRequiredFactorsAuthorizationManager<Object> requiring(String authority) {
    AllRequiredFactorsAuthorizationManager<Object> manager =
        AllRequiredFactorsAuthorizationManager.builder()
            .requireFactor(RequiredFactor.withAuthority(authority).validDuration(window).build())
            .build();
    manager.setClock(clock);
    return manager;
  }

  /**
   * Returns a manager like this one with a different window.
   *
   * @param window how long a sign-in stays recent; must be positive
   * @return the manager
   */
  public RecentSignInAuthorizationManager withWindow(Duration window) {
    return window.equals(this.window) ? this : new RecentSignInAuthorizationManager(window, clock);
  }

  /**
   * Returns how long a sign-in stays recent.
   *
   * @return the window
   */
  public Duration window() {
    return window;
  }

  @Override
  public RecentSignInDecision authorize(
      Supplier<? extends @Nullable Authentication> authentication, @Nullable Object object) {
    Authentication caller = authentication.get();
    if (caller == null || !caller.isAuthenticated() || trustResolver.isAnonymous(caller)) {
      return RecentSignInDecision.refused(new NotSignedInException(), null);
    }
    if (!(caller instanceof WhoAuthenticationToken token)) {
      return refused(RecentSignInUnavailableException.unknownSignIn(), "not signed in through who");
    }
    Optional<FactorGrantedAuthority> factor = token.signInFactor();
    if (factor.isEmpty()) {
      return noFactor(token);
    }
    if (isRecent(factor.get(), object)) {
      return RecentSignInDecision.granted();
    }
    if (FactorGrantedAuthority.BEARER_AUTHORITY.equals(factor.get().getAuthority())) {
      InsufficientUserAuthenticationException stepUp =
          new InsufficientUserAuthenticationException(window);
      log.debug("Recent sign-in required: the token's auth_time is older than {}", window);
      return RecentSignInDecision.refused(stepUp, stepUp.challenge());
    }
    return refused(new ReauthenticationRequiredException(), "the sign-in is older than " + window);
  }

  /**
   * Asks Spring Security whether the factor is within the window, showing it only the factor, so
   * that no permission named like a factor can count as one.
   */
  private boolean isRecent(FactorGrantedAuthority factor, @Nullable Object object) {
    AllRequiredFactorsAuthorizationManager<Object> manager = byFactor.get(factor.getAuthority());
    if (manager == null) {
      return false;
    }
    Authentication onlyTheFactor =
        UsernamePasswordAuthenticationToken.authenticated("factor", null, List.of(factor));
    // the factor check never reads the secured object, but Spring's signature takes no null
    FactorAuthorizationDecision decision =
        manager.authorize(() -> onlyTheFactor, Objects.requireNonNullElse(object, NO_OBJECT));
    return decision.isGranted();
  }

  private RecentSignInDecision noFactor(WhoAuthenticationToken token) {
    SignInMethod method = token.signInMethod().orElse(null);
    if (method == SignInMethod.API_KEY) {
      return refused(RecentSignInUnavailableException.apiKey(), "an API key");
    }
    if (method == SignInMethod.JWT) {
      return refused(RecentSignInUnavailableException.noSignInTime(), "a token without auth_time");
    }
    return refused(new ReauthenticationRequiredException(), "no sign-in time");
  }

  private static RecentSignInDecision refused(RuntimeException refusal, String why) {
    log.debug("Recent sign-in required: {}", why);
    return RecentSignInDecision.refused(refusal, null);
  }
}
