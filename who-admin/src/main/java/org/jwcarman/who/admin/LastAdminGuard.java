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

import java.util.UUID;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps who's admin endpoints from leaving no active identity that holds {@link
 * WhoAdminPermissions#IDENTITIES_MANAGE}. Deactivating an identity and removing one of its roles
 * run through it; a change that would take the holders from one or more to none is refused with
 * {@link LastAdminException}. When no one holds the permission, nothing is refused.
 *
 * <p>Holders are counted from who-rbac's tables: the {@link IdentityStatus#ACTIVE ACTIVE}
 * identities assigned a role that carries the permission. Only who-rbac's resolver makes those the
 * holders, so the guard is <em>enforcing</em> only when the application's permissions come from
 * who-rbac. With a resolver of the application's own, who cannot tell who holds the permission, so
 * the guard refuses nothing rather than refuse on a count it cannot trust; who's autoconfiguration
 * logs a warning at startup when that is so.
 *
 * <p><b>The lock.</b> The count and the change run in one transaction that first locks, with {@code
 * SELECT ... FOR UPDATE}, every {@code who_role} row whose role carries the permission, in id
 * order. Every guarded change takes those same locks, so two of them cannot both count before
 * either changes: two admins demoting each other, or one deactivating the other while being
 * demoted, are serialized, and the second counts what the first committed. The role rows are the
 * narrowest thing every such change has in common: an identity's own row would not serialize
 * changes to two different identities, and a table lock would also stop unrelated writes. A
 * concurrent assignment of one of those roles waits for the lock briefly, which only adds a holder.
 * The transaction is {@code READ COMMITTED} whatever the database's default, so the second change
 * counts what the first committed once it has the locks; under {@code REPEATABLE READ} it would
 * count its own older snapshot. Called inside a transaction of the caller's, the guard joins it at
 * that transaction's isolation, and the guarantee then needs that transaction to be {@code READ
 * COMMITTED}; the admin endpoints call it outside any transaction. Changes made outside the admin
 * endpoints, such as {@code RbacService} or {@code WhoService.deactivate} called by the
 * application, are not guarded.
 */
public final class LastAdminGuard {

  private static final String PERMISSION = "permission";
  private static final String IDENTITY = "identityId";

  private final JdbcClient jdbcClient;
  private final TransactionTemplate transactionTemplate;
  private final boolean enforcing;

  /**
   * Creates the guard.
   *
   * @param jdbcClient reads who-rbac's tables and takes the locks; its data source must be the one
   *     {@code transactionManager} manages, or the locks are taken outside the transaction
   * @param transactionManager runs the count and the change in one {@code READ COMMITTED}
   *     transaction
   * @param enforcing whether the permission's holders are who-rbac's to count; when false the guard
   *     refuses nothing
   */
  public LastAdminGuard(
      JdbcClient jdbcClient, PlatformTransactionManager transactionManager, boolean enforcing) {
    this.jdbcClient = requireNonNull(jdbcClient, "jdbcClient must not be null");
    this.transactionTemplate =
        new TransactionTemplate(
            requireNonNull(transactionManager, "transactionManager must not be null"));
    this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.enforcing = enforcing;
  }

  /**
   * Whether the guard refuses anything: the permission's holders are who-rbac's to count.
   *
   * @return whether it is enforcing
   */
  public boolean isEnforcing() {
    return enforcing;
  }

  /**
   * Runs a deactivation of {@code identityId} unless it is the last active holder of the
   * permission.
   *
   * @param identityId the identity being deactivated
   * @param deactivation deactivates it and returns a result
   * @param <T> the result type
   * @return what the deactivation returned
   * @throws LastAdminException if it would leave no active holder
   */
  public <T> T deactivate(UUID identityId, Supplier<T> deactivation) {
    requireNonNull(identityId, "identityId must not be null");
    requireNonNull(deactivation, "deactivation must not be null");
    return guarded(identityId, null, deactivation);
  }

  /**
   * Runs the removal of role {@code roleId} from {@code identityId} unless the identity is the last
   * active holder of the permission and holds it through no other role.
   *
   * @param identityId the identity losing the role
   * @param roleId the role it loses
   * @param removal removes the role and returns a result
   * @param <T> the result type
   * @return what the removal returned
   * @throws LastAdminException if it would leave no active holder
   */
  public <T> T removeRole(UUID identityId, UUID roleId, Supplier<T> removal) {
    requireNonNull(identityId, "identityId must not be null");
    requireNonNull(roleId, "roleId must not be null");
    requireNonNull(removal, "removal must not be null");
    return guarded(identityId, roleId, removal);
  }

  /** A null {@code roleLost}: the identity loses the permission whatever its roles. */
  private <T> T guarded(UUID identityId, @Nullable UUID roleLost, Supplier<T> change) {
    if (!enforcing) {
      return change.get();
    }
    return transactionTemplate.execute(
        status -> {
          lockGrantingRoles();
          if (wouldLeaveNone(identityId, roleLost)) {
            throw new LastAdminException();
          }
          return change.get();
        });
  }

  private void lockGrantingRoles() {
    jdbcClient
        .sql(
            """
            SELECT r.id FROM who_role r
            WHERE r.id IN (SELECT p.role_id FROM who_role_permission p WHERE p.permission = :permission)
            ORDER BY r.id
            FOR UPDATE
            """)
        .param(PERMISSION, WhoAdminPermissions.IDENTITIES_MANAGE)
        .query(UUID.class)
        .list();
  }

  private boolean wouldLeaveNone(UUID identityId, @Nullable UUID roleLost) {
    if (holdersOtherThan(identityId) > 0 || !holds(identityId, null)) {
      return false;
    }
    // the identity is the only holder: it must keep the permission through another role
    return roleLost == null || !holds(identityId, roleLost);
  }

  private long holdersOtherThan(UUID identityId) {
    return jdbcClient
        .sql(
            """
            SELECT COUNT(DISTINCT ir.identity_id)
            FROM who_identity_role ir
            JOIN who_role_permission p ON p.role_id = ir.role_id
            JOIN who_identity i ON i.id = ir.identity_id
            WHERE p.permission = :permission AND i.status = 'ACTIVE' AND ir.identity_id <> :identityId
            """)
        .param(PERMISSION, WhoAdminPermissions.IDENTITIES_MANAGE)
        .param(IDENTITY, identityId)
        .query(Long.class)
        .single();
  }

  /** Whether the identity is active and holds the permission through a role other than one. */
  private boolean holds(UUID identityId, @Nullable UUID exceptRole) {
    String except = exceptRole == null ? "" : " AND ir.role_id <> :exceptRole";
    JdbcClient.StatementSpec statement =
        jdbcClient
            .sql(
                """
                SELECT COUNT(*)
                FROM who_identity_role ir
                JOIN who_role_permission p ON p.role_id = ir.role_id
                JOIN who_identity i ON i.id = ir.identity_id
                WHERE p.permission = :permission AND i.status = 'ACTIVE'
                  AND ir.identity_id = :identityId"""
                    + except)
            .param(PERMISSION, WhoAdminPermissions.IDENTITIES_MANAGE)
            .param(IDENTITY, identityId);
    if (exceptRole != null) {
      statement = statement.param("exceptRole", exceptRole);
    }
    return statement.query(Long.class).single() > 0;
  }
}
