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

import java.util.Optional;

/**
 * What a {@link WhoHumanCheck} gets to decide on. who reads the request itself, so a check never
 * sees, or consumes, the request body.
 *
 * @param token the {@value WhoHumanCheck#HEADER} header, empty when the page sent none
 * @param clientIp the client's address, as the servlet container reports it; behind a proxy, only
 *     once Spring's forwarded-header handling is configured ({@code
 *     server.forward-headers-strategy})
 * @param action the endpoint being called: {@code email-send} or {@code email-sign-in}
 */
public record HumanCheckRequest(Optional<String> token, String clientIp, String action) {

  /** Validates the components. */
  public HumanCheckRequest {
    requireNonNull(token, "token must not be null");
    requireNonNull(clientIp, "clientIp must not be null");
    requireNonNull(action, "action must not be null");
  }
}
