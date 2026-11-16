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
package org.jwcarman.who.spring.security.web;

/**
 * A failure who hands to the application, named by a stable code. {@link WhoExceptionHandoff} sends
 * the code in the {@value WhoExceptionHandoff#HEADER} response header, beside whatever body the
 * application writes, so a client can tell failures apart without knowing the body's shape.
 *
 * <p>The codes are a public contract: kebab-case, and unchanged across releases.
 */
public interface WhoErrorCode {

  /**
   * Returns the code sent in the {@value WhoExceptionHandoff#HEADER} header.
   *
   * @return the code, such as {@code reauthentication-required}
   */
  String errorCode();
}
