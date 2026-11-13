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

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.jwcarman.who.apikey.ApiKeyService;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.emaillink.EmailCredential;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.emaillink.EmailLinkSender;
import org.jwcarman.who.jwt.JwtCredential;
import org.jwcarman.who.jwt.JwtCredentialRepository;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.rbac.Role;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Deleting an identity, and purging credentials left behind, on PostgreSQL with every credential
 * module wired by the autoconfiguration.
 */
class DeleteIdentityTest {

  private static final String ISSUER = "https://issuer.example.com";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  DataSourceAutoConfiguration.class,
                  DataSourceTransactionManagerAutoConfiguration.class,
                  TransactionAutoConfiguration.class,
                  JdbcTemplateAutoConfiguration.class,
                  JdbcClientAutoConfiguration.class,
                  WhoAutoConfiguration.class))
          .withPropertyValues(
              "who.initialize-schema=always",
              "who.email-link.link-base-url=https://app.example.com/signin",
              "who.email-link.code-secret=test-code-secret-0123456789-abcdefghij",
              "spring.datasource.url=" + EmailLinkSignInTest.POSTGRES.getJdbcUrl(),
              "spring.datasource.username=" + EmailLinkSignInTest.POSTGRES.getUsername(),
              "spring.datasource.password=" + EmailLinkSignInTest.POSTGRES.getPassword())
          .withBean(EmailLinkSender.class, () -> message -> {});

  @Test
  void deleteIdentityRemovesEveryCredentialAndLink() {
    runner.run(
        context -> {
          WhoService whoService = context.getBean(WhoService.class);
          JdbcClient jdbc = context.getBean(JdbcClient.class);
          Identity identity = whoService.createIdentity();
          UUID email =
              context
                  .getBean(EmailCredentialService.class)
                  .register(identity, "delete-" + identity.id() + "@example.com")
                  .id();
          UUID jwt = linkedJwt(context, identity, "delete-" + identity.id());
          context.getBean(ApiKeyService.class).create(identity, "Laptop");
          UUID apiKey =
              jdbc.sql(
                      "SELECT k.id FROM who_api_key_credential k JOIN who_credential_identity ci"
                          + " ON ci.credential_id = k.id WHERE ci.identity_id = :identityId")
                  .param("identityId", identity.id())
                  .query(UUID.class)
                  .single();
          RbacService rbac = context.getBean(RbacService.class);
          Role role = rbac.createRole("delete-" + identity.id());
          rbac.assignRoleToIdentity(identity, role);
          UUID passkey =
              insertPasskey(jdbc, context.getBean(CredentialIdentityRepository.class), identity);

          assertThat(whoService.deleteIdentity(identity.id())).isTrue();

          assertThat(count(jdbc, "who_identity", "id", identity.id())).isZero();
          assertThat(count(jdbc, "who_credential_identity", "identity_id", identity.id())).isZero();
          assertThat(count(jdbc, "who_email_credential", "id", email)).isZero();
          assertThat(count(jdbc, "who_jwt_credential", "id", jwt)).isZero();
          assertThat(count(jdbc, "who_api_key_credential", "id", apiKey)).isZero();
          assertThat(count(jdbc, "who_identity_role", "identity_id", identity.id())).isZero();
          assertThat(count(jdbc, "who_passkey_user", "identity_id", identity.id())).isZero();
          assertThat(count(jdbc, "who_passkey_credential", "credential_uuid", passkey)).isZero();
        });
  }

  @Test
  void deleteUnknownIdentityReturnsFalse() {
    runner.run(
        context ->
            assertThat(context.getBean(WhoService.class).deleteIdentity(UUID.randomUUID()))
                .isFalse());
  }

  @Test
  void purgeRemovesOnlyUnlinkedCredentials() {
    runner.run(
        context -> {
          WhoService whoService = context.getBean(WhoService.class);
          JdbcClient jdbc = context.getBean(JdbcClient.class);
          whoService.purgeOrphanedCredentials();
          Identity kept = whoService.createIdentity();
          EmailCredential keptEmail =
              context
                  .getBean(EmailCredentialService.class)
                  .register(kept, "kept-" + kept.id() + "@example.com");
          UUID keptJwt = linkedJwt(context, kept, "kept-" + kept.id());
          Identity deleted = whoService.createIdentity();
          EmailCredential orphanEmail =
              context
                  .getBean(EmailCredentialService.class)
                  .register(deleted, "orphan-" + deleted.id() + "@example.com");
          UUID orphanJwt = linkedJwt(context, deleted, "orphan-" + deleted.id());
          context.getBean(ApiKeyService.class).create(deleted, "Laptop");
          // A raw delete cascades to the links only.
          jdbc.sql("DELETE FROM who_identity WHERE id = :id").param("id", deleted.id()).update();

          assertThat(whoService.purgeOrphanedCredentials()).isEqualTo(3);

          assertThat(count(jdbc, "who_email_credential", "id", orphanEmail.id())).isZero();
          assertThat(count(jdbc, "who_jwt_credential", "id", orphanJwt)).isZero();
          assertThat(count(jdbc, "who_email_credential", "id", keptEmail.id())).isOne();
          assertThat(count(jdbc, "who_jwt_credential", "id", keptJwt)).isOne();
          whoService.deleteIdentity(kept.id());
        });
  }

  private static UUID linkedJwt(
      AssertableApplicationContext context, Identity identity, String subject) {
    JwtCredential credential =
        context.getBean(JwtCredentialRepository.class).save(JwtCredential.create(ISSUER, subject));
    context.getBean(CredentialIdentityRepository.class).link(credential.id(), identity.id());
    return credential.id();
  }

  private static UUID insertPasskey(
      JdbcClient jdbc, CredentialIdentityRepository links, Identity identity) {
    String userHandle = "handle-" + identity.id();
    jdbc.sql(
            "INSERT INTO who_passkey_user (id, identity_id, name) VALUES (:id, :identityId, :name)")
        .param("id", userHandle)
        .param("identityId", identity.id())
        .param("name", "passkey-" + identity.id())
        .update();
    UUID credentialUuid = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO who_passkey_credential (credential_id, user_entity_user_id, public_key,"
                + " backup_eligible, backup_state, created, label, credential_uuid)"
                + " VALUES (:credentialId, :userHandle, :publicKey, false, false, :created,"
                + " 'Laptop', :credentialUuid)")
        .param("credentialId", "passkey-" + credentialUuid)
        .param("userHandle", userHandle)
        .param("publicKey", new byte[] {1, 2, 3})
        .param("created", OffsetDateTime.now())
        .param("credentialUuid", credentialUuid)
        .update();
    links.link(credentialUuid, identity.id());
    return credentialUuid;
  }

  private static int count(JdbcClient jdbc, String table, String column, UUID value) {
    return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = :value")
        .param("value", value)
        .query(Integer.class)
        .single();
  }
}
