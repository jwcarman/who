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
package org.jwcarman.who.core.service;

import static java.util.Objects.requireNonNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.jwcarman.who.core.exception.LastCredentialException;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps an identity from losing its last sign-in credential. Self-service removals run through
 * {@link #removeIfNotLast}, which counts and removes in one transaction holding the identity's row
 * lock, so two concurrent removals cannot both pass the check.
 *
 * <p>That transaction is {@code READ COMMITTED} whatever the database's default, because the second
 * removal must count what the first committed once it gets the lock; under {@code REPEATABLE READ}
 * it would count its own older snapshot, and both could pass. Called inside a transaction of the
 * caller's, the guard joins it at that transaction's isolation, so the guarantee then holds only if
 * that transaction is {@code READ COMMITTED}; who's own endpoints call it outside any transaction.
 *
 * <p>The credential services keep their own contract and do not apply this rule; callers that
 * remove credentials on a person's behalf must.
 */
public final class LastCredentialGuard {

  private final IdentityRepository identityRepository;
  private final List<SignInCredentialCounter> counters;
  private final TransactionTemplate transactionTemplate;

  /**
   * Creates the guard.
   *
   * @param identityRepository locks the identity's row
   * @param counters one counter per kind of sign-in credential
   * @param transactionManager runs the count and the removal in one {@code READ COMMITTED}
   *     transaction; it must manage the data source the counters and the repository use
   */
  public LastCredentialGuard(
      IdentityRepository identityRepository,
      List<SignInCredentialCounter> counters,
      PlatformTransactionManager transactionManager) {
    this.identityRepository =
        requireNonNull(identityRepository, "identityRepository must not be null");
    this.counters = List.copyOf(requireNonNull(counters, "counters must not be null"));
    this.transactionTemplate =
        new TransactionTemplate(
            requireNonNull(transactionManager, "transactionManager must not be null"));
    this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  /**
   * Runs the removal unless it would take the identity's last sign-in credential. The identity is
   * locked, the credentials counted and the removal run in one transaction.
   *
   * @param identityId the identity losing a credential
   * @param removal removes one credential and returns a result
   * @param <T> the result type
   * @return what the removal returned
   * @throws LastCredentialException if the identity holds one credential or none
   */
  public <T> T removeIfNotLast(UUID identityId, Supplier<T> removal) {
    requireNonNull(identityId, "identityId must not be null");
    requireNonNull(removal, "removal must not be null");
    return transactionTemplate.execute(
        status -> {
          identityRepository.lockById(identityId);
          long total = counts(identityId).values().stream().mapToLong(Long::longValue).sum();
          if (total <= 1) {
            throw new LastCredentialException();
          }
          return removal.get();
        });
  }

  /**
   * Counts the identity's sign-in credentials by kind.
   *
   * @param identityId the identity
   * @return the count for each kind, in counter order
   */
  public Map<String, Long> counts(UUID identityId) {
    requireNonNull(identityId, "identityId must not be null");
    Map<String, Long> counts = new LinkedHashMap<>();
    for (SignInCredentialCounter counter : counters) {
      counts.put(counter.kind(), counter.count(identityId));
    }
    return counts;
  }
}
