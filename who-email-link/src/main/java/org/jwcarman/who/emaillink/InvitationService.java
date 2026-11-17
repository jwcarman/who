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

import org.jspecify.annotations.Nullable;
import org.jwcarman.who.core.crypto.Secrets;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.service.WhoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Invites a person by email address: creates an identity for them, signs it in with that address,
 * gives it roles, and sends it a sign-in link. Accepting the invitation is signing in. Behind
 * {@code POST /who/invitations}, and usable from application code as it is.
 *
 * <p>The order is guaranteed:
 *
 * <ol>
 *   <li>In one transaction: the identity is created, the address registered to it, each role
 *       assigned, and {@link IdentityInvited} published synchronously, so a listener can write the
 *       application's own data in the same transaction. If any step throws, a listener included,
 *       the whole transaction rolls back: no identity, address or role remains, and no link is
 *       sent. who's own events from these steps, such as {@code IdentityCreated}, are delivered
 *       only if it commits.
 *   <li>Only after that transaction has committed, the sign-in link is issued by {@link
 *       EmailLinkIssuer#issue} in a transaction of its own, so a link is only ever sent for an
 *       identity that exists. The invitation stands whatever becomes of the link: {@link
 *       Invitation#linkOutcome} says, and a link that is not sent is logged at WARN without the
 *       address or the token.
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
  private final TransactionTemplate link;

  /**
   * Creates the service.
   *
   * @param whoService creates the identity
   * @param emailCredentials registers its address
   * @param roles assigns its roles; null without {@code who-rbac}, when an invitation may carry no
   *     roles
   * @param linkIssuer sends the sign-in link
   * @param events where {@link IdentityInvited} is published, synchronously
   * @param transactionManager runs the invitation's transaction and then the link's
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
    this.link = new TransactionTemplate(transactionManager);
    this.link.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * Invites the address with the named roles, in the order the class describes. Call it outside any
   * transaction: the link must go only after the invitation commits, which a caller's transaction
   * would postpone.
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
    requireNonNull(roleNames, "roleNames must not be null");
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
    Invited invited = requireNonNull(invitation.execute(_ -> register(email, names)), "invited");
    return new Invitation(invited.identityId(), sendLink(invited));
  }

  private Invited register(String email, Set<String> names) {
    Identity identity = whoService.createIdentity();
    EmailCredential credential;
    try {
      credential = emailCredentials.register(identity, email);
    } catch (DuplicateKeyException _) {
      // the address's unique key: a concurrent invitation or registration took it first. Only
      // here, so a listener's own unique key further on is never reported as this
      throw new EmailAddressTakenException();
    }
    if (!names.isEmpty() && roles != null) {
      roles.assign(identity, names);
    }
    events.publishEvent(
        new IdentityInvited(clock.instant(), identity.id(), credential.address(), names));
    return new Invited(identity.id(), credential.address());
  }

  private EmailLinkOutcome sendLink(Invited invited) {
    EmailLinkOutcome outcome;
    try {
      outcome =
          Objects.requireNonNullElse(
              link.execute(_ -> linkIssuer.issue(invited.address(), Secrets.token())),
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
