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
package org.jwcarman.who.core.domain;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.UUID;

import org.jwcarman.who.core.event.SignInMethod;

/**
 * One browser sign-in, as who records it so a person can see where they are signed in and end a
 * session from elsewhere. Its {@code id} is who's own, never the servlet session's id, which is the
 * session cookie's value.
 *
 * @param id who's id for the session, the only one ever shown
 * @param identityId the identity that signed in
 * @param credentialId the credential it signed in with
 * @param signInMethod how it signed in
 * @param signedInAt when it signed in
 * @param lastSeenAt when it last made a request, written at most once per interval
 * @param userAgent the browser's {@code User-Agent} at sign-in, or {@code null} when it sent none
 * @param endedAt when it was ended (signed out, or ended from elsewhere), or {@code null} while it
 *     has not been
 */
public record SessionRecord(
    UUID id,
    UUID identityId,
    UUID credentialId,
    SignInMethod signInMethod,
    Instant signedInAt,
    Instant lastSeenAt,
    String userAgent,
    Instant endedAt) {

  /** Validates that every component but {@code userAgent} and {@code endedAt} is present. */
  public SessionRecord {
    requireNonNull(id, "id must not be null");
    requireNonNull(identityId, "identityId must not be null");
    requireNonNull(credentialId, "credentialId must not be null");
    requireNonNull(signInMethod, "signInMethod must not be null");
    requireNonNull(signedInAt, "signedInAt must not be null");
    requireNonNull(lastSeenAt, "lastSeenAt must not be null");
  }
}
