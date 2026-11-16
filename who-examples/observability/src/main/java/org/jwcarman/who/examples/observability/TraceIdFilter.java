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

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

/**
 * Names each request's trace in an {@value #HEADER} header, so the page and the traffic generator
 * can link to it in Grafana. It runs after Spring's observation filter, which starts the trace, and
 * before Spring Security, so a refused request names its trace too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class TraceIdFilter extends OncePerRequestFilter {

  /** The response header that holds the trace id. */
  static final String HEADER = "X-Trace-Id";

  private final Tracer tracer;

  TraceIdFilter(Tracer tracer) {
    this.tracer = tracer;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Span span = tracer.currentSpan();
    if (span != null && !span.isNoop()) {
      response.setHeader(HEADER, span.context().traceId());
    }
    chain.doFilter(request, response);
  }
}
