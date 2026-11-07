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
package org.jwcarman.who.emaillink;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.ott.OneTimeTokenLoginConfigurer;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import tools.jackson.databind.json.JsonMapper;

class EmailLinkLoginMethodTest {

  @Test
  @SuppressWarnings("unchecked")
  void configuresEveryHookAndLeavesTheUrlsAlone() {
    JsonMapper mapper = JsonMapper.builder().build();
    WhoOneTimeTokenService tokenService = mock(WhoOneTimeTokenService.class);
    JsonGenerateOneTimeTokenRequestResolver resolver =
        new JsonGenerateOneTimeTokenRequestResolver(mapper);
    EmailLinkGenerationSuccessHandler generationHandler =
        mock(EmailLinkGenerationSuccessHandler.class);
    JsonOneTimeTokenAuthenticationConverter converter =
        new JsonOneTimeTokenAuthenticationConverter(mapper);
    EmailLinkAuthenticationProvider provider = mock(EmailLinkAuthenticationProvider.class);
    AuthenticationSuccessHandler success = mock(AuthenticationSuccessHandler.class);
    AuthenticationFailureHandler failure = mock(AuthenticationFailureHandler.class);
    EmailLinkLoginMethod method =
        new EmailLinkLoginMethod(
            tokenService, resolver, generationHandler, converter, provider, success, failure);

    HttpSecurity http = mock(HttpSecurity.class);
    method.configure(http);

    ArgumentCaptor<Customizer<OneTimeTokenLoginConfigurer<HttpSecurity>>> customizer =
        ArgumentCaptor.forClass(Customizer.class);
    verify(http).oneTimeTokenLogin(customizer.capture());
    OneTimeTokenLoginConfigurer<HttpSecurity> ott =
        mock(OneTimeTokenLoginConfigurer.class, RETURNS_SELF);
    customizer.getValue().customize(ott);

    verify(ott).tokenService(tokenService);
    verify(ott).generateRequestResolver(resolver);
    verify(ott).tokenGenerationSuccessHandler(generationHandler);
    verify(ott).authenticationConverter(converter);
    verify(ott).authenticationProvider(provider);
    verify(ott).authenticationSuccessHandler(success);
    verify(ott).authenticationFailureHandler(failure);
    verify(ott).showDefaultSubmitPage(false);
    verify(ott, never()).tokenGeneratingUrl(anyString());
    verify(ott, never()).loginProcessingUrl(anyString());
    verify(ott, never()).loginPage(anyString());
    verify(ott, never()).defaultSubmitPageUrl(anyString());
  }
}
