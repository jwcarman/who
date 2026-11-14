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
package org.jwcarman.who.oidc;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.CredentialAdded;
import org.jwcarman.who.core.event.IdentityCreated;
import org.jwcarman.who.core.event.SignInFailed;
import org.jwcarman.who.core.event.SignInFailureReason;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.Resolution;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.signup.SignUpDecision;
import org.jwcarman.who.core.signup.WhoSignUpPolicy;
import org.jwcarman.who.federated.FederatedCredential;
import org.jwcarman.who.federated.FederatedCredentialRepository;
import org.jwcarman.who.federated.FederatedCredentialService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Confirms a pending sign-up through the application's {@link WhoSignUpPolicy}, or links a pending
 * account to the identity signed in. Each runs in its own transaction. Two browsers confirming the
 * same account at once both end up signed in to the one identity that won.
 */
public final class OidcSignUpService {

  /**
   * A confirmed sign-up.
   *
   * @param principal the identity signed up, or the one that won a race for the same account
   * @param credentialId the account's credential
   */
  public record SignedUp(WhoPrincipal principal, UUID credentialId) {}

  private final WhoSignUpPolicy policy;
  private final WhoService whoService;
  private final IdentityRepository identities;
  private final FederatedCredentialRepository credentials;
  private final FederatedCredentialService credentialService;
  private final CredentialIdentityRepository credentialIdentities;
  private final WhoEventPublisher eventPublisher;
  private final Clock clock;
  private final TransactionTemplate transaction;

  /**
   * Creates the service.
   *
   * @param policy decides whether an account may sign up
   * @param whoService resolves a credential to its identity
   * @param identities stores the new identity
   * @param credentials stores the account's credential
   * @param credentialService links an account to a signed-in identity
   * @param credentialIdentities links a credential to an identity
   * @param eventPublisher where events are published
   * @param clock stamps the events
   * @param transactionManager runs each confirmation in a transaction of its own
   */
  public OidcSignUpService(
      WhoSignUpPolicy policy,
      WhoService whoService,
      IdentityRepository identities,
      FederatedCredentialRepository credentials,
      FederatedCredentialService credentialService,
      CredentialIdentityRepository credentialIdentities,
      WhoEventPublisher eventPublisher,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.policy = requireNonNull(policy, "policy must not be null");
    this.whoService = requireNonNull(whoService, "whoService must not be null");
    this.identities = requireNonNull(identities, "identities must not be null");
    this.credentials = requireNonNull(credentials, "credentials must not be null");
    this.credentialService =
        requireNonNull(credentialService, "credentialService must not be null");
    this.credentialIdentities =
        requireNonNull(credentialIdentities, "credentialIdentities must not be null");
    this.eventPublisher = requireNonNull(eventPublisher, "eventPublisher must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.transaction =
        new TransactionTemplate(requireNonNull(transactionManager, "transactionManager"));
    this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * Signs a pending account up, when the policy allows it.
   *
   * @param pending the pending sign-up
   * @return the identity signed up
   * @throws SignUpRefusedException when the policy refuses, or the account's identity cannot sign
   *     in
   */
  public SignedUp signUp(PendingSignUp pending) {
    if (!(policy.decide(pending.toRequest()) instanceof SignUpDecision.Allow allow)) {
      eventPublisher.publish(
          new SignInFailed(
              clock.instant(),
              SignInMethod.OIDC,
              SignInFailureReason.SIGN_UP_REFUSED,
              null,
              null,
              null));
      throw new SignUpRefusedException();
    }
    try {
      return transaction.execute(status -> create(pending, allow));
    } catch (DuplicateKeyException e) {
      // another sign-up of the same account committed first; sign in to its identity
      return transaction.execute(
          status ->
              existing(pending).flatMap(this::signedUp).orElseThrow(SignUpRefusedException::new));
    }
  }

  /**
   * Links a pending account to an identity.
   *
   * @param identity the identity signed in
   * @param pending the pending sign-up
   * @return the account's credential
   * @throws AlreadyLinkedException when the account belongs to another identity
   */
  public FederatedCredential link(Identity identity, PendingSignUp pending) {
    try {
      return transaction.execute(status -> linkInTransaction(identity, pending));
    } catch (DuplicateKeyException e) {
      return transaction.execute(status -> linkInTransaction(identity, pending));
    }
  }

  private SignedUp create(PendingSignUp pending, SignUpDecision.Allow allow) {
    Optional<FederatedCredential> found = existing(pending);
    FederatedCredential credential;
    if (found.isPresent()) {
      Resolution resolution = whoService.resolveDetailed(found.get());
      if (resolution instanceof Resolution.Resolved(WhoPrincipal principal)) {
        return new SignedUp(principal, found.get().id());
      }
      if (!(resolution instanceof Resolution.NotLinked)) {
        throw new SignUpRefusedException();
      }
      credential = found.get();
    } else {
      // secured first: a concurrent sign-up of the same account fails here, before any identity
      credential =
          credentials.save(FederatedCredential.create(pending.issuer(), pending.subject()));
    }
    Identity identity = identities.save(Identity.create());
    credentialIdentities.link(credential.id(), identity.id());
    allow.setup().accept(identity);
    eventPublisher.publish(new IdentityCreated(clock.instant(), identity.id()));
    eventPublisher.publish(
        new CredentialAdded(
            clock.instant(),
            identity.id(),
            credential.id(),
            credential.kind(),
            credential.issuer()));
    return signedUp(credential).orElseThrow(SignUpRefusedException::new);
  }

  private FederatedCredential linkInTransaction(Identity identity, PendingSignUp pending) {
    Optional<FederatedCredential> found = existing(pending);
    if (found.isEmpty()) {
      return credentialService.link(identity, pending.issuer(), pending.subject());
    }
    Optional<UUID> owner = credentialIdentities.findIdentityIdByCredentialId(found.get().id());
    if (owner.isEmpty()) {
      return credentialService.linkExisting(identity, found.get());
    }
    if (owner.get().equals(identity.id())) {
      return found.get();
    }
    throw new AlreadyLinkedException();
  }

  private Optional<FederatedCredential> existing(PendingSignUp pending) {
    return credentials.findByIssuerAndSubject(pending.issuer(), pending.subject());
  }

  private Optional<SignedUp> signedUp(FederatedCredential credential) {
    return whoService
        .resolve(credential)
        .map(principal -> new SignedUp(principal, credential.id()));
  }
}
