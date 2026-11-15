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
package org.jwcarman.who.oidc;

import java.util.Collection;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * Spring's sign-in result for a callback that answers a link, carrying the {@link LinkIntent} to
 * who's handler. Never stored: the handler clears the security context.
 *
 * <p>It declares no {@code toBuilder()} on purpose. Spring's login filter rebuilds a result through
 * {@code toBuilder()} when the result's class declares one, which would hand the handler a plain
 * {@link OAuth2AuthenticationToken} and drop the intent. If that ever happens anyway, the link
 * fails safe: the callback is a plain sign-in, which never links.
 */
final class LinkingAuthenticationToken extends OAuth2AuthenticationToken {

  private static final long serialVersionUID = 1L;

  private final LinkIntent intent;

  LinkingAuthenticationToken(
      OAuth2User principal,
      Collection<? extends GrantedAuthority> authorities,
      String registrationId,
      LinkIntent intent) {
    super(principal, authorities, registrationId);
    this.intent = intent;
  }

  LinkIntent intent() {
    return intent;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof LinkingAuthenticationToken that
        && super.equals(that)
        && intent.equals(that.intent);
  }

  @Override
  public int hashCode() {
    return 31 * super.hashCode() + intent.hashCode();
  }
}
