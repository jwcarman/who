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
package org.jwcarman.who.examples.observability;

import java.time.Instant;
import java.util.UUID;

import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.emaillink.EmailCredential;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.test.WhoTestSessions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "spring.docker.compose.enabled=false",
      "example.traffic.enabled=false",
      "management.otlp.metrics.export.enabled=false",
      "management.opentelemetry.tracing.export.otlp.enabled=false",
      "management.opentelemetry.logging.export.otlp.enabled=false"
    })
@AutoConfigureMockMvc
abstract class AbstractObservabilityTest {

  /**
   * One database for every test class: started once, not per class, because the classes share one
   * cached Spring context, which must not outlive its database. Testcontainers removes it after the
   * run.
   */
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

  static {
    POSTGRES.start();
  }

  @Autowired MockMvc mockMvc;
  @Autowired WhoService whoService;
  @Autowired EmailCredentialService emailCredentials;
  @Autowired RbacService rbacService;

  /**
   * A browser session for a new person holding the demo role, as an email sign-in leaves one.
   *
   * @return the session
   */
  MockHttpSession signedIn() {
    return signedInAt(Instant.now());
  }

  /**
   * A browser session for a new person holding the demo role, who signed in at {@code signedInAt}.
   *
   * @param signedInAt when they signed in
   * @return the session
   */
  MockHttpSession signedInAt(Instant signedInAt) {
    Identity identity = whoService.createIdentity();
    EmailCredential credential =
        emailCredentials.register(identity, UUID.randomUUID() + "@example.com");
    rbacService.assignRoleByName(identity, Setup.ROLE);
    return WhoTestSessions.signedIn(identity.id(), credential.id(), signedInAt);
  }
}
