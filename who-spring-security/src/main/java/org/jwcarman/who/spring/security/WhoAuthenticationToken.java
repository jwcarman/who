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

import java.io.Serial;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SignInMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Spring Security authentication token backed by a resolved {@link WhoPrincipal}.
 *
 * <p>Marked authenticated on creation. Credentials are not retained after authentication.
 * Authorities are derived from the principal's permissions automatically.
 */
public final class WhoAuthenticationToken extends AbstractAuthenticationToken {

  @Serial private static final long serialVersionUID = 1L;

  /** The resolved principal for this authentication. */
  private final WhoPrincipal principal;

  /** The credential used to authenticate, or {@code null}. */
  private final UUID credentialId;

  /** How the sign-in that created this token was made, or {@code null} if not a sign-in. */
  private final SignInMethod signInMethod;

  /**
   * Creates a new authenticated token. Authorities are derived from {@link
   * WhoPrincipal#permissions()} automatically.
   *
   * @param principal the resolved principal
   */
  public WhoAuthenticationToken(WhoPrincipal principal) {
    this(principal, null);
  }

  /**
   * Creates a new authenticated token that remembers the credential used to authenticate.
   *
   * @param principal the resolved principal
   * @param credentialId the id of the credential used, or {@code null} if unknown
   */
  public WhoAuthenticationToken(WhoPrincipal principal, UUID credentialId) {
    this(principal, credentialId, null);
  }

  /**
   * Creates a new authenticated token for a sign-in, so that who's success handler can report how
   * the sign-in was made.
   *
   * @param principal the resolved principal
   * @param credentialId the id of the credential used, or {@code null} if unknown
   * @param signInMethod how the sign-in was made, or {@code null} if this is not a sign-in
   */
  public WhoAuthenticationToken(
      WhoPrincipal principal, UUID credentialId, SignInMethod signInMethod) {
    super(extractAuthorities(principal));
    this.principal = principal;
    this.credentialId = credentialId;
    this.signInMethod = signInMethod;
    setAuthenticated(true);
  }

  /**
   * Returns the id of the credential used to authenticate, if known.
   *
   * @return the credential id, or empty
   */
  public Optional<UUID> credentialId() {
    return Optional.ofNullable(credentialId);
  }

  /**
   * Returns how the sign-in that created this token was made, if it was a sign-in.
   *
   * @return the sign-in method, or empty
   */
  public Optional<SignInMethod> signInMethod() {
    return Optional.ofNullable(signInMethod);
  }

  private static @NonNull List<SimpleGrantedAuthority> extractAuthorities(WhoPrincipal principal) {
    return requireNonNull(principal, "principal must not be null").permissions().stream()
        .map(SimpleGrantedAuthority::new)
        .toList();
  }

  /** Returns the identity id, which stays the same however the permissions change. */
  @Override
  public String getName() {
    return principal.identity().id().toString();
  }

  @Override
  public WhoPrincipal getPrincipal() {
    return principal;
  }

  /** Returns {@code null} — credentials are not retained after authentication. */
  @Override
  public Object getCredentials() {
    return null;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof WhoAuthenticationToken that)) return false;
    if (!super.equals(o)) return false;
    // super.equals already compares the principal
    return Objects.equals(credentialId, that.credentialId) && signInMethod == that.signInMethod;
  }

  @Override
  public int hashCode() {
    return Objects.hash(super.hashCode(), credentialId, signInMethod);
  }
}
