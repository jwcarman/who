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
package org.jwcarman.who.apikey;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import static java.util.Objects.requireNonNull;

/**
 * Servlet filter that authenticates requests carrying a valid API key header.
 *
 * <p>If the configured header is absent or blank the request proceeds unauthenticated.
 * If the header value does not match any known key the request also proceeds unauthenticated —
 * the filter never produces a 401; that decision is left to the rest of the filter chain.
 *
 * <p>On any exception during key processing the {@link SecurityContextHolder} is cleared before
 * the exception propagates.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final ApiKeyCredentialRepository apiKeyCredentialRepository;
    private final WhoService whoService;
    private final String headerName;

    /**
     * Creates a new filter.
     *
     * @param apiKeyCredentialRepository repository for looking up hashed API keys
     * @param whoService                 service for resolving a credential to a principal
     * @param headerName                 HTTP header name that carries the API key
     */
    public ApiKeyAuthenticationFilter(ApiKeyCredentialRepository apiKeyCredentialRepository,
                                      WhoService whoService,
                                      String headerName) {
        this.apiKeyCredentialRepository = requireNonNull(apiKeyCredentialRepository,
                "apiKeyCredentialRepository must not be null");
        this.whoService = requireNonNull(whoService, "whoService must not be null");
        this.headerName = requireNonNull(headerName, "headerName must not be null");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String headerValue = request.getHeader(headerName);
        if (headerValue != null && !headerValue.isBlank()) {
            try {
                String keyHash = ApiKeyService.sha256Hex(headerValue);
                apiKeyCredentialRepository.findByKeyHash(keyHash)
                        .flatMap(whoService::resolve)
                        .map(principal -> new WhoAuthenticationToken(
                                principal,
                                principal.permissions().stream()
                                        .map(SimpleGrantedAuthority::new)
                                        .toList()))
                        .ifPresent(token -> SecurityContextHolder.getContext().setAuthentication(token));
            } catch (Exception e) {
                SecurityContextHolder.clearContext();
                throw new ServletException("Error processing API key authentication", e);
            }
        }
        filterChain.doFilter(request, response);
    }
}
