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
package org.jwcarman.who.core.email;

/**
 * An email who sends, before it is rendered. Each kind is a record in the module that owns its
 * feature, and its accessors are what that email's templates can use.
 *
 * <p>Send one only through {@link WhoEmails}.
 */
public interface WhoEmail {

  /**
   * The address the email goes to.
   *
   * @return the recipient's address
   */
  String address();

  /**
   * The name of the email's template: stable and kebab-case, such as {@code sign-in}, {@code
   * invitation} or {@code passkey-added}. The renderer finds the template by it, and a sender that
   * uses a provider's hosted templates can key on it.
   *
   * @return the template's name
   */
  String template();
}
