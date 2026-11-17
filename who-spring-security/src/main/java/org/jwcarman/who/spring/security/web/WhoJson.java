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
package org.jwcarman.who.spring.security.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;

import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes who's own JSON answers with who's own mapper, never the application's, so an application
 * that configures its Jackson (snake-case names, dates as numbers, nulls left out) does not change
 * the shape of what who answers: a record's properties keep their Java names, an {@code Instant} is
 * an ISO-8601 string, and a {@code null} is written as {@code null}.
 *
 * <p>who's resource endpoints write their answers through this class: {@code /who/me}, everything
 * under {@code /who/me/}, the admin and invitation endpoints, and {@code /who/sign-in}. Their
 * request bodies are only read as trees, which no naming or date setting changes.
 */
public final class WhoJson {

  private static final JsonMapper MAPPER =
      JsonMapper.builder().disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();

  private WhoJson() {}

  /**
   * Answers with a JSON body.
   *
   * @param response the response
   * @param status its status
   * @param body what to write
   * @throws IOException if writing fails
   */
  public static void write(HttpServletResponse response, int status, Object body)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(MAPPER.writeValueAsString(body));
  }
}
