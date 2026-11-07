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

import static java.util.Objects.requireNonNull;

import org.jwcarman.who.spring.security.PasswordlessLoginMethod;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ott.OneTimeTokenService;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.ott.GenerateOneTimeTokenRequestResolver;
import org.springframework.security.web.authentication.ott.OneTimeTokenGenerationSuccessHandler;

/**
 * Email-link sign-in through Spring Security's {@code oneTimeTokenLogin()}, at its default URLs
 * ({@code POST /ott/generate}, {@code POST /login/ott}), with who's hooks and no default pages.
 */
public class EmailLinkLoginMethod implements PasswordlessLoginMethod {

  private final OneTimeTokenService tokenService;
  private final GenerateOneTimeTokenRequestResolver generateRequestResolver;
  private final OneTimeTokenGenerationSuccessHandler generationSuccessHandler;
  private final AuthenticationConverter authenticationConverter;
  private final AuthenticationProvider authenticationProvider;
  private final AuthenticationSuccessHandler successHandler;
  private final AuthenticationFailureHandler failureHandler;

  /**
   * Creates a new login method.
   *
   * @param tokenService generates and consumes tokens
   * @param generateRequestResolver reads the generation request
   * @param generationSuccessHandler answers the generation request and sends the link
   * @param authenticationConverter reads the sign-in request
   * @param authenticationProvider authenticates the token
   * @param successHandler answers a successful sign-in
   * @param failureHandler answers a failed sign-in
   */
  public EmailLinkLoginMethod(
      OneTimeTokenService tokenService,
      GenerateOneTimeTokenRequestResolver generateRequestResolver,
      OneTimeTokenGenerationSuccessHandler generationSuccessHandler,
      AuthenticationConverter authenticationConverter,
      AuthenticationProvider authenticationProvider,
      AuthenticationSuccessHandler successHandler,
      AuthenticationFailureHandler failureHandler) {
    this.tokenService = requireNonNull(tokenService, "tokenService must not be null");
    this.generateRequestResolver =
        requireNonNull(generateRequestResolver, "generateRequestResolver must not be null");
    this.generationSuccessHandler =
        requireNonNull(generationSuccessHandler, "generationSuccessHandler must not be null");
    this.authenticationConverter =
        requireNonNull(authenticationConverter, "authenticationConverter must not be null");
    this.authenticationProvider =
        requireNonNull(authenticationProvider, "authenticationProvider must not be null");
    this.successHandler = requireNonNull(successHandler, "successHandler must not be null");
    this.failureHandler = requireNonNull(failureHandler, "failureHandler must not be null");
  }

  @Override
  public void configure(HttpSecurity http) {
    http.oneTimeTokenLogin(
        ott ->
            ott.tokenService(tokenService)
                .generateRequestResolver(generateRequestResolver)
                .tokenGenerationSuccessHandler(generationSuccessHandler)
                .authenticationConverter(authenticationConverter)
                .authenticationProvider(authenticationProvider)
                .authenticationSuccessHandler(successHandler)
                .authenticationFailureHandler(failureHandler)
                .showDefaultSubmitPage(false));
  }
}
