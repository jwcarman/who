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
package org.jwcarman.who.emaillink;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.time.Instant;

import org.jwcarman.who.core.crypto.MessageDigests;
import org.jwcarman.who.core.service.WhoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Does the real work behind {@code POST /ott/generate}, off the request thread: looks up the
 * address, stores the token's hash (the upsert that is also the resend limit), and sends the link.
 *
 * <p>Nothing here is visible to the caller, who has already been answered {@code 202}. {@link
 * #issue} never throws: every failure is logged at WARN with only the credential id (when known)
 * and the exception class, never the exception message, the address or the token value.
 */
public class EmailLinkIssuer {

  private static final Logger log = LoggerFactory.getLogger(EmailLinkIssuer.class);

  private final EmailCredentialService credentialService;
  private final WhoService whoService;
  private final EmailLinkTokenRepository repository;
  private final EmailLinkSender sender;
  private final EmailLinkSettings settings;
  private final Clock clock;

  /**
   * Creates a new issuer.
   *
   * @param credentialService finds email credentials
   * @param whoService resolves credentials to active identities
   * @param repository stores token hashes
   * @param sender sends the link
   * @param settings the link settings
   * @param clock the clock
   */
  public EmailLinkIssuer(
      EmailCredentialService credentialService,
      WhoService whoService,
      EmailLinkTokenRepository repository,
      EmailLinkSender sender,
      EmailLinkSettings settings,
      Clock clock) {
    this.credentialService =
        requireNonNull(credentialService, "credentialService must not be null");
    this.whoService = requireNonNull(whoService, "whoService must not be null");
    this.repository = requireNonNull(repository, "repository must not be null");
    this.sender = requireNonNull(sender, "sender must not be null");
    this.settings = requireNonNull(settings, "settings must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
  }

  /**
   * Issues and sends a link if the address belongs to an active identity and no recent link is
   * outstanding; otherwise does nothing.
   *
   * @param address the requested address, as typed; the link goes to the registered (normalized)
   *     address
   * @param tokenValue the generated token value
   */
  public void issue(String address, String tokenValue) {
    if (!looksLikeAnAddress(address)) {
      return;
    }
    try {
      credentialService
          .findByAddress(address)
          .ifPresentOrElse(
              credential -> issueTo(credential, tokenValue),
              () -> log.debug("Sign-in link requested for an unknown address"));
    } catch (RuntimeException e) {
      log.warn("Failed to look up a sign-in link address: {}", e.getClass().getName());
    }
  }

  private void issueTo(EmailCredential credential, String tokenValue) {
    String tokenHash = MessageDigests.sha256Hex(tokenValue);
    try {
      if (whoService.resolve(credential).isEmpty()) {
        return;
      }
      Instant now = clock.instant();
      boolean issued =
          repository.issue(
              credential.id(),
              tokenHash,
              now,
              now.plus(settings.tokenTtl()),
              settings.resendInterval());
      if (!issued) {
        log.debug(
            "Sign-in link for credential {} suppressed by the resend interval", credential.id());
        return;
      }
    } catch (RuntimeException e) {
      log.warn(
          "Failed to issue sign-in link for credential {}: {}",
          credential.id(),
          e.getClass().getName());
      return;
    }
    send(credential, tokenValue, tokenHash);
  }

  private void send(EmailCredential credential, String tokenValue, String tokenHash) {
    try {
      sender.send(credential.address(), settings.linkBaseUrl() + "#token=" + tokenValue);
    } catch (RuntimeException e) {
      log.warn(
          "Failed to send sign-in link for credential {}: {}",
          credential.id(),
          e.getClass().getName());
      deleteQuietly(credential, tokenHash);
    }
  }

  private void deleteQuietly(EmailCredential credential, String tokenHash) {
    try {
      repository.delete(credential.id(), tokenHash);
    } catch (RuntimeException e) {
      log.warn(
          "Failed to remove unsent sign-in link for credential {}: {}",
          credential.id(),
          e.getClass().getName());
    }
  }

  private static boolean looksLikeAnAddress(String address) {
    return address != null && !address.isBlank() && address.indexOf('@') >= 0;
  }
}
