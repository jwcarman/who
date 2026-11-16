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
package org.jwcarman.who.examples.webapp;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.emaillink.EmailCredential;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.federated.FederatedCredential;
import org.jwcarman.who.federated.FederatedCredentialRepository;
import org.jwcarman.who.federated.FederatedCredentialService;
import org.jwcarman.who.passkey.PasskeyService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * The signed-in person's ways in: their email address, their passkeys and the provider accounts
 * linked to them. Adding a passkey, removing one and confirming a link go through {@code who.js};
 * linking starts with a plain link to {@code /who/oidc/authorize/{id}}.
 */
@Controller
class ProfileController {

  private final CredentialIdentityRepository credentialIdentities;
  private final EmailCredentialService emailCredentials;
  private final FederatedCredentialRepository federatedCredentials;
  private final FederatedCredentialService federatedCredentialService;
  private final PasskeyService passkeys;
  private final Providers providers;

  ProfileController(
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

  @GetMapping("/profile")
  String profile(@AuthenticationPrincipal WhoPrincipal principal, Model model) {
    UUID identityId = principal.identity().id();
    List<EmailCredential> emails = new ArrayList<>();
    List<FederatedCredential> accounts = new ArrayList<>();
    for (UUID credentialId : credentialIdentities.findCredentialIdsByIdentityId(identityId)) {
      emailCredentials.findById(credentialId).ifPresent(emails::add);
      federatedCredentials.findById(credentialId).ifPresent(accounts::add);
    }
    model.addAttribute("identityId", identityId);
    model.addAttribute("emails", emails);
    model.addAttribute("accounts", accounts);
    model.addAttribute("passkeys", passkeys.list(identityId));
    model.addAttribute("providers", providers.all());
    return "profile";
  }

  /**
   * Unlinks a provider account. {@link FederatedCredentialService#unlink} checks no ownership, so
   * the app checks that the account is the caller's before calling it.
   */
  @PostMapping("/profile/accounts/{id}/unlink")
  String unlink(@PathVariable UUID id, @AuthenticationPrincipal WhoPrincipal principal) {
    boolean owned =
        credentialIdentities
            .findIdentityIdByCredentialId(id)
            .filter(principal.identity().id()::equals)
            .isPresent();
    if (owned && federatedCredentials.findById(id).isPresent()) {
      federatedCredentialService.unlink(id);
    }
    return "redirect:/profile";
  }
}
