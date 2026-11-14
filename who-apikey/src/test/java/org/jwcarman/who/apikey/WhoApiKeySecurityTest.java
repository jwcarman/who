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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoApiKeySecurityTest {

  private final HttpSecurity http = mock(HttpSecurity.class);
  private final ApiKeyAuthenticationFilter filter = mock(ApiKeyAuthenticationFilter.class);

  @Test
  void an_explicit_filter_is_added_as_it_is_when_the_chain_has_no_application_context() {
    when(http.getSharedObject(ApplicationContext.class)).thenReturn(null);

    WhoApiKeySecurity.apiKey(filter).configure(http);

    verify(http).addFilterBefore(filter, AuthorizationFilter.class);
    verify(filter, never()).applyApplicationContext(any());
  }

  @Test
  void an_explicit_filter_is_wired_from_the_application_context_when_there_is_one() {
    ApplicationContext context = mock(ApplicationContext.class);
    when(http.getSharedObject(ApplicationContext.class)).thenReturn(context);

    WhoApiKeySecurity.apiKey(filter).configure(http);

    verify(filter).applyApplicationContext(context);
    verify(http).addFilterBefore(filter, AuthorizationFilter.class);
  }
}
