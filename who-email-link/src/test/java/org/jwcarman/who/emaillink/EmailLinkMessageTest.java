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

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class EmailLinkMessageTest {

  @Test
  void to_string_hides_the_link_and_the_code() {
    EmailLinkMessage message =
        new EmailLinkMessage(
            "a@b.c",
            "https://app.test/signin#token=SECRETTOKEN",
            "K7F2QM",
            Instant.parse("2026-11-13T10:00:00Z"));

    assertThat(message.toString())
        .doesNotContain("SECRETTOKEN", "K7F2QM", "https://")
        .contains("a@b.c", "link=****", "code=****");
  }

  @Test
  void an_address_confirmation_hides_the_link_and_the_code() {
    EmailAddressConfirmationMessage message =
        new EmailAddressConfirmationMessage(
            "a@b.c",
            "https://app.test/profile#confirm-address=SECRETTOKEN",
            "K7F2QM",
            Instant.parse("2026-11-13T10:00:00Z"),
            false);

    assertThat(message.toString())
        .doesNotContain("SECRETTOKEN", "K7F2QM", "https://")
        .contains("a@b.c", "link=****", "code=****");
    assertThat(message.formattedCode()).isEqualTo("K7F-2QM");
  }

  @Test
  void a_change_notice_masks_the_new_address() {
    assertThat(new EmailAddressChangedMessage("old@b.c", "newname@example.com").maskedNewAddress())
        .isEqualTo("n***@example.com");
    assertThat(new EmailAddressChangedMessage("old@b.c", "x@example.com").maskedNewAddress())
        .isEqualTo("x***@example.com");
  }

  @Test
  void every_email_names_its_recipient() {
    WhoEmail signIn = new EmailLinkMessage("a@b.c", "l", "K7F2QM", Instant.EPOCH);
    WhoEmail notice = new EmailAddressChangedMessage("old@b.c", "new@b.c");

    assertThat(signIn.address()).isEqualTo("a@b.c");
    assertThat(notice.address()).isEqualTo("old@b.c");
  }

  @Test
  void an_invitation_hides_the_link_and_the_code_and_names_its_roles() {
    InvitationMessage message =
        new InvitationMessage(
            "a@b.c",
            "https://app.test/signin#token=SECRETTOKEN",
            "K7F2QM",
            Instant.parse("2026-11-13T10:00:00Z"),
            Set.of("editor"));

    assertThat(message.toString())
        .doesNotContain("SECRETTOKEN", "K7F2QM", "https://")
        .contains("a@b.c", "link=****", "code=****", "editor");
    assertThat(message.formattedCode()).isEqualTo("K7F-2QM");
    assertThat(message.address()).isEqualTo("a@b.c");
  }

  @Test
  void an_invitation_copies_its_roles() {
    Set<String> roles = new HashSet<>(Set.of("editor"));
    InvitationMessage message = new InvitationMessage("a@b.c", "l", "K7F2QM", Instant.EPOCH, roles);

    roles.add("admin");

    assertThat(message.roles()).containsExactly("editor");
    assertThatThrownBy(() -> message.roles().add("admin"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void an_invitation_needs_every_part() {
    Set<String> none = Set.of();
    assertThatThrownBy(() -> new InvitationMessage(null, "l", "c", Instant.EPOCH, none))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new InvitationMessage("a", null, "c", Instant.EPOCH, none))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new InvitationMessage("a", "l", null, Instant.EPOCH, none))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new InvitationMessage("a", "l", "c", null, none))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new InvitationMessage("a", "l", "c", Instant.EPOCH, null))
        .isInstanceOf(NullPointerException.class);
  }
}
