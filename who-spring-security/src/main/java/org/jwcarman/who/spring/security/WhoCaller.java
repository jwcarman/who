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

import java.time.Duration;
import java.time.Instant;

import org.jwcarman.who.core.event.SignInMethod;

/**
 * Who signed a request in, as {@link WhoSessionSecurityContextRepository#caller} tells it: by what
 * signed the request in, never by which headers it carries. Every who endpoint that treats a
 * browser session, a bearer token and an API key differently switches on it.
 */
public sealed interface WhoCaller {

  /** No who sign-in: the request is anonymous, or signed in by something that is not who's. */
  record SignedOut() implements WhoCaller {}

  /**
   * A browser session signed the request in, and the request presents no API key or bearer token
   * beside the session cookie.
   *
   * @param token the authentication the session loaded
   * @param session the session
   */
  record Session(WhoAuthenticationToken token, WhoSession session) implements WhoCaller {

    /**
     * Creates the caller.
     *
     * @param token the authentication the session loaded
     * @param session the session
     */
    public Session {
      requireNonNull(token, "token must not be null");
      requireNonNull(session, "session must not be null");
    }

    /**
     * Whether the person signed this session in within {@code window} of {@code now}.
     *
     * @param window how long a sign-in stays recent
     * @param now the current time
     * @return whether the sign-in is recent
     * @see WhoSignInFactors#isRecent(Instant, Duration, Instant)
     */
    public boolean isRecent(Duration window, Instant now) {
      return WhoSignInFactors.isRecent(session.signedInAt(), window, now);
    }
  }

  /**
   * A browser session signed the request in, but the request also presents an API key or a bearer
   * token, which was ignored because the session had already signed it in. It is neither a browser
   * nor a stateless caller: endpoints that tell the two apart refuse it.
   *
   * @param token the authentication the session loaded
   */
  record SessionWithForeignCredential(WhoAuthenticationToken token) implements WhoCaller {

    /**
     * Creates the caller.
     *
     * @param token the authentication the session loaded
     */
    public SessionWithForeignCredential {
      requireNonNull(token, "token must not be null");
    }
  }

  /**
   * A bearer token or an API key signed the request in; no browser session did.
   *
   * @param token the authentication the bearer token or API key produced
   * @param method {@link SignInMethod#JWT} or {@link SignInMethod#API_KEY}
   */
  record Stateless(WhoAuthenticationToken token, SignInMethod method) implements WhoCaller {

    /**
     * Creates the caller.
     *
     * @param token the authentication the bearer token or API key produced
     * @param method {@link SignInMethod#JWT} or {@link SignInMethod#API_KEY}
     */
    public Stateless {
      requireNonNull(token, "token must not be null");
      requireNonNull(method, "method must not be null");
    }
  }

  /**
   * A who sign-in that no browser session loaded and that is neither a bearer token nor an API key,
   * such as one placed in the security context by the application or a test. It is not a browser,
   * and nothing exempts it from a recent sign-in.
   *
   * @param token the authentication
   */
  record Other(WhoAuthenticationToken token) implements WhoCaller {

    /**
     * Creates the caller.
     *
     * @param token the authentication
     */
    public Other {
      requireNonNull(token, "token must not be null");
    }
  }
}
