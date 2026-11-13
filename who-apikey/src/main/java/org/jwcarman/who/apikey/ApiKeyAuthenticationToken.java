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

import java.io.Serial;
import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * An unauthenticated request to sign in with an API key, holding the raw key read from the request
 * header.
 *
 * <p>The key is the token's {@link #getCredentials() credentials} until they are {@link
 * #eraseCredentials() erased}; {@link ApiKeyAuthenticationProvider} erases them as soon as it has
 * hashed the key, so the token Spring's authentication events and exceptions carry no longer holds
 * it. The token has no principal, and its {@link #toString()} never shows the key.
 */
public final class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

  @Serial private static final long serialVersionUID = 1L;

  /** The raw key, or {@code null} once erased. */
  private String key;

  /**
   * Creates an unauthenticated request for the given key.
   *
   * @param key the raw API key from the request
   */
  public ApiKeyAuthenticationToken(String key) {
    super(List.of());
    this.key = requireNonNull(key, "key must not be null");
    setAuthenticated(false);
  }

  /**
   * Returns the raw key, or {@code null} once the credentials are erased.
   *
   * @return the raw key, or {@code null}
   */
  @Override
  public String getCredentials() {
    return key;
  }

  /**
   * Returns {@code null}: who the key belongs to is not known until it is authenticated.
   *
   * @return {@code null}
   */
  @Override
  public Object getPrincipal() {
    return null;
  }

  /** Forgets the raw key. */
  @Override
  public void eraseCredentials() {
    super.eraseCredentials();
    key = null;
  }
}
