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
package org.jwcarman.who.test;

import java.time.Instant;
import java.util.UUID;

import org.jwcarman.who.spring.security.WhoSession;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.springframework.mock.web.MockHttpSession;

/**
 * Signed-in HTTP sessions for tests.
 *
 * <p>who keeps only a {@link WhoSession} (ids) in the HTTP session and re-resolves the principal
 * from its {@code WhoService} on every request, so the identity and credential must exist, and be
 * usable, in the application under test.
 */
public final class WhoTestSessions {

  private WhoTestSessions() {}

  /**
   * Creates a session holding a {@link WhoSession} signed in now, as who stores it after a
   * successful sign-in.
   *
   * @param identityId the signed-in identity
   * @param credentialId the credential the identity signed in with
   * @return a new session, to pass to {@code MockHttpServletRequestBuilder.session(...)}
   */
  public static MockHttpSession signedIn(UUID identityId, UUID credentialId) {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute(
        WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE,
        new WhoSession(identityId, credentialId, Instant.now()));
    return session;
  }
}
