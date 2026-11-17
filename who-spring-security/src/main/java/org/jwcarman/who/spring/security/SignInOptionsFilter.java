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

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /who/sign-in} tells a signed-out page which ways of signing in are on, so it can draw
 * its buttons: {@code {"email": true, "passkey": true, "providers": [{"id": "github", "name":
 * "GitHub"}]}}. It is public and read-only and says nothing about any identity.
 *
 * <p>The answer is every {@link PasswordlessLoginMethod#signInOptions()} merged in method order. A
 * module that is not on the classpath, or is turned off, has no method and so adds nothing: {@code
 * email} and {@code passkey} are then {@code false} and {@code providers} is empty.
 */
final class SignInOptionsFilter extends OncePerRequestFilter {

  private final RequestMatcher matcher;
  private final List<PasswordlessLoginMethod> methods;
  private final JsonMapper jsonMapper;

  SignInOptionsFilter(
      WhoPaths paths,
      Collection<? extends PasswordlessLoginMethod> methods,
      JsonMapper jsonMapper) {
    this.matcher =
        PathPatternRequestMatcher.withDefaults()
            .matcher(
                HttpMethod.GET, requireNonNull(paths, "paths must not be null").of("/sign-in"));
    this.methods = List.copyOf(requireNonNull(methods, "methods must not be null"));
    this.jsonMapper = requireNonNull(jsonMapper, "jsonMapper must not be null");
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !matcher.matches(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Map<String, Object> options = new LinkedHashMap<>();
    options.put("email", false);
    options.put("passkey", false);
    options.put("providers", List.of());
    methods.forEach(method -> options.putAll(method.signInOptions()));
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(jsonMapper.writeValueAsString(options));
  }
}
