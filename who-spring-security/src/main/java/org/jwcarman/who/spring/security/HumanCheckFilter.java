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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jwcarman.who.spring.security.web.HumanCheckFailedException;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Asks the application's {@link WhoHumanCheck} about every POST to a checked path, and refuses the
 * request through the application's exception handling when the check says no or fails.
 */
final class HumanCheckFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(HumanCheckFilter.class);

  private final WhoHumanCheck check;
  private final WhoExceptionHandoff handoff;
  private final Map<RequestMatcher, String> actions = new LinkedHashMap<>();

  /**
   * Creates the filter.
   *
   * @param check decides each request
   * @param handoff answers a refusal through the application's exception handling
   * @param matchers the chain's own path matching, so a path the endpoint answers is always checked
   * @param actions each checked path, mapped to the action the check is told
   */
  HumanCheckFilter(
      WhoHumanCheck check,
      WhoExceptionHandoff handoff,
      PathPatternRequestMatcher.Builder matchers,
      Map<String, String> actions) {
    this.check = requireNonNull(check, "check must not be null");
    this.handoff = requireNonNull(handoff, "handoff must not be null");
    requireNonNull(matchers, "matchers must not be null");
    requireNonNull(actions, "actions must not be null")
        .forEach(
            (path, action) -> this.actions.put(matchers.matcher(HttpMethod.POST, path), action));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Optional<String> action = actionOf(request);
    if (action.isEmpty() || passes(request, action.get())) {
      chain.doFilter(request, response);
      return;
    }
    handoff.handOff(request, response, new HumanCheckFailedException(), HttpStatus.FORBIDDEN);
  }

  private Optional<String> actionOf(HttpServletRequest request) {
    return actions.entrySet().stream()
        .filter(entry -> entry.getKey().matches(request))
        .map(Map.Entry::getValue)
        .findFirst();
  }

  private boolean passes(HttpServletRequest request, String action) {
    String header = request.getHeader(WhoHumanCheck.HEADER);
    Optional<String> token =
        StringUtils.hasText(header) ? Optional.of(header.strip()) : Optional.empty();
    try {
      return check.passes(new HumanCheckRequest(token, request.getRemoteAddr(), action));
    } catch (RuntimeException e) {
      log.warn("The human check failed, so the request is refused: {}", e.getClass().getName());
      return false;
    }
  }
}
