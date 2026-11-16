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

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import org.jwcarman.who.core.event.SignInMethod;

/**
 * What a browser session remembers about a signed-in identity: ids only. The principal is
 * re-resolved from the database on every request.
 *
 * @param identityId the identity that signed in
 * @param credentialId the credential used to sign in
 * @param signedInAt when the session was first established for that credential
 * @param signInMethod how the person signed in, or {@code null} for a session saved before who
 *     stored it
 * @param sessionId the id of who's record of this session, or {@code null} for a session saved
 *     before who recorded sessions, which is signed out on its next request
 */
public record WhoSession(
    UUID identityId,
    UUID credentialId,
    Instant signedInAt,
    SignInMethod signInMethod,
    UUID sessionId)
    implements Serializable {

  /**
   * A session with no record behind it, as one saved before who recorded sessions.
   *
   * @param identityId the identity that signed in
   * @param credentialId the credential used to sign in
   * @param signedInAt when the session was first established for that credential
   * @param signInMethod how the person signed in, or {@code null}
   */
  public WhoSession(
      UUID identityId, UUID credentialId, Instant signedInAt, SignInMethod signInMethod) {
    this(identityId, credentialId, signedInAt, signInMethod, null);
  }

  /**
   * A session that does not say how the person signed in. It carries no sign-in factor, so an
   * action that needs a recent sign-in asks the person to sign in again.
   *
   * @param identityId the identity that signed in
   * @param credentialId the credential used to sign in
   * @param signedInAt when the session was first established for that credential
   */
  public WhoSession(UUID identityId, UUID credentialId, Instant signedInAt) {
    this(identityId, credentialId, signedInAt, null, null);
  }
}
