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
package org.jwcarman.who.enrollment;

import java.time.Duration;

import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

// Scan both the enrollment package (JdbcEnrollmentTokenRepository) and the jdbc package
// (JdbcIdentityRepository, JdbcCredentialIdentityRepository).
@SpringBootApplication(scanBasePackages = {"org.jwcarman.who.enrollment", "org.jwcarman.who.jdbc"})
class WhoEnrollmentTestApp {

  @Bean
  WhoEnrollmentService whoEnrollmentService(
      EnrollmentTokenRepository enrollmentTokenRepository,
      IdentityRepository identityRepository,
      CredentialIdentityRepository credentialIdentityRepository) {
    return new WhoEnrollmentService(
        enrollmentTokenRepository,
        identityRepository,
        credentialIdentityRepository,
        Duration.ofHours(24));
  }
}
