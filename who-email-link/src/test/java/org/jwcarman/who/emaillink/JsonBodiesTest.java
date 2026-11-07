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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import tools.jackson.databind.json.JsonMapper;

class JsonBodiesTest {

  private final JsonMapper mapper = JsonMapper.builder().build();

  private static MockHttpServletRequest post(String body) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/x");
    request.setContent(body.getBytes(UTF_8));
    return request;
  }

  @Test
  void readsAStringField() {
    assertThat(JsonBodies.readString(mapper, post("{\"email\":\"a@b.c\"}"), "email"))
        .isEqualTo("a@b.c");
  }

  @Test
  void aMissingOrNonStringFieldIsNull() {
    assertThat(JsonBodies.readString(mapper, post("{}"), "email")).isNull();
    assertThat(JsonBodies.readString(mapper, post("{\"email\":5}"), "email")).isNull();
  }

  @Test
  void aNonObjectBodyIsNull() {
    assertThat(JsonBodies.readString(mapper, post("[\"email\"]"), "email")).isNull();
    assertThat(JsonBodies.readString(mapper, post("\"email\""), "email")).isNull();
  }

  @Test
  void aNullBodyStreamIsNull() throws IOException {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getInputStream()).thenReturn(null);

    assertThat(JsonBodies.readString(mapper, request, "email")).isNull();
  }

  @Test
  void aMapperThatYieldsNoNodeIsNull() throws IOException {
    JsonMapper nullMapper = mock(JsonMapper.class);
    when(nullMapper.readTree(any(InputStream.class))).thenReturn(null);

    assertThat(JsonBodies.readString(nullMapper, post("{}"), "email")).isNull();
  }

  @Test
  void unreadableBodiesAreNull() throws IOException {
    HttpServletRequest broken = mock(HttpServletRequest.class);
    when(broken.getInputStream()).thenThrow(new IOException("boom"));
    assertThat(JsonBodies.readString(mapper, broken, "email")).isNull();

    assertThat(JsonBodies.readString(mapper, post("{"), "email")).isNull();

    HttpServletRequest failing = mock(HttpServletRequest.class);
    when(failing.getInputStream()).thenThrow(new IllegalStateException("boom"));
    assertThat(JsonBodies.readString(mapper, failing, "email")).isNull();
  }

  private static HttpServletRequest requestWith(String body, boolean readFails, boolean closeFails)
      throws IOException {
    ByteArrayInputStream bytes = new ByteArrayInputStream(body.getBytes(UTF_8));
    ServletInputStream stream =
        new ServletInputStream() {
          @Override
          public int read() throws IOException {
            if (readFails) {
              throw new IOException("read failed");
            }
            return bytes.read();
          }

          @Override
          public int read(byte[] b, int off, int len) throws IOException {
            if (readFails) {
              throw new IOException("read failed");
            }
            return bytes.read(b, off, len);
          }

          @Override
          public void close() throws IOException {
            if (closeFails) {
              throw new IOException("close failed");
            }
          }

          @Override
          public boolean isFinished() {
            return bytes.available() == 0;
          }

          @Override
          public boolean isReady() {
            return true;
          }

          @Override
          public void setReadListener(ReadListener readListener) {
            // not used
          }
        };
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getInputStream()).thenReturn(stream);
    return request;
  }

  @Test
  void aBodyThatFailsToCloseIsNull() throws IOException {
    assertThat(
            JsonBodies.readString(
                mapper, requestWith("{\"email\":\"a@b.c\"}", false, true), "email"))
        .isNull();
  }

  @Test
  void aBodyThatFailsToReadAndToCloseIsNull() throws IOException {
    assertThat(JsonBodies.readString(mapper, requestWith("{}", true, true), "email")).isNull();
  }
}
