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

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.jwcarman.who.core.crypto.Secrets;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.service.WhoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Invites a person by email address: creates an identity for them, signs it in with that address,
 * gives it roles, and sends it a sign-in link in an {@link InvitationMessage}. Accepting the
 * invitation is signing in. Behind {@code POST /who/invitations}, and usable from application code
 * as it is.
 *
 * <p>The order is guaranteed:
 *
 * <ol>
 *   <li>In one transaction: the identity is created, the address registered to it, each role
 *       assigned, the caller's callback run (see {@link #invite(String, Set, Consumer)}), and
 *       {@link IdentityInvited} published synchronously, so a listener can react in the same
 *       transaction. If any step throws, the callback or a listener included, the whole transaction
 *       rolls back: no identity, address or role remains, and no link is sent. who's own events
 *       from these steps, such as {@code IdentityCreated}, are delivered only if it commits.
 *   <li>Only after that transaction has committed, the sign-in link is issued by {@link
 *       EmailLinkIssuer#issue} with no transaction open, so a link is only ever sent for an
 *       identity that exists, and no database connection is held while the email goes out. The
 *       invitation stands whatever becomes of the link: {@link Invitation#linkOutcome} says, and a
 *       link that is not sent is logged at WARN without the address or the token.
 * </ol>
 *
 * <p>This service applies no policy about who may grant which roles: it is for trusted application
 * code. {@code who-admin}'s endpoint applies its own.
 */
public class InvitationService {

  private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

  private final WhoService whoService;
  private final EmailCredentialService emailCredentials;
  private final @Nullable InvitationRoles roles;
  private final EmailLinkIssuer linkIssuer;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final TransactionTemplate invitation;

  /**
   * Creates the service.
   *
   * @param whoService creates the identity
   * @param emailCredentials registers its address
   * @param roles assigns its roles; null without {@code who-rbac}, when an invitation may carry no
   *     roles
   * @param linkIssuer sends the sign-in link, as an {@link InvitationMessage}
   * @param events where {@link IdentityInvited} is published, synchronously
   * @param transactionManager runs the invitation's transaction
   * @param clock stamps the event
   */
  public InvitationService(
      WhoService whoService,
      EmailCredentialService emailCredentials,
      @Nullable InvitationRoles roles,
      EmailLinkIssuer linkIssuer,
      ApplicationEventPublisher events,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.whoService = requireNonNull(whoService, "whoService must not be null");
    this.emailCredentials = requireNonNull(emailCredentials, "emailCredentials must not be null");
    this.roles = roles;
    this.linkIssuer = requireNonNull(linkIssuer, "linkIssuer must not be null");
    this.events = requireNonNull(events, "events must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    requireNonNull(transactionManager, "transactionManager must not be null");
    this.invitation = new TransactionTemplate(transactionManager);
  }

  /**
   * Invites the address with the named roles, in the order the class describes, with nothing of the
   * application's own to store. Equivalent to {@link #invite(String, Set, Consumer)} with a
   * callback that does nothing. Call it outside any transaction: the link is issued after the
   * invitation commits, with no transaction open, which a caller's transaction would postpone.
   *
   * @param email the address, as typed; registered normalized
   * @param roleNames the names of the roles to give the identity; empty for none
   * @return the new identity and what became of its sign-in link
   * @throws InvalidInvitationException before anything is created, if the address is missing or is
   *     not an address, a role name is blank, or roles are asked for without {@code who-rbac}; and,
   *     with everything rolled back, if a name names no role
   * @throws EmailAddressTakenException if the address already belongs to an identity, or a
   *     concurrent invitation or registration of it wins; nothing is created
   * @throws IllegalStateException if called inside a transaction
   */
  public Invitation invite(@Nullable String email, Set<String> roleNames) {
    return invite(email, roleNames, _ -> {});
  }

  /**
   * Invites the address with the named roles, in the order the class describes, running {@code
   * inTransaction} inside the invitation's transaction: after the identity is created, the address
   * registered and the roles assigned, and before {@link IdentityInvited} is published. Use it to
   * write the application's own data for the new person, such as a profile row or an activity-log
   * entry, so it commits or rolls back with the invitation.
   *
   * <p><b>The callback runs on the caller's thread</b>, so request-scoped state is reachable inside
   * it: the {@code SecurityContext}, request attributes, a {@link ScopedValue}, the locale. Its
   * database writes join the invitation's transaction.
   *
   * <p>If the callback throws, the whole invitation rolls back: no identity, address or role
   * remains, {@link IdentityInvited} is not heard by anything, no who event from these steps is
   * delivered and no link is sent. A {@link RuntimeException} or {@link Error} propagates to the
   * caller unchanged; a checked exception thrown by sneaky means arrives wrapped in an {@link
   * java.lang.reflect.UndeclaredThrowableException}, as {@code TransactionTemplate} does. Nothing
   * is translated.
   *
   * <p>The callback's writes join the invitation's transaction only if they use the same
   * transaction manager with joining propagation. A {@code REQUIRES_NEW} call inside it runs on
   * another connection, cannot see the uncommitted identity, and survives a rollback. If the
   * callback catches an exception from a {@code @Transactional} call that marked the transaction
   * rollback-only, {@code invite} throws {@code UnexpectedRollbackException} at commit and sends
   * nothing.
   *
   * <p>The method returns only after the send attempt, with its result in {@link
   * Invitation#linkOutcome}. Call it outside any transaction: the link is issued after the
   * invitation commits, with no transaction open, which a caller's transaction would postpone.
   * Everything that is checked before anything is created (the address, the role names, roles
   * without {@code who-rbac}, an open transaction) is checked before the callback can run.
   *
   * @param email the address, as typed; registered normalized
   * @param roleNames the names of the roles to give the identity; empty for none
   * @param inTransaction receives the new identity, inside the invitation's transaction
   * @return the new identity and what became of its sign-in link
   * @throws NullPointerException if {@code roleNames} or {@code inTransaction} is null
   * @throws InvalidInvitationException before anything is created, if the address is missing or is
   *     not an address, a role name is blank, or roles are asked for without {@code who-rbac}; and,
   *     with everything rolled back, if a name names no role
   * @throws EmailAddressTakenException if the address already belongs to an identity, or a
   *     concurrent invitation or registration of it wins; nothing is created
   * @throws IllegalStateException if called inside a transaction
   */
  public Invitation invite(
      @Nullable String email, Set<String> roleNames, Consumer<Identity> inTransaction) {
    requireNonNull(roleNames, "roleNames must not be null");
    requireNonNull(inTransaction, "inTransaction must not be null");
    if (email == null || !EmailAddresses.isSafe(email) || email.indexOf('@') < 0) {
      throw new InvalidInvitationException("email must be an email address");
    }
    if (roleNames.stream().anyMatch(name -> name == null || name.isBlank())) {
      throw new InvalidInvitationException("Role names must not be blank");
    }
    if (!roleNames.isEmpty() && roles == null) {
      throw new InvalidInvitationException("Roles need who-rbac, which this application lacks");
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "invite must be called outside a transaction, so the link goes after it commits");
    }
    Set<String> names = Set.copyOf(roleNames);
    Invited invited =
        requireNonNull(invitation.execute(_ -> register(email, names, inTransaction)), "invited");
    return new Invitation(invited.identityId(), sendLink(invited, names));
  }

  private Invited register(String email, Set<String> names, Consumer<Identity> inTransaction) {
    Identity identity = whoService.createIdentity();
    EmailCredential credential;
    try {
      credential = emailCredentials.register(identity, email);
    } catch (DuplicateKeyException _) {
      // the address's unique key: a concurrent invitation or registration took it first. Only
      // here, so a callback's or listener's own unique key further on is never reported as this
      throw new EmailAddressTakenException();
    }
    if (!names.isEmpty() && roles != null) {
      roles.assign(identity, names);
    }
    inTransaction.accept(identity);
    events.publishEvent(
        new IdentityInvited(clock.instant(), identity.id(), credential.address(), names));
    return new Invited(identity.id(), credential.address());
  }

  private EmailLinkOutcome sendLink(Invited invited, Set<String> names) {
    EmailLinkOutcome outcome;
    try {
      outcome =
          Objects.requireNonNullElse(
              linkIssuer.issue(
                  invited.address(),
                  Secrets.token(),
                  (address, link, code, expiresAt) ->
                      new InvitationMessage(address, link, code, expiresAt, names)),
              EmailLinkOutcome.SEND_FAILED);
    } catch (RuntimeException e) {
      // class name only: the message could carry the address
      log.warn(
          "Failed to send the invitation link for identity {}: {}",
          invited.identityId(),
          e.getClass().getName());
      return EmailLinkOutcome.SEND_FAILED;
    }
    if (outcome != EmailLinkOutcome.SENT) {
      log.warn("Invitation link for identity {} not sent: {}", invited.identityId(), outcome);
    }
    return outcome;
  }

  /** The committed invitation, before its link. */
  private record Invited(UUID identityId, String address) {}
}
