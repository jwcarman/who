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

import java.util.concurrent.Executor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.ott.OneTimeToken;
import org.springframework.security.web.authentication.ott.OneTimeTokenGenerationSuccessHandler;

/**
 * Answers {@code POST /ott/generate} with {@code 202} at once and hands the real work to {@link
 * EmailLinkIssuer} on another thread, so the response time does not reveal whether the address is
 * known.
 */
public class EmailLinkGenerationSuccessHandler implements OneTimeTokenGenerationSuccessHandler {

  private final EmailLinkIssuer issuer;
  private final Executor executor;

  /**
   * Creates a new handler.
   *
   * @param issuer does the lookup, the upsert, and the sending
   * @param executor runs the issuer off the request thread
   */
  public EmailLinkGenerationSuccessHandler(EmailLinkIssuer issuer, Executor executor) {
    this.issuer = requireNonNull(issuer, "issuer must not be null");
    this.executor = requireNonNull(executor, "executor must not be null");
  }

  @Override
  public void handle(HttpServletRequest request, HttpServletResponse response, OneTimeToken token) {
    response.setStatus(HttpServletResponse.SC_ACCEPTED);
    String address = token.getUsername();
    String tokenValue = token.getTokenValue();
    executor.execute(() -> issuer.issue(address, tokenValue));
  }
}
