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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.CredentialAdded;
import org.jwcarman.who.core.event.CredentialKind;
import org.jwcarman.who.core.event.CredentialRevoked;
import org.jwcarman.who.core.event.SignInFailed;
import org.jwcarman.who.core.event.SignInFailureReason;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.SignInSucceeded;
import org.jwcarman.who.core.event.WhoEvent;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.service.Resolution.Inactive;
import org.jwcarman.who.core.service.Resolution.NoIdentity;
import org.jwcarman.who.core.service.Resolution.NotLinked;
import org.jwcarman.who.core.service.Resolution.Resolved;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The events JWTs publish: {@link JwtCredentialService} for credential changes and {@link
 * WhoJwtAuthenticationConverter} for sign-ins, one per request, through a real bearer-token filter.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JwtEventsTest {

  private static final Instant NOW = Instant.parse("2026-11-13T10:15:30Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String ISSUER = "https://issuer.example.com";
  private static final String SUBJECT = "user-123";
  private static final String TOKEN = "super.secret.token";

  private final List<WhoEvent> events = new CopyOnWriteArrayList<>();

  @Nested
  class Managing_credentials {

    private final JwtCredentialRepository repository = mock(JwtCredentialRepository.class);
    private final CredentialIdentityRepository links = mock(CredentialIdentityRepository.class);
    private final JwtCredentialService service =
        new JwtCredentialService(repository, links, events::add, CLOCK);

    @Test
    void link_publishes_credential_added_with_the_issuer() {
      Identity identity = Identity.create();
      when(repository.save(any(JwtCredential.class))).thenAnswer(call -> call.getArgument(0));

      JwtCredential credential = service.link(identity, ISSUER, SUBJECT);

      verify(links).link(credential.id(), identity.id());
      assertThat(events)
          .containsExactly(
              new CredentialAdded(NOW, identity.id(), credential.id(), CredentialKind.JWT, ISSUER));
      assertThat(events).noneMatch(event -> event.toString().contains(SUBJECT));
    }

    @Test
    void unlink_publishes_credential_revoked() {
      UUID identityId = UUID.randomUUID();
      JwtCredential credential = JwtCredential.create(ISSUER, SUBJECT);
      when(repository.findById(credential.id())).thenReturn(Optional.of(credential));
      when(links.findIdentityIdByCredentialId(credential.id())).thenReturn(Optional.of(identityId));

      service.unlink(credential.id());

      verify(repository).deleteById(credential.id());
      verify(links).unlink(credential.id());
      assertThat(events)
          .containsExactly(
              new CredentialRevoked(NOW, identityId, credential.id(), CredentialKind.JWT, ISSUER));
    }

    @Test
    void unlinking_an_id_that_is_linked_to_nothing_publishes_nothing() {
      UUID unknown = UUID.randomUUID();
      when(links.findIdentityIdByCredentialId(unknown)).thenReturn(Optional.empty());

      service.unlink(unknown);

      assertThat(events).isEmpty();
    }

    @Test
    void a_repository_that_cannot_find_by_id_still_publishes_revoked_with_no_label() {
      UUID identityId = UUID.randomUUID();
      UUID credentialId = UUID.randomUUID();
      JwtCredentialRepository defaulting = mock(JwtCredentialRepository.class);
      when(defaulting.findById(credentialId)).thenCallRealMethod();
      when(links.findIdentityIdByCredentialId(credentialId)).thenReturn(Optional.of(identityId));

      new JwtCredentialService(defaulting, links, events::add, CLOCK).unlink(credentialId);

      assertThat(events)
          .containsExactly(
              new CredentialRevoked(NOW, identityId, credentialId, CredentialKind.JWT, null));
    }
  }

  @Nested
  class Signing_in {

    private final JwtCredentialRepository repository = mock(JwtCredentialRepository.class);
    private final WhoService whoService = mock(WhoService.class);
    private final JwtDecoder decoder = mock(JwtDecoder.class);
    private MockMvc mvc;

    @BeforeEach
    void setUpChain() {
      JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
      provider.setJwtAuthenticationConverter(
          new WhoJwtAuthenticationConverter(repository, whoService, events::add, CLOCK));
      BearerTokenAuthenticationFilter filter =
          new BearerTokenAuthenticationFilter(new ProviderManager(provider));
      filter.setAuthenticationEntryPoint(new BearerTokenAuthenticationEntryPoint());
      filter.setAuthenticationFailureHandler(
          (request, response, exception) ->
              new BearerTokenAuthenticationEntryPoint().commence(request, response, exception));
      mvc = MockMvcBuilders.standaloneSetup(new Object()).addFilters(filter).build();
    }

    private int statusOf(Jwt jwt) throws Exception {
      when(decoder.decode(TOKEN)).thenReturn(jwt);
      return mvc.perform(get("/anything").header("Authorization", "Bearer " + TOKEN))
          .andReturn()
          .getResponse()
          .getStatus();
    }

    @Test
    void a_known_jwt_publishes_sign_in_succeeded() throws Exception {
      JwtCredential credential = JwtCredential.create(ISSUER, SUBJECT);
      Identity identity = Identity.create();
      when(repository.findByIssuerAndSubject(ISSUER, SUBJECT)).thenReturn(Optional.of(credential));
      when(whoService.resolveDetailed(credential))
          .thenReturn(new Resolved(new WhoPrincipal(identity, Set.of())));

      // 404 from the empty app: the filter let the request through, so the sign-in succeeded
      assertThat(statusOf(jwt(Map.of("iss", ISSUER, "sub", SUBJECT)))).isEqualTo(404);

      // one per request: the chain calls the converter once
      assertThat(events)
          .containsExactly(
              new SignInSucceeded(NOW, SignInMethod.JWT, identity.id(), credential.id()));
    }

    @Test
    void the_token_carries_the_jwt_method_and_credential_id() {
      JwtCredential credential = JwtCredential.create(ISSUER, SUBJECT);
      WhoPrincipal principal = new WhoPrincipal(Identity.create(), Set.of());
      when(repository.findByIssuerAndSubject(ISSUER, SUBJECT)).thenReturn(Optional.of(credential));
      when(whoService.resolveDetailed(credential)).thenReturn(new Resolved(principal));

      WhoAuthenticationToken token =
          (WhoAuthenticationToken)
              new WhoJwtAuthenticationConverter(repository, whoService, events::add, CLOCK)
                  .convert(jwt(Map.of("iss", ISSUER, "sub", SUBJECT)));

      assertThat(token.signInMethod()).contains(SignInMethod.JWT);
      assertThat(token.credentialId()).contains(credential.id());
    }

    @Test
    void missing_sub_publishes_missing_claims() throws Exception {
      assertThat(statusOf(jwt(Map.of("iss", ISSUER)))).isEqualTo(401);

      assertThat(events)
          .containsExactly(
              new SignInFailed(
                  NOW, SignInMethod.JWT, SignInFailureReason.MISSING_CLAIMS, null, null, null));
    }

    @Test
    void missing_iss_publishes_missing_claims() throws Exception {
      assertThat(statusOf(jwt(Map.of("sub", SUBJECT)))).isEqualTo(401);

      assertThat(events)
          .containsExactly(
              new SignInFailed(
                  NOW, SignInMethod.JWT, SignInFailureReason.MISSING_CLAIMS, null, null, null));
    }

    @Test
    void a_missing_resolution_is_treated_as_not_linked() {
      JwtCredential credential = JwtCredential.create(ISSUER, SUBJECT);
      when(repository.findByIssuerAndSubject(ISSUER, SUBJECT)).thenReturn(Optional.of(credential));

      // the mocked WhoService stubs nothing, so resolveDetailed answers null
      assertRefused(SignInFailureReason.UNKNOWN_CREDENTIAL, null, credential.id());
    }

    @Test
    void an_unknown_subject_publishes_unknown_credential() throws Exception {
      when(repository.findByIssuerAndSubject(ISSUER, SUBJECT)).thenReturn(Optional.empty());

      assertThat(statusOf(jwt(Map.of("iss", ISSUER, "sub", SUBJECT)))).isEqualTo(401);

      assertThat(events)
          .containsExactly(
              new SignInFailed(
                  NOW, SignInMethod.JWT, SignInFailureReason.UNKNOWN_CREDENTIAL, null, null, null));
      assertThat(events).noneMatch(event -> event.toString().contains(SUBJECT));
    }

    @Test
    void an_unlinked_credential_publishes_unknown_credential_with_its_id() {
      JwtCredential credential = JwtCredential.create(ISSUER, SUBJECT);
      when(repository.findByIssuerAndSubject(ISSUER, SUBJECT)).thenReturn(Optional.of(credential));
      when(whoService.resolveDetailed(credential)).thenReturn(new NotLinked());

      assertRefused(SignInFailureReason.UNKNOWN_CREDENTIAL, null, credential.id());
    }

    @Test
    void a_credential_whose_identity_is_gone_publishes_unknown_credential_with_both_ids() {
      JwtCredential credential = JwtCredential.create(ISSUER, SUBJECT);
      UUID identityId = UUID.randomUUID();
      when(repository.findByIssuerAndSubject(ISSUER, SUBJECT)).thenReturn(Optional.of(credential));
      when(whoService.resolveDetailed(credential)).thenReturn(new NoIdentity(identityId));

      assertRefused(SignInFailureReason.UNKNOWN_CREDENTIAL, identityId, credential.id());
    }

    @Test
    void an_inactive_identity_publishes_inactive_identity() throws Exception {
      JwtCredential credential = JwtCredential.create(ISSUER, SUBJECT);
      UUID identityId = UUID.randomUUID();
      when(repository.findByIssuerAndSubject(ISSUER, SUBJECT)).thenReturn(Optional.of(credential));
      when(whoService.resolveDetailed(credential)).thenReturn(new Inactive(identityId));

      assertThat(statusOf(jwt(Map.of("iss", ISSUER, "sub", SUBJECT)))).isEqualTo(401);

      assertThat(events)
          .containsExactly(
              new SignInFailed(
                  NOW,
                  SignInMethod.JWT,
                  SignInFailureReason.INACTIVE_IDENTITY,
                  null,
                  identityId,
                  credential.id()));
    }

    @Test
    void the_token_appears_in_no_event() throws Exception {
      JwtCredential credential = JwtCredential.create(ISSUER, SUBJECT);
      when(repository.findByIssuerAndSubject(ISSUER, SUBJECT)).thenReturn(Optional.of(credential));
      when(whoService.resolveDetailed(credential))
          .thenReturn(new Resolved(new WhoPrincipal(Identity.create(), Set.of())));

      statusOf(jwt(Map.of("iss", ISSUER, "sub", SUBJECT)));

      assertThat(events).hasSize(1).noneMatch(event -> event.toString().contains(TOKEN));
    }

    private void assertRefused(SignInFailureReason reason, UUID identityId, UUID credentialId) {
      WhoJwtAuthenticationConverter converter =
          new WhoJwtAuthenticationConverter(repository, whoService, events::add, CLOCK);
      Jwt jwt = jwt(Map.of("iss", ISSUER, "sub", SUBJECT));

      assertThatExceptionOfType(BadCredentialsException.class)
          .isThrownBy(() -> converter.convert(jwt))
          .withMessage("Unknown JWT credential");

      assertThat(events)
          .containsExactly(
              new SignInFailed(NOW, SignInMethod.JWT, reason, null, identityId, credentialId));
    }
  }

  private static Jwt jwt(Map<String, Object> claims) {
    return new Jwt(TOKEN, NOW, NOW.plusSeconds(3600), Map.of("alg", "RS256"), claims);
  }
}
