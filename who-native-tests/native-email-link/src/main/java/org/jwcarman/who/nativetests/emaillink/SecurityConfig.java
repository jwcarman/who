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
package org.jwcarman.who.nativetests.emaillink;

import org.jwcarman.who.spring.security.WhoWebSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** The browser chain from the email-links guide, plus the test endpoints. */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

  @Bean
  SecurityFilterChain browserChain(HttpSecurity http) throws Exception {
    http.with(WhoWebSecurity.passwordless(), Customizer.withDefaults());
    http.authorizeHttpRequests(
        a ->
            a.requestMatchers("/ott/generate", "/login/ott", "/test/**", "/error")
                .permitAll()
                .anyRequest()
                .authenticated());
    return http.build();
  }
}
