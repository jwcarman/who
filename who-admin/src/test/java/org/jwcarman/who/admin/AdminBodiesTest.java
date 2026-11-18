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
package org.jwcarman.who.admin;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import tools.jackson.databind.json.JsonMapper;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class AdminBodiesTest {

  @Test
  void a_body_that_cannot_be_read_is_a_bad_request() {
    // a client that goes away mid-body: nothing to answer, and nothing for the server's error log
    MockHttpServletRequest aborted =
        new MockHttpServletRequest("POST", "/who/x") {
          @Override
          public ServletInputStream getInputStream() {
            return new ServletInputStream() {
              @Override
              public int read() throws IOException {
                throw new IOException("Connection reset by peer");
              }

              @Override
              public boolean isFinished() {
                return false;
              }

              @Override
              public boolean isReady() {
                return true;
              }

              @Override
              public void setReadListener(ReadListener listener) {
                // blocking reads only
              }
            };
          }
        };

    JsonMapper jsonMapper = new JsonMapper();

    assertThatThrownBy(() -> AdminBodies.readObject(jsonMapper, aborted))
        .isInstanceOf(InvalidAdminRequestException.class)
        .hasMessage("The body could not be read");
  }
}
