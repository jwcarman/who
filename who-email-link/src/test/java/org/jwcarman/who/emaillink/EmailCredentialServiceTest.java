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

import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.springframework.beans.factory.annotation.Autowired;

class EmailCredentialServiceTest extends AbstractEmailLinkTest {

  @Autowired private EmailCredentialService service;
  @Autowired private IdentityRepository identityRepository;
  @Autowired private CredentialIdentityRepository credentialIdentityRepository;

  @Test
  void registerLinksTheCredentialToTheIdentity() {
    Identity identity = identityRepository.save(Identity.create());

    EmailCredential credential = service.register(identity, "Owner@Example.com");

    assertThat(credential.address()).isEqualTo("owner@example.com");
    assertThat(credentialIdentityRepository.findIdentityIdByCredentialId(credential.id()))
        .contains(identity.id());
  }

  @Test
  void registeringATakenAddressThrows() {
    Identity identity = identityRepository.save(Identity.create());
    service.register(identity, "taken@example.com");
    Identity other = identityRepository.save(Identity.create());

    assertThatThrownBy(() -> service.register(other, "taken@example.com"))
        .isInstanceOf(EmailAddressTakenException.class)
        .hasMessageNotContaining("taken@example.com");
    assertThatThrownBy(() -> service.register(other, "TAKEN@Example.com"))
        .isInstanceOf(EmailAddressTakenException.class);
  }

  @Test
  void findByAddressNormalizes() {
    Identity identity = identityRepository.save(Identity.create());
    EmailCredential credential = service.register(identity, "find@example.com");

    assertThat(service.findByAddress("  FIND@example.com ")).contains(credential);
  }

  @Test
  void revokeDeletesTheCredentialAndItsLink() {
    Identity identity = identityRepository.save(Identity.create());
    EmailCredential credential = service.register(identity, "gone@example.com");

    service.revoke(credential.id());

    assertThat(service.findByAddress("gone@example.com")).isEmpty();
    assertThat(credentialIdentityRepository.findIdentityIdByCredentialId(credential.id()))
        .isEmpty();
  }
}
