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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.config.Customizer.withDefaults;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.WhoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@Import(ApiKeyChainTest.ChainConfig.class)
class ApiKeyChainTest extends AbstractApiKeyTest {

  private static final String HEADER = "X-API-Key";

  @Autowired private WebApplicationContext context;
  @Autowired private FilterChainProxy springSecurityFilterChain;
  @Autowired private ApiKeyService apiKeyService;
  @Autowired private IdentityRepository identityRepository;

  private MockMvc mvc;
  private String key;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
    key = apiKeyService.create(identityRepository.save(Identity.create()), "chain");
  }

  @Test
  void validKeyAuthenticatesOnTheKeyedChain() throws Exception {
    mvc.perform(get("/keyed/ping").header(HEADER, key)).andExpect(status().isOk());
  }

  @Test
  void keyIsIgnoredOnAChainWithoutIt() throws Exception {
    mvc.perform(get("/plain/ping").header(HEADER, key)).andExpect(status().isUnauthorized());
  }

  @Test
  void applyingItTwiceRegistersTheFilterOnce() {
    SecurityFilterChain twice =
        springSecurityFilterChain.getFilterChains().stream()
            .filter(
                c ->
                    c instanceof DefaultSecurityFilterChain d
                        && d.getRequestMatcher().toString().contains("/twice/"))
            .findFirst()
            .orElseThrow();

    assertThat(twice.getFilters().stream().filter(ApiKeyAuthenticationFilter.class::isInstance))
        .hasSize(1);
  }

  @TestConfiguration
  @EnableWebSecurity
  static class ChainConfig {

    @Bean
    ApiKeyAuthenticationFilter apiKeyAuthenticationFilter(
        ApiKeyCredentialRepository repository, WhoService whoService) {
      return new ApiKeyAuthenticationFilter(repository, whoService, HEADER);
    }

    @Bean
    PingController pingController() {
      return new PingController();
    }

    @Bean
    @Order(1)
    SecurityFilterChain keyed(HttpSecurity http) throws Exception {
      http.securityMatcher("/keyed/**")
          .authorizeHttpRequests(a -> a.anyRequest().authenticated())
          .httpBasic(withDefaults())
          .csrf(c -> c.disable());
      http.with(WhoApiKeySecurity.apiKey(), Customizer.withDefaults());
      return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain twice(HttpSecurity http) throws Exception {
      http.securityMatcher("/twice/**")
          .authorizeHttpRequests(a -> a.anyRequest().authenticated())
          .httpBasic(withDefaults())
          .csrf(c -> c.disable());
      http.with(WhoApiKeySecurity.apiKey(), Customizer.withDefaults());
      http.with(WhoApiKeySecurity.apiKey(), Customizer.withDefaults());
      return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain plain(HttpSecurity http) throws Exception {
      return http.securityMatcher("/plain/**")
          .authorizeHttpRequests(a -> a.anyRequest().authenticated())
          .httpBasic(withDefaults())
          .csrf(c -> c.disable())
          .build();
    }
  }

  @org.springframework.web.bind.annotation.RestController
  static class PingController {
    @org.springframework.web.bind.annotation.GetMapping({"/keyed/ping", "/plain/ping"})
    String ping() {
      return "pong";
    }
  }
}
