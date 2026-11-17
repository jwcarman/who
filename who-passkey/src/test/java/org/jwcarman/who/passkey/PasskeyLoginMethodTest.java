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
package org.jwcarman.who.passkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.config.Customizer.withDefaults;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.jdbc.JdbcCredentialIdentityRepository;
import org.jwcarman.who.jdbc.JdbcIdentityRepository;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.jwcarman.who.spring.security.WhoPaths;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.WhoWebSecurity;
import org.jwcarman.who.spring.security.web.LastSignInCookie;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.jwcarman.who.test.InMemorySessionRecordRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.webauthn.authentication.PublicKeyCredentialRequestOptionsFilter;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthenticationFilter;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.security.web.webauthn.registration.DefaultWebAuthnRegistrationPageGeneratingFilter;
import org.springframework.security.web.webauthn.registration.PublicKeyCredentialCreationOptionsFilter;
import org.springframework.security.web.webauthn.registration.WebAuthnRegistrationFilter;
import org.springframework.test.util.ReflectionTestUtils;

import tools.jackson.databind.json.JsonMapper;

/**
 * A real browser chain with {@code passwordless()} and the passkey login method, wired by hand
 * (autoconfiguration comes later). Without a {@code UserDetailsService} anywhere, the chain only
 * builds because the login method shares its no-users stub.
 */
@SpringBootTest(classes = PasskeyLoginMethodTest.TestApp.class)
class PasskeyLoginMethodTest extends AbstractPasskeyTest {

  private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");

  @Configuration
  @EnableAutoConfiguration(exclude = UserDetailsServiceAutoConfiguration.class)
  static class TestApp {

    @Bean
    Clock clock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Bean
    IdentityRepository identityRepository(JdbcClient jdbcClient) {
      return new JdbcIdentityRepository(jdbcClient);
    }

    @Bean
    CredentialIdentityRepository credentialIdentityRepository(JdbcClient jdbcClient) {
      return new JdbcCredentialIdentityRepository(jdbcClient);
    }

    @Bean
    WhoService whoService(
        IdentityRepository identityRepository,
        CredentialIdentityRepository credentialIdentityRepository) {
      return new WhoService(identityRepository, credentialIdentityRepository, i -> Set.of());
    }

    @Bean
    WhoSessionSecurityContextRepository whoSessionSecurityContextRepository(
        WhoService whoService, Clock clock) {
      return new WhoSessionSecurityContextRepository(
          whoService,
          InMemorySessionRecordRepository.whoSessions(clock, WhoEventPublisher.NOOP),
          clock,
          WhoEventPublisher.NOOP);
    }

    @Bean
    JdbcWhoPasskeyUserRepository passkeyUserRepository(
        JdbcClient jdbcClient, CredentialIdentityRepository credentialIdentityRepository) {
      return new JdbcWhoPasskeyUserRepository(jdbcClient, credentialIdentityRepository);
    }

    @Bean
    JdbcWhoPasskeyCredentialRepository passkeyCredentialRepository(
        JdbcClient jdbcClient, CredentialIdentityRepository credentialIdentityRepository) {
      return new JdbcWhoPasskeyCredentialRepository(jdbcClient, credentialIdentityRepository);
    }

    @Bean
    WebAuthnRelyingPartyOperations relyingPartyOperations(
        JdbcWhoPasskeyUserRepository userRepository,
        JdbcWhoPasskeyCredentialRepository credentialRepository) {
      return new WhoRelyingPartyOperations(
          new PasskeySettings(
              "app.test", "App", Set.of("https://app.test"), Duration.ofMinutes(10)),
          userRepository,
          credentialRepository,
          PasskeyNameResolver.identityId(),
          (identityId, label) -> {});
    }

    @Bean
    @Primary
    AuthenticationEventPublisher authenticationEventPublisher() {
      return new DefaultAuthenticationEventPublisher();
    }

    @Bean
    PasskeyLoginMethod passkeyLoginMethod(
        JdbcWhoPasskeyUserRepository userRepository,
        JdbcWhoPasskeyCredentialRepository credentialRepository,
        WebAuthnRelyingPartyOperations relyingPartyOperations,
        WhoService whoService,
        WhoSessionSecurityContextRepository sessionRepository,
        JsonMapper jsonMapper,
        Clock clock) {
      return new PasskeyLoginMethod(
          WhoPaths.defaults(),
          userRepository,
          credentialRepository,
          new WhoPasskeyAuthenticationProvider(
              relyingPartyOperations, credentialRepository, whoService),
          sessionRepository,
          jsonMapper,
          clock,
          Duration.ofMinutes(10));
    }

    @Bean
    SecurityFilterChain browserChain(HttpSecurity http) {
      http.authorizeHttpRequests(a -> a.anyRequest().authenticated())
          .with(WhoWebSecurity.passwordless(), withDefaults());
      return http.build();
    }
  }

  @Autowired private SecurityFilterChain chain;
  @Autowired private PasskeyLoginMethod loginMethod;
  @Autowired private WhoSessionSecurityContextRepository sessionRepository;
  @Autowired private AuthenticationEventPublisher eventPublisher;
  @Autowired private JdbcWhoPasskeyUserRepository userRepository;
  @Autowired private JdbcWhoPasskeyCredentialRepository credentialRepository;
  @Autowired private WebAuthnRelyingPartyOperations relyingPartyOperations;
  @Autowired private ApplicationContext applicationContext;

  private <T extends Filter> T filter(Class<T> type) {
    List<T> found = chain.getFilters().stream().filter(type::isInstance).map(type::cast).toList();
    assertThat(found).as(type.getSimpleName()).hasSize(1);
    return found.getFirst();
  }

  private int indexOf(Class<? extends Filter> type) {
    List<Filter> filters = chain.getFilters();
    for (int i = 0; i < filters.size(); i++) {
      if (type.isInstance(filters.get(i))) {
        return i;
      }
    }
    throw new AssertionError(type.getSimpleName() + " not in the chain");
  }

  @Test
  void loginMethodInstallsWhoCollaborators() throws Exception {
    WebAuthnAuthenticationFilter signIn = filter(WebAuthnAuthenticationFilter.class);

    Object manager = ReflectionTestUtils.getField(signIn, "authenticationManager");
    assertThat(manager).isInstanceOf(ProviderManager.class);
    assertThat(((ProviderManager) manager).getProviders())
        .containsExactly(
            (WhoPasskeyAuthenticationProvider)
                ReflectionTestUtils.getField(loginMethod, "authenticationProvider"));
    assertThat(ReflectionTestUtils.getField(manager, "eventPublisher")).isSameAs(eventPublisher);

    assertThat(ReflectionTestUtils.getField(signIn, "securityContextRepository"))
        .isSameAs(sessionRepository);

    Object strategy = ReflectionTestUtils.getField(signIn, "sessionStrategy");
    assertThat(strategy).isInstanceOf(CompositeSessionAuthenticationStrategy.class);
    assertThat((List<?>) ReflectionTestUtils.getField(strategy, "delegateStrategies"))
        .hasAtLeastOneElementOfType(ChangeSessionIdAuthenticationStrategy.class)
        .hasAtLeastOneElementOfType(CsrfAuthenticationStrategy.class);

    Identity identity = Identity.create();
    MockHttpServletResponse success = new MockHttpServletResponse();
    ((AuthenticationSuccessHandler) ReflectionTestUtils.getField(signIn, "successHandler"))
        .onAuthenticationSuccess(
            new MockHttpServletRequest(),
            success,
            new WhoAuthenticationToken(new WhoPrincipal(identity, Set.of()), UUID.randomUUID()));
    assertThat(success.getStatus()).isEqualTo(200);
    assertThat(success.getContentAsString())
        .isEqualTo("{\"identityId\":\"" + identity.id() + "\"}");

    MockHttpServletResponse failure = new MockHttpServletResponse();
    ((AuthenticationFailureHandler) ReflectionTestUtils.getField(signIn, "failureHandler"))
        .onAuthenticationFailure(
            new MockHttpServletRequest(), failure, new BadCredentialsException("invalid"));
    // a failed sign-in is handed to the application; with nothing to format it, a bare 401
    assertThat(failure.getStatus()).isEqualTo(401);
    assertThat(failure.getContentAsString()).isEmpty();
  }

  @Test
  void webAuthnUsesWhosRepositoriesAndRelyingParty() {
    WebAuthnRegistrationFilter registration = filter(WebAuthnRegistrationFilter.class);
    assertThat(ReflectionTestUtils.getField(registration, "userCredentials"))
        .isSameAs(credentialRepository);
    assertThat(ReflectionTestUtils.getField(registration, "rpOptions"))
        .isSameAs(relyingPartyOperations);
    Object deleteAuthorization =
        ReflectionTestUtils.getField(registration, "deleteCredentialAuthorizationManager");
    assertThat(ReflectionTestUtils.getField(deleteAuthorization, "userEntities"))
        .isSameAs(userRepository);
    assertThat(ReflectionTestUtils.getField(deleteAuthorization, "userCredentials"))
        .isSameAs(credentialRepository);
  }

  @Test
  void defaultRegistrationPageIsOff() {
    assertThat(chain.getFilters())
        .noneMatch(DefaultWebAuthnRegistrationPageGeneratingFilter.class::isInstance);
  }

  @Test
  void guardRunsAfterExceptionTranslationAndBeforeWebAuthn() {
    filter(PasskeyRegistrationGuardFilter.class);
    int guard = indexOf(PasskeyRegistrationGuardFilter.class);

    assertThat(guard)
        .isGreaterThan(indexOf(ExceptionTranslationFilter.class))
        .isLessThan(indexOf(PublicKeyCredentialCreationOptionsFilter.class))
        .isLessThan(indexOf(AuthorizationFilter.class))
        .isLessThan(indexOf(WebAuthnRegistrationFilter.class));
  }

  @Test
  void chainWithoutSessionManagementFailsToBuild() throws Exception {
    HttpSecurity http = applicationContext.getBean(HttpSecurity.class);
    http.sessionManagement(AbstractHttpConfigurer::disable);
    loginMethod.configure(http);

    assertThatThrownBy(http::build)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("session management");
  }

  @Test
  void isNamedPasskey() {
    assertThat(standalone().name()).isEqualTo("passkey");
  }

  private PasskeyLoginMethod standalone() {
    return new PasskeyLoginMethod(
        WhoPaths.defaults(),
        mock(JdbcWhoPasskeyUserRepository.class),
        mock(JdbcWhoPasskeyCredentialRepository.class),
        mock(WhoPasskeyAuthenticationProvider.class),
        sessionRepository,
        JsonMapper.builder().build(),
        Clock.systemUTC(),
        Duration.ofMinutes(10));
  }

  private static HttpSecurity mockHttp() {
    HttpSecurity http = mock(HttpSecurity.class);
    ApplicationContext context = mock(ApplicationContext.class);
    ObjectProvider<WhoExceptionHandoff> handoffs = mock();
    when(context.getBeanProvider(WhoExceptionHandoff.class)).thenReturn(handoffs);
    when(handoffs.getIfAvailable(any())).thenReturn(new WhoExceptionHandoff(null));
    ObjectProvider<WhoEventPublisher> publishers = mock();
    when(context.getBeanProvider(WhoEventPublisher.class)).thenReturn(publishers);
    when(publishers.getIfAvailable(any())).thenReturn(WhoEventPublisher.NOOP);
    ObjectProvider<LastSignInCookie> cookies = mock();
    when(context.getBeanProvider(LastSignInCookie.class)).thenReturn(cookies);
    when(http.getSharedObject(ApplicationContext.class)).thenReturn(context);
    return http;
  }

  /**
   * who adds Spring's passkey filters itself, so nothing (no stub UserDetailsService) is shared.
   */
  @Test
  void nothing_is_shared_with_the_chain() {
    HttpSecurity http = mockHttp();

    standalone().configure(http);

    verify(http, never()).setSharedObject(any(), any());
  }

  /**
   * Whether the filter takes the request for itself rather than passing it on. A filter that takes
   * it may fail on the empty request; that still means it took it.
   */
  private static boolean takes(Filter filter, String method, String path) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    MockFilterChain next = new MockFilterChain();
    try {
      filter.doFilter(request, new MockHttpServletResponse(), next);
    } catch (RuntimeException _) {
      return true;
    }
    return next.getRequest() == null;
  }

  @Test
  void old_path_is_not_served() throws Exception {
    Filter registrationOptions = filter(PublicKeyCredentialCreationOptionsFilter.class);
    Filter registration = filter(WebAuthnRegistrationFilter.class);

    assertThat(takes(registrationOptions, "POST", "/who/passkey/register/options")).isFalse();
    assertThat(takes(registration, "POST", "/who/passkey/register")).isFalse();
    assertThat(takes(registration, "DELETE", "/who/passkey/register/abc")).isFalse();
  }

  @Test
  void each_passkey_filter_answers_at_who_s_path_only() throws Exception {
    Filter signIn = filter(WebAuthnAuthenticationFilter.class);
    Filter signInOptions = filter(PublicKeyCredentialRequestOptionsFilter.class);
    Filter registrationOptions = filter(PublicKeyCredentialCreationOptionsFilter.class);
    Filter registration = filter(WebAuthnRegistrationFilter.class);

    assertThat(takes(signIn, "POST", "/who/passkey/sign-in")).isTrue();
    assertThat(takes(signInOptions, "POST", "/who/passkey/sign-in/options")).isTrue();
    assertThat(takes(registrationOptions, "POST", "/who/me/passkeys/options")).isTrue();
    assertThat(takes(registration, "POST", "/who/me/passkeys")).isTrue();
    assertThat(takes(registration, "DELETE", "/who/me/passkeys/abc")).isFalse();

    assertThat(takes(signIn, "POST", "/login/webauthn")).isFalse();
    assertThat(takes(signInOptions, "POST", "/webauthn/authenticate/options")).isFalse();
    assertThat(takes(registrationOptions, "POST", "/webauthn/register/options")).isFalse();
    assertThat(takes(registration, "POST", "/webauthn/register")).isFalse();
    assertThat(takes(registration, "DELETE", "/webauthn/register/abc")).isFalse();
  }

  private static Set<Class<?>> passkeyFilterTypes(SecurityFilterChain chain) {
    return chain.getFilters().stream()
        .<Class<?>>map(Object::getClass)
        .filter(
            type -> type.getPackageName().startsWith("org.springframework.security.web.webauthn"))
        .collect(Collectors.toSet());
  }

  /**
   * who wires Spring's passkey filters instead of calling {@code webAuthn()}: a Spring release that
   * adds, drops or swaps one fails here, so who's wiring is revisited.
   */
  @Test
  void who_adds_the_passkey_filters_spring_s_webAuthn_adds() throws Exception {
    HttpSecurity springs = applicationContext.getBean(HttpSecurity.class);
    springs.setSharedObject(UserDetailsService.class, username -> null);
    springs.webAuthn(w -> w.rpId("app.test").disableDefaultRegistrationPage(true));

    assertThat(passkeyFilterTypes(chain))
        .isNotEmpty()
        .isEqualTo(passkeyFilterTypes(springs.build()));
  }
}
