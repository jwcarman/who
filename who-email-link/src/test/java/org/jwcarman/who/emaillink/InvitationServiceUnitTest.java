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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.UndeclaredThrowableException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.service.WhoService;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** {@link InvitationService} without a database: its checks, its event and the link's fate. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@ExtendWith(OutputCaptureExtension.class)
class InvitationServiceUnitTest {

  private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
  private static final String ADDRESS = "Carol@Example.com";

  private final WhoService whoService = mock(WhoService.class);
  private final EmailCredentialService credentials = mock(EmailCredentialService.class);
  private final EmailLinkIssuer issuer = mock(EmailLinkIssuer.class);
  private final InvitationRoles roles = mock(InvitationRoles.class);
  private final List<Object> published = new ArrayList<>();
  private final Identity carol = Identity.create();

  @BeforeEach
  void setUp() {
    when(whoService.createIdentity()).thenReturn(carol);
    when(credentials.register(eq(carol), anyString()))
        .thenAnswer(call -> EmailCredential.create(call.getArgument(1)));
    when(issuer.issue(anyString(), anyString(), any())).thenReturn(EmailLinkOutcome.SENT);
  }

  private InvitationService service(InvitationRoles withRoles) {
    return new InvitationService(
        whoService,
        credentials,
        withRoles,
        issuer,
        published::add,
        mock(PlatformTransactionManager.class),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Nested
  class With_roles {

    @Test
    void creates_registers_assigns_publishes_then_sends() {
      Invitation invitation = service(roles).invite(ADDRESS, Set.of("editor"));

      assertThat(invitation.identityId()).isEqualTo(carol.id());
      assertThat(invitation.linkOutcome()).isEqualTo(EmailLinkOutcome.SENT);
      assertThat(invitation.linkSent()).isTrue();
      verify(roles).assign(carol, Set.of("editor"));
      verify(issuer).issue(eq("carol@example.com"), anyString(), any());
      assertThat(published)
          .containsExactly(
              new IdentityInvited(NOW, carol.id(), "carol@example.com", Set.of("editor")));
    }

    @Test
    void an_unknown_role_sends_nothing() {
      doThrow(new InvalidInvitationException("No such role")).when(roles).assign(any(), any());

      InvitationService service = service(roles);
      Set<String> nope = Set.of("nope");

      assertThatThrownBy(() -> service.invite(ADDRESS, nope))
          .isInstanceOf(InvalidInvitationException.class);
      assertThat(published).isEmpty();
      verifyNoInteractions(issuer);
    }
  }

  @Nested
  class Unique_keys {

    @Test
    void the_address_taken_by_a_concurrent_registration_is_a_taken_address() {
      when(credentials.register(eq(carol), anyString()))
          .thenThrow(new DuplicateKeyException("who_email_credential_address_key"));
      InvitationService service = service(roles);
      Set<String> none = Set.of();

      assertThatThrownBy(() -> service.invite(ADDRESS, none))
          .isInstanceOf(EmailAddressTakenException.class);
      assertThat(published).isEmpty();
      verifyNoInteractions(issuer);
    }

    @Test
    void a_listeners_own_unique_key_violation_is_not_a_taken_address() {
      DuplicateKeyException listeners = new DuplicateKeyException("app_profile_key");
      InvitationService service =
          new InvitationService(
              whoService,
              credentials,
              roles,
              issuer,
              event -> {
                throw listeners;
              },
              mock(PlatformTransactionManager.class),
              Clock.fixed(NOW, ZoneOffset.UTC));
      Set<String> none = Set.of();

      assertThatThrownBy(() -> service.invite(ADDRESS, none)).isSameAs(listeners);
      verifyNoInteractions(issuer);
    }
  }

  @Nested
  class Without_rbac {

    @Test
    void invites_with_no_roles() {
      Invitation invitation = service(null).invite(ADDRESS, Set.of());

      assertThat(invitation.identityId()).isEqualTo(carol.id());
      assertThat(invitation.linkSent()).isTrue();
      assertThat(published)
          .containsExactly(new IdentityInvited(NOW, carol.id(), "carol@example.com", Set.of()));
    }

    @Test
    void refuses_roles_before_creating_anything() {
      InvitationService service = service(null);
      Set<String> editor = Set.of("editor");

      assertThatThrownBy(() -> service.invite(ADDRESS, editor))
          .isInstanceOf(InvalidInvitationException.class)
          .extracting(e -> ((InvalidInvitationException) e).errorCode())
          .isEqualTo("invalid-request");
      verifyNoInteractions(whoService, credentials, issuer);
      assertThat(published).isEmpty();
    }
  }

  @Nested
  class The_link {

    @Test
    void a_throwing_issuer_leaves_the_invitation_standing(CapturedOutput output) {
      when(issuer.issue(anyString(), anyString(), any()))
          .thenThrow(new IllegalStateException(ADDRESS));

      Invitation invitation = service(roles).invite(ADDRESS, Set.of());

      assertThat(invitation.identityId()).isEqualTo(carol.id());
      assertThat(invitation.linkOutcome()).isEqualTo(EmailLinkOutcome.SEND_FAILED);
      assertThat(invitation.linkSent()).isFalse();
      assertThat(output).contains("WARN").doesNotContainIgnoringCase(ADDRESS);
    }

    @Test
    void an_unsent_link_is_not_sent(CapturedOutput output) {
      when(issuer.issue(anyString(), anyString(), any()))
          .thenReturn(EmailLinkOutcome.RESEND_SUPPRESSED);

      Invitation invitation = service(roles).invite(ADDRESS, Set.of());

      assertThat(invitation.linkOutcome()).isEqualTo(EmailLinkOutcome.RESEND_SUPPRESSED);
      assertThat(invitation.linkSent()).isFalse();
      assertThat(output).contains("WARN").doesNotContainIgnoringCase(ADDRESS);
    }
  }

  /** A callback a mock can make throw a checked exception, as a sneaky throw would. */
  interface IdentityCallback extends Consumer<Identity> {}

  @Nested
  class The_callback {

    private static final ScopedValue<String> CALLER = ScopedValue.newInstance();

    @Test
    void receives_the_new_identity_and_the_invitation_returns_after_the_send() {
      List<Identity> received = new ArrayList<>();

      Invitation invitation = service(roles).invite(ADDRESS, Set.of("editor"), received::add);

      assertThat(received).containsExactly(carol);
      assertThat(invitation.identityId()).isEqualTo(carol.id());
      assertThat(invitation.linkOutcome()).isEqualTo(EmailLinkOutcome.SENT);
      verify(issuer).issue(eq("carol@example.com"), anyString(), any());
    }

    @Test
    void runs_after_the_roles_are_assigned_and_before_the_event_is_published() {
      List<String> order = new ArrayList<>();
      doAnswer(
              call -> {
                order.add("roles");
                return null;
              })
          .when(roles)
          .assign(any(), any());
      InvitationService service =
          new InvitationService(
              whoService,
              credentials,
              roles,
              issuer,
              event -> order.add("event"),
              mock(PlatformTransactionManager.class),
              Clock.fixed(NOW, ZoneOffset.UTC));

      service.invite(ADDRESS, Set.of("editor"), _ -> order.add("callback"));

      assertThat(order).containsExactly("roles", "callback", "event");
    }

    @Test
    void runs_on_the_callers_thread_and_sees_its_bound_state() {
      AtomicReference<Thread> ran = new AtomicReference<>();
      AtomicReference<String> seen = new AtomicReference<>();
      InvitationService service = service(roles);
      Set<String> none = Set.of();

      ScopedValue.where(CALLER, "request-42")
          .run(
              () ->
                  service.invite(
                      ADDRESS,
                      none,
                      _ -> {
                        ran.set(Thread.currentThread());
                        seen.set(CALLER.orElse("lost"));
                      }));

      assertThat(ran.get()).isSameAs(Thread.currentThread());
      assertThat(seen.get()).isEqualTo("request-42");
    }

    @Test
    void a_throwing_callback_propagates_unchanged_and_sends_and_publishes_nothing() {
      IllegalStateException failure = new IllegalStateException("the app refuses");
      InvitationService service = service(roles);
      Set<String> none = Set.of();

      assertThatThrownBy(
              () ->
                  service.invite(
                      ADDRESS,
                      none,
                      _ -> {
                        throw failure;
                      }))
          .isSameAs(failure);
      assertThat(published).isEmpty();
      verifyNoInteractions(issuer);
    }

    @Test
    void a_sneaked_checked_exception_arrives_wrapped_and_sends_nothing() {
      Exception checked = new Exception("checked");
      IdentityCallback callback = mock(IdentityCallback.class);
      doAnswer(
              _ -> {
                throw checked;
              })
          .when(callback)
          .accept(any());
      InvitationService service = service(roles);
      Set<String> none = Set.of();

      assertThatThrownBy(() -> service.invite(ADDRESS, none, callback))
          .isInstanceOf(UndeclaredThrowableException.class)
          .hasCause(checked);
      assertThat(published).isEmpty();
      verifyNoInteractions(issuer);
    }

    @Test
    void a_callbacks_unique_key_violation_is_not_a_taken_address() {
      DuplicateKeyException callbacks = new DuplicateKeyException("app_profile_key");
      InvitationService service = service(roles);
      Set<String> none = Set.of();

      assertThatThrownBy(
              () ->
                  service.invite(
                      ADDRESS,
                      none,
                      _ -> {
                        throw callbacks;
                      }))
          .isSameAs(callbacks);
      verifyNoInteractions(issuer);
    }

    @Test
    void a_missing_callback_is_refused() {
      InvitationService service = service(roles);
      Set<String> none = Set.of();

      assertThatThrownBy(() -> service.invite(ADDRESS, none, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("inTransaction must not be null");
    }

    @Test
    void a_refused_invitation_never_runs_it() {
      InvitationService service = service(roles);
      Set<String> none = Set.of();
      AtomicBoolean ran = new AtomicBoolean();

      assertThatThrownBy(() -> service.invite("not-an-address", none, _ -> ran.set(true)))
          .isInstanceOf(InvalidInvitationException.class);
      TransactionSynchronizationManager.setActualTransactionActive(true);
      try {
        assertThatThrownBy(() -> service.invite(ADDRESS, none, _ -> ran.set(true)))
            .isInstanceOf(IllegalStateException.class);
      } finally {
        TransactionSynchronizationManager.setActualTransactionActive(false);
      }
      assertThat(ran).isFalse();
    }
  }

  @Nested
  class Refusals {

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "not-an-address", "a@b\u0000c"})
    void a_malformed_address_creates_nothing(String address) {
      InvitationService service = service(roles);
      Set<String> none = Set.of();

      assertThatThrownBy(() -> service.invite(address, none))
          .isInstanceOf(InvalidInvitationException.class);
      verifyNoInteractions(whoService, credentials, issuer);
    }

    @Test
    void a_missing_address_creates_nothing() {
      InvitationService service = service(roles);
      Set<String> none = Set.of();

      assertThatThrownBy(() -> service.invite(null, none))
          .isInstanceOf(InvalidInvitationException.class);
      verifyNoInteractions(whoService);
    }

    @Test
    void a_blank_role_name_creates_nothing() {
      InvitationService service = service(roles);
      Set<String> blank = Set.of(" ");

      assertThatThrownBy(() -> service.invite(ADDRESS, blank))
          .isInstanceOf(InvalidInvitationException.class);
      verifyNoInteractions(whoService);
    }

    @Test
    void a_taken_address_sends_nothing() {
      when(credentials.register(any(), anyString())).thenThrow(new EmailAddressTakenException());
      InvitationService service = service(roles);
      Set<String> none = Set.of();

      assertThatThrownBy(() -> service.invite(ADDRESS, none))
          .isInstanceOf(EmailAddressTakenException.class);
      assertThat(published).isEmpty();
      verify(issuer, never()).issue(anyString(), anyString(), any());
    }

    @Test
    void refuses_to_run_inside_a_transaction() {
      InvitationService service = service(roles);
      Set<String> none = Set.of();
      TransactionSynchronizationManager.setActualTransactionActive(true);
      try {
        assertThatThrownBy(() -> service.invite(ADDRESS, none))
            .isInstanceOf(IllegalStateException.class);
      } finally {
        TransactionSynchronizationManager.setActualTransactionActive(false);
      }
      verifyNoInteractions(whoService, issuer);
    }
  }

  @Test
  void the_event_copies_its_roles() {
    Set<String> held = new HashSet<>(Set.of("editor"));
    IdentityInvited event = new IdentityInvited(NOW, UUID.randomUUID(), ADDRESS, held);
    held.add("admin");

    assertThat(event.roles()).containsExactly("editor");
  }
}
