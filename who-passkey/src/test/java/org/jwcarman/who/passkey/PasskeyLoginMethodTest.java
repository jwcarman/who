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
import static org.mockito.ArgumentMatchers.eq;
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
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.WhoWebSecurity;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.mockito.ArgumentCaptor;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
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
      return new WhoSessionSecurityContextRepository(whoService, clock);
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

  private PasskeyLoginMethod standalone() {
    return new PasskeyLoginMethod(
        mock(JdbcWhoPasskeyUserRepository.class),
        mock(JdbcWhoPasskeyCredentialRepository.class),
        mock(WhoPasskeyAuthenticationProvider.class),
        sessionRepository,
        JsonMapper.builder().build(),
        Clock.systemUTC(),
        Duration.ofMinutes(10));
  }

  private static HttpSecurity http(UserDetailsService shared, UserDetailsService bean) {
    HttpSecurity http = mock(HttpSecurity.class);
    ApplicationContext context = mock(ApplicationContext.class);
    ObjectProvider<UserDetailsService> beans = mock();
    ObjectProvider<WhoExceptionHandoff> handoffs = mock();
    when(context.getBeanProvider(WhoExceptionHandoff.class)).thenReturn(handoffs);
    when(handoffs.getIfAvailable(any())).thenReturn(new WhoExceptionHandoff(null));
    ObjectProvider<WhoEventPublisher> publishers = mock();
    when(context.getBeanProvider(WhoEventPublisher.class)).thenReturn(publishers);
    when(publishers.getIfAvailable(any())).thenReturn(WhoEventPublisher.NOOP);
    when(http.getSharedObject(ApplicationContext.class)).thenReturn(context);
    when(http.getSharedObject(UserDetailsService.class)).thenReturn(shared);
    when(context.getBeanProvider(UserDetailsService.class)).thenReturn(beans);
    when(beans.getIfUnique()).thenReturn(bean);
    return http;
  }

  @Test
  void sharesANoUsersUserDetailsServiceWhenThereIsNone() {
    HttpSecurity http = http(null, null);

    standalone().configure(http);

    ArgumentCaptor<UserDetailsService> stub = ArgumentCaptor.forClass(UserDetailsService.class);
    verify(http).setSharedObject(eq(UserDetailsService.class), stub.capture());
    UserDetailsService noUsers = stub.getValue();
    assertThatThrownBy(() -> noUsers.loadUserByUsername("anyone"))
        .isInstanceOf(UsernameNotFoundException.class);
  }

  @Test
  void keepsASharedUserDetailsService() {
    HttpSecurity http = http(mock(UserDetailsService.class), null);

    standalone().configure(http);

    verify(http, never()).setSharedObject(eq(UserDetailsService.class), any());
  }

  @Test
  void keepsAUserDetailsServiceBean() {
    HttpSecurity http = http(null, mock(UserDetailsService.class));

    standalone().configure(http);

    verify(http, never()).setSharedObject(eq(UserDetailsService.class), any());
  }
}
