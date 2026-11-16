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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SignInMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
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

  /** The factor stamped with when the person signed in, or {@code null}. */
  private final FactorGrantedAuthority signInFactor;

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
    this(principal, credentialId, signInMethod, null);
  }

  /**
   * Creates a new authenticated token that carries the factor of the person's sign-in among its
   * authorities, beside the permissions.
   *
   * @param principal the resolved principal
   * @param credentialId the id of the credential used, or {@code null} if unknown
   * @param signInMethod how the sign-in was made, or {@code null} if this is not a sign-in
   * @param signInFactor the factor stamped with when the person signed in, or {@code null} if there
   *     is none
   * @see WhoSignInFactors
   */
  public WhoAuthenticationToken(
      WhoPrincipal principal,
      UUID credentialId,
      SignInMethod signInMethod,
      FactorGrantedAuthority signInFactor) {
    super(extractAuthorities(principal, signInFactor));
    this.principal = principal;
    this.credentialId = credentialId;
    this.signInMethod = signInMethod;
    this.signInFactor = signInFactor;
    setAuthenticated(true);
  }

  /**
   * Creates the token for a sign-in made at {@code signedInAt}, with the factor that {@code
   * signInMethod} stands for.
   *
   * @param principal the resolved principal
   * @param credentialId the id of the credential used
   * @param signInMethod how the sign-in was made
   * @param signedInAt when the person signed in
   * @return the token
   */
  public static WhoAuthenticationToken signedIn(
      WhoPrincipal principal, UUID credentialId, SignInMethod signInMethod, Instant signedInAt) {
    return new WhoAuthenticationToken(
        principal,
        credentialId,
        signInMethod,
        WhoSignInFactors.of(signInMethod, signedInAt).orElse(null));
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

  /**
   * Returns the factor stamped with when the person signed in, if there is one: none for an API
   * key, a JWT without {@code auth_time}, or a session saved before who stored how it signed in.
   *
   * @return the sign-in factor, or empty
   */
  public Optional<FactorGrantedAuthority> signInFactor() {
    return Optional.ofNullable(signInFactor);
  }

  private static @NonNull List<GrantedAuthority> extractAuthorities(
      WhoPrincipal principal, FactorGrantedAuthority signInFactor) {
    List<GrantedAuthority> authorities = new ArrayList<>();
    requireNonNull(principal, "principal must not be null").permissions().stream()
        .map(SimpleGrantedAuthority::new)
        .forEach(authorities::add);
    if (signInFactor != null) {
      authorities.add(signInFactor);
    }
    return authorities;
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
    return Objects.equals(credentialId, that.credentialId)
        && signInMethod == that.signInMethod
        && Objects.equals(signInFactor, that.signInFactor);
  }

  @Override
  public int hashCode() {
    return Objects.hash(super.hashCode(), credentialId, signInMethod, signInFactor);
  }
}
