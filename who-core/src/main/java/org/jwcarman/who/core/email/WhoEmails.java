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

import static java.util.Objects.requireNonNull;

import java.util.Locale;
import java.util.function.Supplier;

import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Renders and sends who's emails. Every email who sends goes through here, and nowhere else.
 *
 * <p>No email is sent while a transaction is open: {@link #send} refuses before rendering, so an
 * email never promises something that might still roll back, and no connection is held while it
 * goes out.
 */
public final class WhoEmails {

  private final WhoEmailRenderer renderer;
  private final WhoEmailSender sender;
  private final Supplier<Locale> locale;

  /**
   * Creates the seam.
   *
   * @param renderer renders each email
   * @param sender delivers each rendered email
   * @param locale the locale to render in, asked once per email
   */
  public WhoEmails(WhoEmailRenderer renderer, WhoEmailSender sender, Supplier<Locale> locale) {
    this.renderer = requireNonNull(renderer, "renderer must not be null");
    this.sender = requireNonNull(sender, "sender must not be null");
    this.locale = requireNonNull(locale, "locale must not be null");
  }

  /**
   * Renders the email in the current locale and sends it.
   *
   * @param email the email to send
   * @throws IllegalStateException if a transaction is open; nothing is rendered or sent
   * @throws RuntimeException whatever the renderer or the sender threw, as it was thrown
   */
  public void send(WhoEmail email) {
    requireNonNull(email, "email must not be null");
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "The " + email.template() + " email must be sent outside a transaction");
    }
    sender.send(renderer.render(email, locale.get()));
  }
}
