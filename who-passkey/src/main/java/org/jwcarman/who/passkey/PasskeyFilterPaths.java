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

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.Filter;

import org.jwcarman.who.spring.security.WhoPaths;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.webauthn.authentication.PublicKeyCredentialRequestOptionsFilter;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthenticationFilter;
import org.springframework.security.web.webauthn.registration.PublicKeyCredentialCreationOptionsFilter;
import org.springframework.security.web.webauthn.registration.WebAuthnRegistrationFilter;

/**
 * Puts Spring Security's passkey endpoints under who's root, in the chains who configures:
 *
 * <ul>
 *   <li>{@code POST <root>/passkey/register/options} and {@code POST <root>/passkey/register}
 *       register a passkey, and {@code DELETE <root>/passkey/register/{id}} removes one;
 *   <li>{@code POST <root>/passkey/sign-in/options} and {@code POST <root>/passkey/sign-in} sign
 *       in.
 * </ul>
 *
 * <p>Spring's {@code webAuthn()} post-processes only its sign-in filter, so who {@linkplain #mark
 * marks} that one while the chain is configured; once the chain is built, its three other passkey
 * filters, found beside the marked one, are re-pointed through their own setters. Spring's filters
 * are used as they are, only at other URLs. A chain with no marked filter, such as the
 * application's own {@code webAuthn()} chain, keeps Spring's URLs.
 */
public final class PasskeyFilterPaths implements BeanPostProcessor {

  /** Signs in, below the root. */
  static final String SIGN_IN = "/passkey/sign-in";

  /** The sign-in options, below the root. */
  static final String SIGN_IN_OPTIONS = "/passkey/sign-in/options";

  /** Registers a passkey, below the root. */
  static final String REGISTER = "/passkey/register";

  /** The registration options, below the root. */
  static final String REGISTER_OPTIONS = "/passkey/register/options";

  private final Map<WebAuthnAuthenticationFilter, WhoPaths> marked =
      Collections.synchronizedMap(new IdentityHashMap<>());

  /**
   * Points the sign-in filter at who's root, and marks it so its chain's other passkey filters
   * follow once the chain is built.
   *
   * @param filter the sign-in filter of a chain who configures
   * @param paths where who's endpoints live
   */
  public void mark(WebAuthnAuthenticationFilter filter, WhoPaths paths) {
    requireNonNull(filter, "filter must not be null");
    requireNonNull(paths, "paths must not be null");
    filter.setRequiresAuthenticationRequestMatcher(
        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, paths.of(SIGN_IN)));
    marked.put(filter, paths);
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (bean instanceof DefaultSecurityFilterChain chain) {
      List<Filter> filters = chain.getFilters();
      for (Filter filter : filters) {
        if (filter instanceof WebAuthnAuthenticationFilter signIn) {
          WhoPaths paths = marked.remove(signIn);
          if (paths != null) {
            repoint(filters, paths);
          }
        }
      }
    }
    return bean;
  }

  private static void repoint(List<Filter> filters, WhoPaths paths) {
    PathPatternRequestMatcher.Builder matchers = PathPatternRequestMatcher.withDefaults();
    find(filters, PublicKeyCredentialRequestOptionsFilter.class)
        .setRequestMatcher(matchers.matcher(HttpMethod.POST, paths.of(SIGN_IN_OPTIONS)));
    find(filters, PublicKeyCredentialCreationOptionsFilter.class)
        .setRequestMatcher(matchers.matcher(HttpMethod.POST, paths.of(REGISTER_OPTIONS)));
    WebAuthnRegistrationFilter registration = find(filters, WebAuthnRegistrationFilter.class);
    registration.setRegisterCredentialMatcher(
        matchers.matcher(HttpMethod.POST, paths.of(REGISTER)));
    // Spring reads the passkey's id from the {id} variable
    registration.setRemoveCredentialMatcher(
        matchers.matcher(HttpMethod.DELETE, paths.of(REGISTER + "/{id}")));
  }

  private static <F extends Filter> F find(List<Filter> filters, Class<F> type) {
    return filters.stream()
        .filter(type::isInstance)
        .map(type::cast)
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "A chain with who's passkey sign-in has no "
                        + type.getSimpleName()
                        + "; Spring Security's webAuthn() no longer adds the filters who"
                        + " re-points"));
  }
}
