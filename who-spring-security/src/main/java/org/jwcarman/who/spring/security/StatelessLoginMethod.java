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

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * A way to sign in each request by a credential it carries in a header (API keys, bearer JWTs),
 * contributed as a bean by a credential module. {@link WhoSecurity#all()} applies every such bean.
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
   * must not match.
   *
   * @return the matcher
   */
  RequestMatcher carriesCredential();
}
