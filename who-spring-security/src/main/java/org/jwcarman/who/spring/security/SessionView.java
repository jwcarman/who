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
package org.jwcarman.who.spring.security;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * One of the caller's live browser sessions, as {@code GET /who/me/sessions} lists it.
 *
 * @param id who's id for the session, never the servlet session's
 * @param device a short label for the browser, such as {@code Firefox on Windows}
 * @param userAgent the browser's {@code User-Agent} when it signed in
 * @param signInMethod how it signed in, such as {@code passkey}
 * @param signedInAt when it signed in, ISO-8601
 * @param lastSeenAt when it was last used, ISO-8601
 * @param current whether it is the session making this request
 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SessionView(
    String id,
    String device,
    String userAgent,
    String signInMethod,
    String signedInAt,
    String lastSeenAt,
    boolean current) {}
