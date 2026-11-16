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

/**
 * Decides whether a request to one of who's public, costly endpoints comes from a person rather
 * than a script: a bot check such as Cloudflare Turnstile. who checks {@code POST /who/email/send}
 * and {@code POST /who/email/sign-in} when the application has a bean of this type, and checks
 * nothing without one. A refused request never reaches the endpoint: no email is sent and no code
 * is tried.
 *
 * <p>A check that throws refuses the request. An implementation decides itself what to do when its
 * vendor cannot be reached.
 */
@FunctionalInterface
public interface WhoHumanCheck {

  /** The request header carrying the token the page got from the check's widget. */
  String HEADER = "Who-Human-Check";

  /** Lets everyone through; for tests that turn the check on without a vendor. */
  WhoHumanCheck ALLOW_ALL = request -> true;

  /**
   * Decides a request.
   *
   * @param request the token the page sent, the client's address and the endpoint being called
   * @return true to let the request through
   */
  boolean passes(HumanCheckRequest request);

  /**
   * Returns this check's name, for the line who logs at startup.
   *
   * @return the name; by default the class's simple name, or for an anonymous class or a lambda a
   *     name built from its enclosing class
   */
  default String name() {
    return LoginMethodNames.of(getClass());
  }
}
