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

import static java.util.Objects.requireNonNull;

import java.time.Clock;

import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.spring.security.web.SignInFailedException;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

/**
 * Signs in requests that carry an API key header, through Spring Security's {@link
 * AuthenticationFilter}: {@link ApiKeyAuthenticationConverter} reads the header and {@link
 * ApiKeyAuthenticationProvider} resolves the key.
 *
 * <ul>
 *   <li>No header, a blank one, or a request that is already authenticated (other than
 *       anonymously): the request goes on as it is.
 *   <li>A key that signs in: the request is authenticated as the key's identity, for this request
 *       only (no session is created to hold it), and goes on. As with every Spring {@link
 *       AuthenticationFilter}, a session the request already has gets a new id.
 *   <li>A key that does not sign in (unknown, revoked, or of an inactive identity): the request is
 *       refused at once with {@code 401}, even where anonymous access is permitted. The refusal is
 *       handed to the application as a {@link SignInFailedException}, the same answer every who
 *       sign-in gives, which never says why.
 * </ul>
 *
 * <p>Add it to a chain with {@link WhoApiKeySecurity}, which also gives it the application's
 * authentication event publisher, observation and exception handoff; it is not registered with the
 * servlet container on its own.
 */
public class ApiKeyAuthenticationFilter extends AuthenticationFilter {

  private final ApiKeyAuthenticationProvider provider;

  /**
   * Creates a new filter that publishes no who events.
   *
   * @param apiKeyCredentialRepository repository for looking up hashed API keys
   * @param whoService service for resolving a credential to a principal
   * @param headerName HTTP header name that carries the API key
   */
  public ApiKeyAuthenticationFilter(
      ApiKeyCredentialRepository apiKeyCredentialRepository,
      WhoService whoService,
      String headerName) {
    this(
        new ApiKeyAuthenticationProvider(
            apiKeyCredentialRepository, whoService, WhoEventPublisher.NOOP, Clock.systemUTC()),
        headerName);
  }

  /**
   * Creates a new filter.
   *
   * @param provider resolves the key and publishes the sign-in events
   * @param headerName HTTP header name that carries the API key
   */
  public ApiKeyAuthenticationFilter(ApiKeyAuthenticationProvider provider, String headerName) {
    super(
        new ProviderManager(requireNonNull(provider, "provider must not be null")),
        new ApiKeyAuthenticationConverter(headerName));
    this.provider = provider;
    // the provider publishes the sign-in; the request just goes on
    setSuccessHandler((request, response, authentication) -> {});
    setFailureHandler(handOffTo(new WhoExceptionHandoff(null)));
    // the default already, but this filter depends on it: the sign-in lasts for the request only
    setSecurityContextRepository(new RequestAttributeSecurityContextRepository());
  }

  /**
   * Applies the application's collaborators: the authentication manager gets the application's
   * {@link AuthenticationEventPublisher} and is post-processed as Spring post-processes the
   * managers it builds (observed, when the application observes), and refusals are handed to the
   * application's {@link WhoExceptionHandoff}.
   */
  void applyApplicationContext(ApplicationContext context) {
    ProviderManager manager = new ProviderManager(provider);
    context
        .getBeanProvider(AuthenticationEventPublisher.class)
        .ifUnique(manager::setAuthenticationEventPublisher);
    ObjectProvider<ObjectPostProcessor<AuthenticationManager>> postProcessor =
        context.getBeanProvider(
            ResolvableType.forClassWithGenerics(
                ObjectPostProcessor.class, AuthenticationManager.class));
    AuthenticationManager postProcessed =
        postProcessor.getIfUnique(ObjectPostProcessor::identity).postProcess(manager);
    setAuthenticationManagerResolver(request -> postProcessed);
    setFailureHandler(
        handOffTo(
            context
                .getBeanProvider(WhoExceptionHandoff.class)
                .getIfAvailable(() -> new WhoExceptionHandoff(null))));
  }

  private static AuthenticationFailureHandler handOffTo(WhoExceptionHandoff handoff) {
    return (request, response, exception) ->
        handoff.handOff(
            request, response, new SignInFailedException(exception), HttpStatus.UNAUTHORIZED);
  }
}
