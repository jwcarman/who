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
package org.jwcarman.who.examples.webandapi;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The example's own settings.
 *
 * @param issuer the authorization server whose accounts are linked
 * @param accounts the accounts that may use the API, and their roles
 */
@ConfigurationProperties("example")
public record ExampleProperties(String issuer, List<Account> accounts) {

  /**
   * An account at the issuer that may use the API.
   *
   * @param subject the account's {@code sub} at the issuer
   * @param email the address the person signs in to the website with
   * @param role its role on the board
   */
  public record Account(String subject, String email, TeamRole role) {}
}
