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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.signup.SignUpDecision;
import org.jwcarman.who.federated.FederatedCredential;
import org.jwcarman.who.oidc.OidcSignUpService;
import org.jwcarman.who.oidc.PendingSignUp;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class OidcSignUpServiceTest extends AbstractOidcTest {

  @Autowired private OidcSignUpService signUpService;

  private static PendingSignUp pending(String subject) {
    return new PendingSignUp(
        "test",
        PROVIDER.issuer(),
        subject,
        subject,
        null,
        Map.of(),
        null,
        Instant.now().plus(Duration.ofMinutes(10)));
  }

  private long identities() {
    return jdbcClient.sql("SELECT count(*) FROM who_identity").query(Long.class).single();
  }

  @Test
  void race_loser_signs_in_to_the_winner() throws Exception {
    CyclicBarrier bothDecided = new CyclicBarrier(2);
    policy.set(
        request -> {
          try {
            bothDecided.await(5, TimeUnit.SECONDS);
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
          return SignUpDecision.allow();
        });
    long before = identities();

    List<OidcSignUpService.SignedUp> results;
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      Future<OidcSignUpService.SignedUp> one =
          pool.submit(() -> signUpService.signUp(pending("racer")));
      Future<OidcSignUpService.SignedUp> two =
          pool.submit(() -> signUpService.signUp(pending("racer")));
      results = List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
    }

    assertThat(results.get(0).principal().identity().id())
        .isEqualTo(results.get(1).principal().identity().id());
    assertThat(results.get(0).credentialId()).isEqualTo(results.get(1).credentialId());
    assertThat(identities()).isEqualTo(before + 1);
  }

  @Test
  void reuses_a_not_linked_row() {
    policy.set(request -> SignUpDecision.allow());
    FederatedCredential orphan =
        federatedCredentialRepository.save(FederatedCredential.create(PROVIDER.issuer(), "orphan"));

    OidcSignUpService.SignedUp signedUp = signUpService.signUp(pending("orphan"));

    assertThat(signedUp.credentialId()).isEqualTo(orphan.id());
    assertThat(whoService.resolve(orphan))
        .hasValueSatisfying(
            p -> assertThat(p.identity().id()).isEqualTo(signedUp.principal().identity().id()));
  }

  @Test
  void an_account_someone_already_signed_up_signs_in_to_them() {
    policy.set(request -> SignUpDecision.allow());

    OidcSignUpService.SignedUp signedUp = signUpService.signUp(pending("alice"));

    assertThat(signedUp.principal().identity().id()).isEqualTo(alice.id());
    assertThat(signedUp.credentialId()).isEqualTo(aliceCredential.id());
  }
}
