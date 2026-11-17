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

import java.util.Optional;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * A way to sign in each request by a credential it carries in a header (API keys, bearer JWTs),
 * contributed as a bean by a credential module. {@link WhoSecurity#all()} and {@link
 * WhoSecurity#stateless()} apply every such bean.
 *
 * <p>A request that carries the credential is signed in by it or refused, so it needs no CSRF
 * token: a browser cannot attach the header to a forged cross-site request.
 */
public interface StatelessLoginMethod {

  /**
   * Adds this login method to the chain.
   *
   * @param http the chain being built
   */
  void configure(HttpSecurity http);

  /**
   * Matches the requests that carry this method's credential. Such requests are exempt from CSRF
   * protection, so the matcher must match only when the credential is really there: a blank header
   * must not match. Nor may a request already {@link #alreadySignedIn() signed in}, such as by a
   * session cookie: its credential would not be what signs it in, so the cookie would ride along
   * without a CSRF token.
   *
   * @return the matcher
   */
  RequestMatcher carriesCredential();

  /**
   * Matches the requests that present this method's credential at all, whether or not it signs them
   * in: such a request is never treated as a browser session, even when a session cookie comes with
   * it, so it may not do what only a browser may. By default the same as {@link
   * #carriesCredential()}; a method that ignores its credential on a request already signed in
   * matches here regardless.
   *
   * @return the matcher
   */
  default RequestMatcher presentsCredential() {
    return carriesCredential();
  }

  /**
   * Whether the current request is already signed in, by a session or anything else, before a
   * stateless method looks at its credential: the security context holds an authentication that is
   * not anonymous. A {@link #carriesCredential()} matcher answers {@code false} then.
   *
   * @return whether the request is signed in already
   */
  static boolean alreadySignedIn() {
    Authentication existing = SecurityContextHolder.getContext().getAuthentication();
    return existing != null && !(existing instanceof AnonymousAuthenticationToken);
  }

  /**
   * The authentication scheme a signed-out {@code 401} names in its {@code WWW-Authenticate}
   * header, so a client learns how to sign in (RFC 9110, section 11.6.1), such as {@code Bearer}
   * (RFC 6750). The value is written as the header's value, verbatim, once even when several
   * methods name it. Empty for a method with no standard scheme, which adds no header.
   *
   * @return the scheme, or empty
   */
  default Optional<String> challenge() {
    return Optional.empty();
  }

  /**
   * Returns this method's name, for the line who logs at startup naming the sign-in methods a chain
   * accepts. who's own methods return a short name such as {@code api-key}.
   *
   * @return the name; by default the class's simple name, or for an anonymous class or a lambda a
   *     name built from its enclosing class
   */
  default String name() {
    return LoginMethodNames.of(getClass());
  }
}
