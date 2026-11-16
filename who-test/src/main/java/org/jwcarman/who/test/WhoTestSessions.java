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

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.UUID;

import org.jwcarman.who.core.domain.SessionRecord;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.service.WhoSessions;
import org.jwcarman.who.spring.security.WhoSession;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.springframework.mock.web.MockHttpSession;

/**
 * Signed-in HTTP sessions for tests.
 *
 * <p>who keeps only a {@link WhoSession} (ids) in the HTTP session, checks on every request that
 * who's record of the session is live, and re-resolves the principal from its {@code WhoService},
 * so the identity and credential must exist, and be usable, in the application under test. These
 * methods record the session through the application's {@link WhoSessions}, as a real sign-in does.
 */
public final class WhoTestSessions {

  private WhoTestSessions() {}

  /**
   * Creates a session signed in now, by the application's {@code Clock}, holding a {@link
   * WhoSession} and the principal index as who stores them after a successful sign-in.
   *
   * <p>The session is a {@code MockHttpSession}, which MockMvc gives to the request. With Spring
   * Session in the filter chain, its request wrapper ignores that session, so every request would
   * be signed out; use {@link WhoMockMvcBrowser#signedInAs(UUID, UUID)} there, which stores the
   * session in the application's {@code SessionRepository}.
   *
   * @param sessions the application's {@code WhoSessions} bean
   * @param identityId the signed-in identity
   * @param credentialId the credential the identity signed in with
   * @return a new session, to pass to {@code MockHttpServletRequestBuilder.session(...)}
   */
  public static MockHttpSession signedIn(WhoSessions sessions, UUID identityId, UUID credentialId) {
    return session(record(sessions, identityId, credentialId, null));
  }

  /**
   * Creates a session signed in at the given instant, as {@link #signedIn(WhoSessions, UUID, UUID)}
   * does. Use it to sign in a while ago, for instance to test a recent sign-in requirement; the
   * session counts as used now, so it is not idle.
   *
   * @param sessions the application's {@code WhoSessions} bean
   * @param identityId the signed-in identity
   * @param credentialId the credential the identity signed in with
   * @param signedInAt when the identity signed in
   * @return a new session, to pass to {@code MockHttpServletRequestBuilder.session(...)}
   */
  public static MockHttpSession signedIn(
      WhoSessions sessions, UUID identityId, UUID credentialId, Instant signedInAt) {
    return session(
        record(
            sessions,
            identityId,
            credentialId,
            requireNonNull(signedInAt, "signedInAt must not be null")));
  }

  /** Records a sign-in by email code and returns what the HTTP session holds for it. */
  static WhoSession record(
      WhoSessions sessions, UUID identityId, UUID credentialId, Instant signedInAt) {
    requireNonNull(sessions, "sessions must not be null");
    SessionRecord row =
        signedInAt == null
            ? sessions.start(identityId, credentialId, SignInMethod.EMAIL_CODE, null)
            : sessions.start(identityId, credentialId, SignInMethod.EMAIL_CODE, null, signedInAt);
    return new WhoSession(
        identityId, credentialId, row.signedInAt(), SignInMethod.EMAIL_CODE, row.id());
  }

  static MockHttpSession session(WhoSession whoSession) {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute(WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE, whoSession);
    session.setAttribute(
        WhoSessionSecurityContextRepository.PRINCIPAL_INDEX_ATTRIBUTE,
        whoSession.identityId().toString());
    return session;
  }
}
