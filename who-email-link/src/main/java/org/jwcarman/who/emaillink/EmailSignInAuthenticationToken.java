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
package org.jwcarman.who.emaillink;

import static java.util.Objects.requireNonNull;

import java.util.List;

import org.jwcarman.who.emaillink.EmailSignInProof.EmailCode;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * An unauthenticated email sign-in request carrying its {@link EmailSignInProof}, as read by {@link
 * JsonOneTimeTokenAuthenticationConverter} and authenticated by {@link
 * EmailLinkAuthenticationProvider}.
 */
public final class EmailSignInAuthenticationToken extends AbstractAuthenticationToken {

  private final transient EmailSignInProof proof;

  /**
   * Creates an unauthenticated sign-in request.
   *
   * @param proof what the request presents
   */
  public EmailSignInAuthenticationToken(EmailSignInProof proof) {
    super(List.of());
    this.proof = requireNonNull(proof, "proof must not be null");
  }

  /**
   * Returns what the request presents.
   *
   * @return the proof
   */
  public EmailSignInProof proof() {
    return proof;
  }

  @Override
  public Object getCredentials() {
    return proof;
  }

  @Override
  public Object getPrincipal() {
    return proof instanceof EmailCode code ? code.address() : null;
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof EmailSignInAuthenticationToken other && proof.equals(other.proof);
  }

  @Override
  public int hashCode() {
    return proof.hashCode();
  }
}
