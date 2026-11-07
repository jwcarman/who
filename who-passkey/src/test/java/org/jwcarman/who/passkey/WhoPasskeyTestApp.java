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
package org.jwcarman.who.passkey;

import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.jdbc.JdbcCredentialIdentityRepository;
import org.jwcarman.who.jdbc.JdbcIdentityRepository;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootApplication
class WhoPasskeyTestApp {

  @Bean
  IdentityRepository identityRepository(JdbcClient jdbcClient) {
    return new JdbcIdentityRepository(jdbcClient);
  }

  @Bean
  CredentialIdentityRepository credentialIdentityRepository(JdbcClient jdbcClient) {
    return new JdbcCredentialIdentityRepository(jdbcClient);
  }

  @Bean
  JdbcWhoPasskeyUserRepository passkeyUserRepository(
      JdbcClient jdbcClient, CredentialIdentityRepository credentialIdentityRepository) {
    return new JdbcWhoPasskeyUserRepository(jdbcClient, credentialIdentityRepository);
  }

  @Bean
  JdbcWhoPasskeyCredentialRepository passkeyCredentialRepository(
      JdbcClient jdbcClient, CredentialIdentityRepository credentialIdentityRepository) {
    return new JdbcWhoPasskeyCredentialRepository(jdbcClient, credentialIdentityRepository);
  }

  @Bean
  PasskeyService passkeyService(JdbcWhoPasskeyCredentialRepository credentialRepository) {
    return new PasskeyService(credentialRepository);
  }
}
