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
package org.jwcarman.who.jwt;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.service.WhoService;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Drives a real bearer-token filter (with the real {@link WhoJwtAuthenticationConverter}) so that
 * an odd token yields 401 rather than an unhandled exception (500).
 */
class WhoJwtChainTest {

  @Test
  void missingIssuerIs401() throws Exception {
    Instant now = Instant.now();
    Jwt noIssuer =
        new Jwt(
            "tok", now, now.plusSeconds(3600), Map.of("alg", "RS256"), Map.of("sub", "user-123"));
    JwtDecoder decoder = mock(JwtDecoder.class);
    when(decoder.decode("tok")).thenReturn(noIssuer);

    JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
    provider.setJwtAuthenticationConverter(
        new WhoJwtAuthenticationConverter(
            mock(JwtCredentialRepository.class), mock(WhoService.class)));
    BearerTokenAuthenticationFilter filter =
        new BearerTokenAuthenticationFilter(new ProviderManager(provider));
    filter.setAuthenticationEntryPoint(new BearerTokenAuthenticationEntryPoint());
    filter.setAuthenticationFailureHandler(
        (request, response, exception) ->
            new BearerTokenAuthenticationEntryPoint().commence(request, response, exception));

    MockMvc mvc = MockMvcBuilders.standaloneSetup(new Object()).addFilters(filter).build();

    mvc.perform(get("/anything").header("Authorization", "Bearer tok"))
        .andExpect(status().isUnauthorized());
  }
}
