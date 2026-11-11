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
package org.jwcarman.who.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * With no advice for who's exceptions, each error path still answers its status, and who writes no
 * body of its own (MockMvc does not render the servlet container's error page).
 */
@SpringBootTest(
    classes = ErrorHandoffScenarios.App.class,
    properties = {
      "who.initialize-schema=always",
      "who.email-link.link-base-url=https://app.example.com/signin",
      "who.passkey.rp-id=app.example.com"
    })
@Testcontainers
class ErrorHandoffWithoutAdviceTest extends ErrorHandoffScenarios {

  private static void assertStatusOnly(MvcResult result, int status) throws Exception {
    assertThat(result.getResponse().getStatus()).isEqualTo(status);
    assertThat(result.getResponse().getContentAsString()).isEmpty();
  }

  @Test
  void withoutAnAdviceEachPathAnswersItsStatusOnly() throws Exception {
    assertStatusOnly(badSignInToken(), 401);
    assertStatusOnly(unknownPasskeySignIn(), 401);
    assertStatusOnly(signedOutGuarded(), 401);
    assertStatusOnly(signedOutWhoMe(), 401);
    assertStatusOnly(deniedByUrlRule(), 403);
    assertStatusOnly(registrationSignedOut(), 401);
    assertStatusOnly(registrationOutsideTheWindow(), 401);
    assertStatusOnly(invalidRegistration(), 400);
  }
}
