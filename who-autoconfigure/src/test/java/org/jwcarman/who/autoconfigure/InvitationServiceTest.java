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
package org.jwcarman.who.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.config.Customizer.withDefaults;

import java.io.Serial;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.admin.WhoAdminPermissions;
import org.jwcarman.who.apikey.ApiKeyService;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.emaillink.EmailAddressTakenException;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.emaillink.EmailLinkMessage;
import org.jwcarman.who.emaillink.EmailLinkOutcome;
import org.jwcarman.who.emaillink.IdentityInvited;
import org.jwcarman.who.emaillink.InvalidInvitationException;
import org.jwcarman.who.emaillink.Invitation;
import org.jwcarman.who.emaillink.InvitationService;
import org.jwcarman.who.emaillink.WhoEmailSender;
import org.jwcarman.who.federated.FederatedCredentialService;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.rbac.Role;
import org.jwcarman.who.spring.security.WhoPublicPaths;
import org.jwcarman.who.spring.security.WhoSecurity;
import org.jwcarman.who.spring.security.WhoWebSecurity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.ResponseStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link InvitationService} and {@code POST /who/invitations} against PostgreSQL: one transaction
 * for the identity, its address, its roles and {@link IdentityInvited}, and the link only after it
 * commits.
 */
@SpringBootTest(
    classes = InvitationServiceTest.App.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "who.initialize-schema=always",
      "who.email-link.link-base-url=https://app.example.com/signin",
      "who.email-link.code-secret=test-code-secret-0123456789-abcdefghij",
      "who.passkey.rp-id=localhost",
      "who.passkey.allowed-origins=http://localhost",
      "spring.session.jdbc.initialize-schema=always"
    })
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class InvitationServiceTest {

  static final String ISSUER = "https://issuer.example.com";
  static final String ALICE_TOKEN = "alice-token";
  static final String ALICE = "alice@example.com";
  static final String CAROL = "carol@example.com";
  static final String ADMIN_ROLE = "invitation-test-admin";
  static final String EDITOR_ROLE = "invitation-test-editor";
  static final String MEMBER_ROLE = "invitation-test-member";
  static final String TASK_WRITE = "task.write";
  static final JsonMapper JSON = new JsonMapper();

  /** Each sent link, with whether its identity was committed when it was sent. */
  static final List<Sent> SENT = new CopyOnWriteArrayList<>();

  /** Each {@link IdentityInvited} heard, with whether it was heard inside a transaction. */
  static final List<Heard> HEARD = new CopyOnWriteArrayList<>();

  /** When set, the listener throws. */
  static final AtomicBoolean LISTENER_THROWS = new AtomicBoolean();

  /** When set, the listener throws this. */
  static final AtomicReference<RuntimeException> LISTENER_FAILURE = new AtomicReference<>();

  /** What an application's listener might throw to refuse an invitation, with its own status. */
  @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
  static final class Unprocessable extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;
  }

  record Sent(EmailLinkMessage message, boolean identityCommitted, boolean inTransaction) {}

  record Heard(IdentityInvited event, boolean inTransaction) {}

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", EmailLinkSignInTest.POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", EmailLinkSignInTest.POSTGRES::getUsername);
    registry.add("spring.datasource.password", EmailLinkSignInTest.POSTGRES::getPassword);
  }

  /** Hears {@link IdentityInvited} as an application would, and throws when told to. */
  static class InvitedListener {

    @EventListener
    void on(IdentityInvited event) {
      HEARD.add(new Heard(event, TransactionSynchronizationManager.isActualTransactionActive()));
      if (LISTENER_THROWS.get()) {
        throw new IllegalStateException("the application refuses");
      }
      RuntimeException failure = LISTENER_FAILURE.get();
      if (failure != null) {
        throw failure;
      }
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class App {

    @Bean
    WhoEmailSender whoEmailSender(JdbcClient jdbcClient) {
      return email -> {
        if (email instanceof EmailLinkMessage message) {
          // this connection sees only what has committed, as the link must be sent with no
          // transaction open
          long identities =
              jdbcClient
                  .sql(
                      "SELECT COUNT(*) FROM who_email_credential c JOIN who_credential_identity l"
                          + " ON l.credential_id = c.id WHERE c.address = :address")
                  .param("address", message.address())
                  .query(Long.class)
                  .single();
          SENT.add(
              new Sent(
                  message,
                  identities == 1,
                  TransactionSynchronizationManager.isActualTransactionActive()));
        }
      };
    }

    @Bean
    InvitedListener invitedListener() {
      return new InvitedListener();
    }

    @Bean
    Executor whoEmailLinkExecutor() {
      return Runnable::run;
    }

    @Bean
    JwtDecoder jwtDecoder() {
      return token -> {
        if (!ALICE_TOKEN.equals(token)) {
          throw new BadJwtException("not a good token");
        }
        Instant now = Instant.now();
        return new Jwt(
            token,
            now,
            now.plusSeconds(60),
            Map.of("alg", "none"),
            Map.of("iss", ISSUER, "sub", "alice"));
      };
    }

    /** A request that carries its own credential goes to the stateless chain. */
    @Bean
    @Order(1)
    SecurityFilterChain api(HttpSecurity http) {
      http.securityMatcher(
              request ->
                  request.getHeader("Authorization") != null
                      || request.getHeader("X-API-Key") != null)
          .with(WhoSecurity.stateless(), withDefaults())
          .authorizeHttpRequests(a -> a.anyRequest().authenticated());
      return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain web(HttpSecurity http, WhoPublicPaths publicPaths) {
      http.with(WhoWebSecurity.passwordless(), withDefaults())
          .authorizeHttpRequests(
              a ->
                  a.requestMatchers(publicPaths.patterns())
                      .permitAll()
                      .anyRequest()
                      .authenticated());
      return http.build();
    }
  }

  @LocalServerPort private int port;
  @Autowired private InvitationService invitationService;
  @Autowired private WhoService whoService;
  @Autowired private RbacService rbacService;
  @Autowired private ApiKeyService apiKeyService;
  @Autowired private FederatedCredentialService federatedCredentialService;
  @Autowired private EmailCredentialService emailCredentialService;
  @Autowired private JdbcClient jdbcClient;

  private Role editorRole;
  private Identity alice;

  private Role role(String name, String... permissions) {
    Role role = rbacService.findRole(name).orElseGet(() -> rbacService.createRole(name));
    for (String permission : permissions) {
      rbacService.addPermissionToRole(role, permission);
    }
    return role;
  }

  @BeforeEach
  void setUp() {
    jdbcClient.sql("DELETE FROM who_identity").update();
    jdbcClient.sql("DELETE FROM who_federated_credential").update();
    jdbcClient.sql("DELETE FROM who_email_credential").update();
    Role adminRole =
        role(
            ADMIN_ROLE, WhoAdminPermissions.IDENTITIES_READ, WhoAdminPermissions.IDENTITIES_MANAGE);
    editorRole = role(EDITOR_ROLE, TASK_WRITE);
    role(MEMBER_ROLE);
    alice = whoService.createIdentity();
    rbacService.assignRoleToIdentity(alice, adminRole);
    federatedCredentialService.link(alice, ISSUER, "alice");
    emailCredentialService.register(alice, ALICE);
    SENT.clear();
    HEARD.clear();
    LISTENER_THROWS.set(false);
    LISTENER_FAILURE.set(null);
  }

  private long identities() {
    return jdbcClient.sql("SELECT COUNT(*) FROM who_identity").query(Long.class).single();
  }

  private boolean registered(String address) {
    return emailCredentialService.findByAddress(address).isPresent();
  }

  @Nested
  class The_service {

    @Test
    void invites_and_sends_a_link_after_commit() {
      Invitation invitation = invitationService.invite("Carol@Example.com", Set.of(EDITOR_ROLE));

      assertThat(invitation.linkOutcome()).isEqualTo(EmailLinkOutcome.SENT);
      assertThat(invitation.linkSent()).isTrue();
      UUID id = invitation.identityId();
      assertThat(whoService.resolve(emailCredentialService.findByAddress(CAROL).orElseThrow()))
          .hasValueSatisfying(p -> assertThat(p.identity().id()).isEqualTo(id));
      assertThat(rbacService.findRolesForIdentity(id)).containsExactly(editorRole);
      assertThat(HEARD)
          .singleElement()
          .satisfies(
              heard -> {
                assertThat(heard.inTransaction()).isTrue();
                assertThat(heard.event().identityId()).isEqualTo(id);
                assertThat(heard.event().address()).isEqualTo(CAROL);
                assertThat(heard.event().roles()).containsExactly(EDITOR_ROLE);
              });
      assertThat(SENT)
          .singleElement()
          .satisfies(
              sent -> {
                assertThat(sent.message().address()).isEqualTo(CAROL);
                assertThat(sent.identityCommitted()).isTrue();
              });
    }

    @Test
    void sends_the_link_with_no_transaction_open() {
      invitationService.invite("Carol@Example.com", Set.of());

      assertThat(SENT)
          .singleElement()
          .satisfies(sent -> assertThat(sent.inTransaction()).isFalse());
    }

    @Test
    void taken_address_creates_nothing() {
      long before = identities();
      Set<String> editor = Set.of(EDITOR_ROLE);

      assertThatThrownBy(() -> invitationService.invite("ALICE@example.com", editor))
          .isInstanceOf(EmailAddressTakenException.class);
      assertThat(identities()).isEqualTo(before);
      assertThat(rbacService.findIdentityIdsWithRole(editorRole)).isEmpty();
      assertThat(HEARD).isEmpty();
      assertThat(SENT).isEmpty();
    }

    @Test
    void unknown_role_creates_nothing() {
      long before = identities();
      Set<String> roles = Set.of(EDITOR_ROLE, "no-such-role");

      assertThatThrownBy(() -> invitationService.invite(CAROL, roles))
          .isInstanceOf(InvalidInvitationException.class);
      assertThat(identities()).isEqualTo(before);
      assertThat(registered(CAROL)).isFalse();
      assertThat(HEARD).isEmpty();
      assertThat(SENT).isEmpty();
    }

    @Test
    void listener_rollback_rolls_back_the_invitation() {
      LISTENER_THROWS.set(true);
      long before = identities();
      Set<String> editor = Set.of(EDITOR_ROLE);

      assertThatThrownBy(() -> invitationService.invite(CAROL, editor))
          .isInstanceOf(IllegalStateException.class);
      assertThat(HEARD).hasSize(1);
      assertThat(identities()).isEqualTo(before);
      assertThat(registered(CAROL)).isFalse();
      assertThat(rbacService.findIdentityIdsWithRole(editorRole)).isEmpty();
      assertThat(SENT).isEmpty();
    }
  }

  @Nested
  class The_endpoint {

    private HttpResponse<String> post(String header, String value, String body) throws Exception {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/who/invitations"))
              .header(header, value)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> asAlice(String body) throws Exception {
      return post("Authorization", "Bearer " + ALICE_TOKEN, body);
    }

    private String key(String... scopes) {
      return apiKeyService.create(alice, "inviter", Duration.ofDays(1), Set.of(scopes)).key();
    }

    private static String body(String email, String role) {
      return "{\"email\":\"" + email + "\",\"roles\":[\"" + role + "\"]}";
    }

    @Test
    void invites_with_a_role_the_caller_holds() throws Exception {
      rbacService.assignRoleToIdentity(alice, editorRole);

      HttpResponse<String> response = asAlice(body(CAROL, EDITOR_ROLE));

      assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
      JsonNode json = JSON.readTree(response.body());
      assertThat(json.get("linkSent").asBoolean()).isTrue();
      UUID id = UUID.fromString(json.get("identityId").asString());
      assertThat(rbacService.findRolesForIdentity(id)).containsExactly(editorRole);
      assertThat(SENT)
          .singleElement()
          .satisfies(s -> assertThat(s.message().address()).isEqualTo(CAROL));
    }

    @Test
    void a_listener_refusal_with_its_own_status_is_answered_with_it_and_creates_nothing()
        throws Exception {
      LISTENER_FAILURE.set(new Unprocessable());
      long before = identities();

      HttpResponse<String> response = asAlice(body(CAROL, MEMBER_ROLE));

      assertThat(response.statusCode()).as(response.body()).isEqualTo(422);
      assertThat(identities()).isEqualTo(before);
      assertThat(registered(CAROL)).isFalse();
      assertThat(SENT).isEmpty();
    }

    @Test
    void a_listener_failure_is_a_server_error_and_creates_nothing() throws Exception {
      LISTENER_FAILURE.set(new IllegalStateException("the listener failed"));
      long before = identities();

      HttpResponse<String> response = asAlice(body(CAROL, MEMBER_ROLE));

      assertThat(response.statusCode()).isEqualTo(500);
      assertThat(identities()).isEqualTo(before);
      assertThat(registered(CAROL)).isFalse();
      assertThat(SENT).isEmpty();
    }

    @Test
    void a_taken_address_is_a_conflict() throws Exception {
      long before = identities();

      HttpResponse<String> response = asAlice(body(ALICE, MEMBER_ROLE));

      assertThat(response.statusCode()).isEqualTo(409);
      assertThat(response.headers().firstValue("Who-Error")).contains("email-address-taken");
      assertThat(identities()).isEqualTo(before);
    }

    @Test
    void an_unknown_role_is_bad_request() throws Exception {
      long before = identities();

      HttpResponse<String> response = asAlice(body(CAROL, "no-such-role"));

      assertThat(response.statusCode()).isEqualTo(400);
      assertThat(response.headers().firstValue("Who-Error")).contains("invalid-invitation");
      assertThat(identities()).isEqualTo(before);
    }

    @Test
    void a_role_beyond_the_callers_permissions_is_forbidden() throws Exception {
      long before = identities();

      HttpResponse<String> response = asAlice(body(CAROL, EDITOR_ROLE));

      assertThat(response.statusCode()).isEqualTo(403);
      assertThat(response.headers().firstValue("Who-Error")).contains("role-grant-forbidden");
      assertThat(identities()).isEqualTo(before);
      assertThat(registered(CAROL)).isFalse();
      assertThat(SENT).isEmpty();
    }

    @Test
    void a_scoped_api_key_may_invite() throws Exception {
      String key = key(WhoAdminPermissions.IDENTITIES_MANAGE);

      HttpResponse<String> response = post("X-API-Key", key, body(CAROL, MEMBER_ROLE));

      assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
      assertThat(registered(CAROL)).isTrue();
    }

    @Test
    void an_api_key_grants_only_within_its_scopes() throws Exception {
      // alice holds the editor role's permission; her key is scoped to manage alone
      rbacService.assignRoleToIdentity(alice, editorRole);
      String key = key(WhoAdminPermissions.IDENTITIES_MANAGE);

      HttpResponse<String> response = post("X-API-Key", key, body(CAROL, EDITOR_ROLE));

      assertThat(response.statusCode()).isEqualTo(403);
      assertThat(response.headers().firstValue("Who-Error")).contains("role-grant-forbidden");
      assertThat(registered(CAROL)).isFalse();
    }

    @Test
    void without_manage_permission_is_forbidden() throws Exception {
      String key = key(WhoAdminPermissions.IDENTITIES_READ);

      HttpResponse<String> response = post("X-API-Key", key, body(CAROL, MEMBER_ROLE));

      assertThat(response.statusCode()).isEqualTo(403);
      assertThat(response.headers().firstValue("Who-Error")).contains("permission-required");
      assertThat(registered(CAROL)).isFalse();
    }
  }
}
