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

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;

import org.jwcarman.who.core.service.WhoSessions;

/**
 * Ends who's record of a session when the servlet container destroys it, by invalidation or by
 * timeout, so the list of sessions stays current. A store that destroys sessions without telling
 * the container, such as Spring Session JDBC, is covered by the idle timeout instead.
 */
public class WhoSessionEndListener implements HttpSessionListener {

  private final WhoSessions sessions;

  /**
   * Creates the listener.
   *
   * @param sessions who's record of sessions
   */
  public WhoSessionEndListener(WhoSessions sessions) {
    this.sessions = requireNonNull(sessions, "sessions must not be null");
  }

  @Override
  public void sessionDestroyed(HttpSessionEvent event) {
    if (event.getSession().getAttribute(WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE)
            instanceof WhoSession whoSession
        && whoSession.sessionId() != null) {
      sessions.close(whoSession.sessionId());
    }
  }
}
