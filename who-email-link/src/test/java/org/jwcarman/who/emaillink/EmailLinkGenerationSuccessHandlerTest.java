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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.ott.DefaultOneTimeToken;

class EmailLinkGenerationSuccessHandlerTest {

  @Test
  void answers202BeforeTheWorkRuns() throws Exception {
    EmailLinkIssuer issuer = mock(EmailLinkIssuer.class);
    List<Runnable> tasks = new ArrayList<>();
    EmailLinkGenerationSuccessHandler handler =
        new EmailLinkGenerationSuccessHandler(issuer, tasks::add);
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.handle(
        new MockHttpServletRequest(),
        response,
        new DefaultOneTimeToken("value", "owner@example.com", Instant.now()));

    assertThat(response.getStatus()).isEqualTo(202);
    assertThat(response.getContentAsByteArray()).isEmpty();
    verifyNoInteractions(issuer);
    assertThat(tasks).hasSize(1);

    tasks.getFirst().run();

    verify(issuer).issue("owner@example.com", "value");
  }
}
