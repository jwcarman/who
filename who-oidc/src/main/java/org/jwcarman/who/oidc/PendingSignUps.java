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

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * Keeps the {@link PendingSignUp} in the session. One that has expired, or was created for another
 * identity than the one signed in now (signed out counts as one), is dropped when read: an account
 * planted in a shared browser cannot be linked or signed up by the next person.
 */
public final class PendingSignUps {

  /** The session attribute that holds the pending sign-up. */
  public static final String ATTRIBUTE = "org.jwcarman.who.oidc.pending";

  /** Creates the store. It keeps nothing itself: the pending sign-up lives in the session. */
  public PendingSignUps() {
    // stateless
  }

  /**
   * Stores a pending sign-up, replacing any other.
   *
   * @param request the current request
   * @param pending the pending sign-up
   */
  public void save(HttpServletRequest request, PendingSignUp pending) {
    request.getSession(true).setAttribute(ATTRIBUTE, pending);
  }

  /**
   * The pending sign-up, when there is one this browser may still use.
   *
   * @param request the current request
   * @param signedIn the identity signed in now, or null
   * @param now the current time
   * @return the pending sign-up, or empty
   */
  public Optional<PendingSignUp> current(HttpServletRequest request, UUID signedIn, Instant now) {
    HttpSession session = request.getSession(false);
    if (session == null || !(session.getAttribute(ATTRIBUTE) instanceof PendingSignUp pending)) {
      return Optional.empty();
    }
    if (!now.isBefore(pending.expiresAt()) || !Objects.equals(pending.createdFor(), signedIn)) {
      session.removeAttribute(ATTRIBUTE);
      return Optional.empty();
    }
    return Optional.of(pending);
  }

  /**
   * Removes the pending sign-up.
   *
   * @param request the current request
   */
  public void consume(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session != null) {
      session.removeAttribute(ATTRIBUTE);
    }
  }
}
