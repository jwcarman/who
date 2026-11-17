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
package org.jwcarman.who.admin;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.emaillink.EmailCredential;
import org.jwcarman.who.emaillink.EmailCredentialService;

/**
 * The addresses of who's email sign-in. An address is matched as email sign-in matches it: trimmed
 * and lower-cased, otherwise exact. An address whose credential no identity holds finds none.
 */
public final class EmailLinkIdentityEmails implements IdentityEmails {

  private final EmailCredentialService credentials;
  private final CredentialIdentityRepository links;

  /**
   * Creates the adapter.
   *
   * @param credentials email sign-in's credentials
   * @param links which identity each credential belongs to
   */
  public EmailLinkIdentityEmails(
      EmailCredentialService credentials, CredentialIdentityRepository links) {
    this.credentials = requireNonNull(credentials, "credentials must not be null");
    this.links = requireNonNull(links, "links must not be null");
  }

  @Override
  public Optional<UUID> findIdentityId(String address) {
    return credentials
        .findByAddress(address)
        .flatMap(credential -> links.findIdentityIdByCredentialId(credential.id()));
  }

  @Override
  public List<String> addressesOf(UUID identityId) {
    return credentials.findAllFor(identityId).stream().map(EmailCredential::address).toList();
  }
}
