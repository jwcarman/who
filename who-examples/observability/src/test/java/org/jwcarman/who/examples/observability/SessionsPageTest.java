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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** The page shows where the demo person is signed in. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SessionsPageTest extends AbstractObservabilityTest {

  @Test
  void the_page_lists_sessions() throws Exception {
    mockMvc
        .perform(get("/index.html"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"sessions\"")));
    mockMvc
        .perform(get("/who/me/sessions").session(signedIn()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].current").value(true));
    mockMvc.perform(get("/js/sessions.js")).andExpect(status().isOk());
  }
}
