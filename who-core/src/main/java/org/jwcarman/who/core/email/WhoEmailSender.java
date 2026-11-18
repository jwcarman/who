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
 * Delivers a rendered email: SMTP, or a provider such as SES. It only delivers; who has already
 * rendered the content. Register one as a bean and who's own senders back off.
 */
@FunctionalInterface
public interface WhoEmailSender {

  /**
   * Delivers the email.
   *
   * @param email the rendered email
   * @throws RuntimeException if it could not be delivered, so who can undo what the email promised
   */
  void send(OutgoingEmail email);
}
