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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.DelegatingServletInputStream;
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
  void aMapperThatYieldsNoNodeIsNull() {
    JsonMapper nullMapper = mock(JsonMapper.class);
    when(nullMapper.readTree(any(byte[].class))).thenReturn(null);

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

  @Test
  void readStringsKeepsOnlyRequestedStringFields() {
    assertThat(
            JsonBodies.readStrings(
                mapper,
                post("{\"email\":\"a@b.c\",\"code\":123456,\"token\":null,\"x\":\"y\"}"),
                "email",
                "code",
                "token"))
        .isEqualTo(Map.of("email", "a@b.c"));
  }

  @Test
  void readStringsOfANonObjectBodyIsEmpty() {
    assertThat(JsonBodies.readStrings(mapper, post("[\"email\"]"), "email")).isEmpty();
    assertThat(JsonBodies.readStrings(mapper, post("\"email\""), "email")).isEmpty();
  }

  @Test
  void readStringsOfAnInvalidBodyIsEmpty() throws IOException {
    assertThat(JsonBodies.readStrings(mapper, post("{"), "email")).isEmpty();

    HttpServletRequest noBody = mock(HttpServletRequest.class);
    when(noBody.getInputStream()).thenReturn(null);
    assertThat(JsonBodies.readStrings(mapper, noBody, "email")).isEmpty();

    HttpServletRequest broken = mock(HttpServletRequest.class);
    when(broken.getInputStream()).thenThrow(new IOException("boom"));
    assertThat(JsonBodies.readStrings(mapper, broken, "email")).isEmpty();
  }

  @Test
  void anOversizedBodyByDeclaredLengthIsEmpty() throws IOException {
    HttpServletRequest large = mock(HttpServletRequest.class);
    when(large.getContentLengthLong()).thenReturn(JsonBodies.MAX_BODY_BYTES + 1L);

    assertThat(JsonBodies.readStrings(mapper, large, "email")).isEmpty();
    verify(large, never()).getInputStream();
  }

  @Test
  void anOversizedBodyWithoutADeclaredLengthIsEmpty() throws IOException {
    String padding = " ".repeat(JsonBodies.MAX_BODY_BYTES);
    byte[] body = ("{\"email\":\"a@b.c\"}" + padding).getBytes(UTF_8);
    HttpServletRequest chunked = mock(HttpServletRequest.class);
    when(chunked.getContentLengthLong()).thenReturn(-1L);
    when(chunked.getInputStream())
        .thenReturn(new DelegatingServletInputStream(new ByteArrayInputStream(body)));

    assertThat(JsonBodies.readStrings(mapper, chunked, "email")).isEmpty();
  }

  @Test
  void aBodyAtTheCapIsRead() {
    String json = "{\"email\":\"a@b.c\"}";
    String padded = json + " ".repeat(JsonBodies.MAX_BODY_BYTES - json.length());

    assertThat(JsonBodies.readString(mapper, post(padded), "email")).isEqualTo("a@b.c");
  }
}
