# Adding and changing an email address

## Intent

`EmailCredentialService.register(identity, address)` attaches an address at once, proving nothing.
It is right for an application vouching for an address (an admin invite), and wrong for a person
adding one: someone could claim another person's address, which would then be taken (squatting),
or get a victim to sign in to the attacker's identity (injection). A person should add an address,
or change one, only by proving they read its mailbox, the way they prove it to sign in.

The owner asked for this on 2026-11-15, changing included. who is a security library; breaking
changes are acceptable and documented.

## Goals

- A signed-in person adds an address only after confirming a link or code sent to it.
- A change is an add that names the address it replaces: confirmed by the new address, applied in
  one transaction, and announced to the old address.
- The request answers alike whether the address is free or taken.
- One rule for "signed in recently" before adding any credential.

## Non-goals

- Removing an address on its own, and the last-credential guard it needs (a change never lowers
  the count). Applications call `EmailCredentialService.revoke`, as today.
- An undo link in the notice to the old address.
- Changing `register`; invites keep using it.

## Approaches

- **A separate request table (chosen).** `who_email_address_request` holds the pending address,
  bound to the identity that asked, with hashes of a link token and a code. Sign-in tokens and
  confirmation tokens can never stand in for each other, and limits are per address on the server.
- Keep the pending request in the HTTP session only: no migration, but its limits reset with a new
  session, so it could flood a mailbox.
- Store the unconfirmed address as a credential marked pending: reuses the token table, but the
  pending row holds the address hostage, the squatting this is meant to stop, and who already
  treats unowned credentials as orphans to delete.

## The design

### One recent sign-in rule

`who.security.recent-sign-in` (default `10m`) replaces `who.passkey.registration-window` and
`who.oidc.link-window`. A credential is added only by a browser session whose who sign-in is that
recent (`WhoSession.signedInAt`), checked when the request starts and again when it is confirmed;
otherwise `401` with `ReauthenticationRequiredException`. Passkey registration, provider links
(now also at `POST /who/oidc/link/{registrationId}`, so a stale session fails before the trip to
the provider) and email addresses all use it. A started provider link still lasts that long.

### Requesting

`POST /who/email/addresses`, CSRF-protected, JSON `{ "address": "...", "replaces": "<id>" }`
(`replaces` optional). Signed out is `401` (`NotSignedInException`); not recent is `401`
(`ReauthenticationRequiredException`); a `replaces` that is not one of the caller's own email
credentials is `404` (`EmailCredentialNotFoundException`). Otherwise `202` at once, and the rest
runs off the request thread, as sign-in does, so timing tells nothing:

- an address that is not one, or is registered to anyone (the caller included): nothing sent;
- a request for the same address made within `who.email-link.resend-interval`, by anyone, or by
  this identity for any address: nothing sent;
- otherwise the identity's request (one at a time; a new one replaces it) is saved and the address
  is sent a link and a code. A failed send removes it.

Each request publishes `EmailAddressConfirmationRequested` with its outcome.

### The email

`EmailLinkSender.send` now takes a sealed `WhoEmail`: the sign-in `EmailLinkMessage`, an
`EmailAddressConfirmationMessage` (address, link, code, expiry, whether it is a change) and an
`EmailAddressChangedMessage` (the old address, the new one). The link is
`who.email-link.address-confirmation-url` (default `link-base-url`) with `#confirm-address=<token>`.
`MailEmailLinkSender` gains a subject for each (`address-confirmation-subject`,
`address-changed-subject`), and shows the new address masked in the notice.

### Confirming

`POST /who/email/addresses/confirm`, JSON `{ "token": "..." }` or `{ "code": "..." }`, by the same
identity, signed in recently. A token is consumed only by the identity that asked. A code counts
against `who.email-link.code-max-attempts`, as at sign-in, and is keyed to the request. No match,
an expired or used request, or too many wrong codes is `400`
(`InvalidEmailAddressConfirmationException`). An address registered since is `409`
(`EmailAddressTakenException`). Success adds the address (`CredentialAdded`), and for a change
removes the old one in the same transaction (`CredentialRevoked`), then answers
`{ "credentialId": "..." }` and, for a change, sends the old address the notice (best effort).

## Breaking changes

- `EmailLinkSender.send(WhoEmail)`; a sender switches over the three messages.
- `who.passkey.registration-window` and `who.oidc.link-window` are gone; use
  `who.security.recent-sign-in`.
- `POST /who/oidc/link/{registrationId}` needs a recent sign-in.

## Left to the builder

Class and column names, the event's fields, the exact HTTP shape of errors (through the handoff, as
every who endpoint).
