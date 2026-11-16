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
package org.jwcarman.who.examples.spa;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.emaillink.EmailCredential;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.examples.spa.Providers.Provider;
import org.jwcarman.who.federated.FederatedCredential;
import org.jwcarman.who.federated.FederatedCredentialRepository;
import org.jwcarman.who.federated.FederatedCredentialService;
import org.jwcarman.who.passkey.PasskeyService;
import org.jwcarman.who.passkey.PasskeySummary;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who is signed in, and their ways in. Signed out, who's chain answers {@code 401} before this
 * runs.
 */
@RestController
class MeController {

  /**
   * The signed-in person.
   *
   * @param identityId their identity
   * @param permissions what they may do, for showing and hiding parts of the page; the server
   *     checks again on every call
   * @param emails their email addresses
   * @param passkeys their passkeys
   * @param accounts their linked provider accounts
   */
  record Me(
      UUID identityId,
      Set<String> permissions,
      List<EmailCredential> emails,
      List<PasskeySummary> passkeys,
      List<FederatedCredential> accounts) {}

  private final CredentialIdentityRepository credentialIdentities;
  private final EmailCredentialService emailCredentials;
  private final FederatedCredentialRepository federatedCredentials;
  private final FederatedCredentialService federatedCredentialService;
  private final PasskeyService passkeys;
  private final Providers providers;

  MeController(
      CredentialIdentityRepository credentialIdentities,
      EmailCredentialService emailCredentials,
      FederatedCredentialRepository federatedCredentials,
      FederatedCredentialService federatedCredentialService,
      PasskeyService passkeys,
      Providers providers) {
    this.credentialIdentities = credentialIdentities;
    this.emailCredentials = emailCredentials;
    this.federatedCredentials = federatedCredentials;
    this.federatedCredentialService = federatedCredentialService;
    this.passkeys = passkeys;
    this.providers = providers;
  }

  @GetMapping("/api/me")
  Me me(@AuthenticationPrincipal WhoPrincipal principal) {
    UUID identityId = principal.identity().id();
    List<EmailCredential> emails = new ArrayList<>();
    List<FederatedCredential> accounts = new ArrayList<>();
    for (UUID credentialId : credentialIdentities.findCredentialIdsByIdentityId(identityId)) {
      emailCredentials.findById(credentialId).ifPresent(emails::add);
      federatedCredentials.findById(credentialId).ifPresent(accounts::add);
    }
    return new Me(identityId, principal.permissions(), emails, passkeys.list(identityId), accounts);
  }

  /** The providers to show a button for. Public: the sign-in page needs them. */
  @GetMapping("/api/providers")
  List<Provider> providers() {
    return providers.all();
  }

  /**
   * Unlinks a provider account. {@link FederatedCredentialService#unlink} checks no ownership, so
   * the app checks that the account is the caller's before calling it.
   */
  @DeleteMapping("/api/me/accounts/{id}")
  ResponseEntity<Void> unlink(
      @PathVariable UUID id, @AuthenticationPrincipal WhoPrincipal principal) {
    boolean owned =
        credentialIdentities
            .findIdentityIdByCredentialId(id)
            .filter(principal.identity().id()::equals)
            .isPresent();
    if (!owned || federatedCredentials.findById(id).isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    federatedCredentialService.unlink(id);
    return ResponseEntity.noContent().build();
  }
}
