# who: typed sign-in code in the email (design)

Date: 2026-11-13
Status: draft, pending review
Target: who 0.15.0
Supersedes: "plan 4" in `2026-11-07-passwordless-credentials-design.md` (lines 52–56)

## Why

Today an email sign-in only works by clicking the link. People often read the email on one device
(their phone) and want to sign in on another (their laptop). They should be able to type the code,
or paste it through Apple's shared clipboard, into the browser that asked for it. A code also
helps with mobile in-app browsers and with mail security scanners that open links before the
person does.

## Goals

- Every sign-in email carries a short code next to the link. Either one signs the person in, and
  using either one makes the other stop working.
- The code is 6 characters by default (configurable) and easy to read and type.
- It is secure:
  - only a keyed hash of the code is stored;
  - wrong guesses are capped per issued code, and concurrent requests cannot get past the cap;
  - failed attempts do not reveal whether an address is registered.

## Non-goals

- Upgrading existing databases in place. Recreate them instead.
- Binding the code to the browser session that requested it (see Decisions).
- An SMS or other delivery channel.
- A digits-only alphabet.

## Anti-goals

- Anyone who knows an address can lock that person out of email sign-in.
- Codes can be guessed by spraying one code against every outstanding code.
- A database read is enough to recover a live code.

## Approaches considered

| Approach | Verdict |
|---|---|
| **Link and code in the same row, redeemed on `POST /login/ott`** | **Chosen.** One lifecycle. The existing upsert already acts as the resend limit and the "one live sign-in per credential" rule, and it carries over. |
| Separate code table and endpoint | Rejected. It means a second lifecycle, a second resend limit and two outstanding secrets per person. |
| Code alone, without the address | Rejected. Each guess is tried against every outstanding code at once, and codes collide across users. |
| Code derived from the link token | Rejected. It needs the same storage and lookup and gains nothing. |

**Hashing the code:**

| Option | Verdict |
|---|---|
| **HMAC-SHA256 with a server secret** | **Chosen.** The secret is not in the database, so a database read cannot be attacked offline. It is fast and uses only the JDK. |
| bcrypt | Rejected. It only slows an attacker down: someone with database read access can request a fresh code each resend interval and race the TTL. It also costs real CPU per guess and needs dummy hashing on the miss paths. |
| Plain SHA-256 | Rejected. About 1e9 possibilities can be reversed in seconds. |

**Carrying the two forms through Spring:**

| Option | Verdict |
|---|---|
| **A sealed `EmailSignInProof` in our own `Authentication`** | **Chosen.** The provider switches over it exhaustively, so a new form becomes a compile error. Nothing hides inside `OneTimeTokenService`. |
| Subclass Spring's `OneTimeTokenAuthenticationToken` and branch in `consume` | Rejected. It means an `instanceof` on a Spring type, and it stretches the `OneTimeTokenService` contract to cover address-plus-code checks. Spring's two-argument constructor is also deprecated for removal. |

## Decisions

1. **The email carries both.** The link stays exactly as it is, and the code is added. A code is
   always generated, and there is no switch to turn it off: the security and performance gains
   would be negligible. A switch can be added later without breaking anything if someone needs
   link-only sign-in.
2. **No session binding.** The phone-to-laptop case works either way, because the laptop both
   requested the code and redeems it. Binding protects little:
   - an attacker can start their own request for a victim's address and guess within their own
     session;
   - it would leave invitations sent through `EmailLinkIssuer.issue` without a usable code.
   *(My call; the original plan-4 note said "bound to the session".)*
3. **Code format:**
   - Crockford base32, `0123456789ABCDEFGHJKMNPQRSTVWXYZ`, generated from `SecureRandom` with
     `nextInt(32)` per character, so there is no modulo bias.
   - 6 characters is 30 bits.
   - The email shows it grouped as `K7F-2QM`. For an odd length, the first group is the longer one.
4. **Normalizing input** happens before any database call:
   - upper-case;
   - drop spaces and hyphens;
   - map `O`→`0` and `I`/`L`→`1`.

   A result with the wrong length or a character outside the alphabet fails at once. That depends
   only on the input's shape, so it leaks nothing.
5. **Attempt limit:** after `code-max-attempts` wrong codes (default 5) the code is dead, but the
   link stays valid. A new request resets both, subject to the resend interval.
6. **Hash input:** `hex(HMAC-SHA256(secret, credentialId + ":" + normalizedCode))`. Binding the
   credential id in means two people with the same code get different hashes. The link
   `token_hash` stays plain SHA-256, since the token has 256 bits of entropy.
7. **Breaking changes are accepted for 0.15.0:**
   - the `EmailLinkSender` signature;
   - the `EmailLinkTokenRepository` methods;
   - the `EmailLinkAuthenticationProvider` constructor and `supports`;
   - a new required secret.
8. **Residual timing:** the attempt statement only writes when a live code exists. That write
   versus no-write difference is small, and the required ingress rate limits
   (`docs/guides/email-links.md`) cover it. There is no minimum-time floor. *(My call.)*
9. **Subject** default becomes "Your sign-in link and code". *(My call.)*

## Design

### Data model

`who_email_link_token` gains two columns:

```sql
code_hash     VARCHAR(64) NOT NULL,   -- not unique: lookup is by credential
code_attempts INTEGER     NOT NULL
```

There is no upgrade path: existing databases are dropped and recreated.

- **`who-email-link-schema.sql`:** add the columns to the `CREATE TABLE`.
- **Liquibase:** add them to the existing `who_email_link_token` changeSet in
  `who-email-link-changelog.xml`, in place.
- **No** `ALTER` statements, new changeSet, or migration notes.
- **Docs:** update the schema copy in `docs/guides/database.md`.

### Generation and issue

- **`Secrets`:** add `code(int length)` to `who-core/.../crypto/Secrets.java`.
- **HMAC helper:** add one next to `MessageDigests`, using `javax.crypto.Mac`.
- **`EmailLinkIssuer.issueTo`:** generates the code and passes its HMAC to `repository.issue`. The
  signature of `issue(address, tokenValue)` is unchanged.
- **The upsert** also sets `code_hash = EXCLUDED.code_hash, code_attempts = 0`.

### Redemption on `POST /login/ott`

```java
public sealed interface EmailSignInProof permits LinkToken, EmailCode, Malformed {}
public record LinkToken(String token) implements EmailSignInProof {}
public record EmailCode(String address, String code) implements EmailSignInProof {}  // normalized
public record Malformed() implements EmailSignInProof {}   // replaces the NO_TOKEN sentinel

public final class EmailSignInAuthenticationToken extends AbstractAuthenticationToken { /* proof */ }
```

**Converter** (`JsonOneTimeTokenAuthenticationConverter`, possibly renamed):
- reads the body once, so `JsonBodies` needs a multi-field read;
- `{token}` becomes `LinkToken`;
- `{email, code}` becomes `EmailCode`;
- anything else, including both forms at once or a code that fails normalizing, becomes
  `Malformed`.

**Provider** (`EmailLinkAuthenticationProvider`):

```java
Optional<UUID> credentialId = switch (proof) {
  case LinkToken t -> tokens.consume(sha256(t.token()), now);
  case EmailCode c -> codes.redeem(c, now);
  case Malformed m -> Optional.empty();
};
// then whoService.resolve(...) as today; every failure → same InvalidOneTimeTokenException
```

- `supports` matches `EmailSignInAuthenticationToken`.
- `WhoOneTimeTokenService` keeps only `generate`. Its `consume` throws
  `UnsupportedOperationException`, because who's provider never calls it.

**Code redemption** uses two statements. Statement 1 reserves an attempt. It is the same
statement, with the same HMAC work afterwards, for an unknown address, no code, an expired code
and a burnt code:

```sql
UPDATE who_email_link_token t
   SET code_attempts = t.code_attempts + 1
  FROM who_email_credential c
 WHERE c.address = :address AND t.credential_id = c.id
   AND t.code_attempts < :maxAttempts AND t.expires_at > :now
RETURNING t.credential_id, t.code_hash
```

The row lock serializes concurrent guesses. Under READ COMMITTED the `WHERE` is re-checked after
the lock wait, so at most `maxAttempts` guesses are ever evaluated.

In Java, compute the HMAC and compare it with `MessageDigest.isEqual`. When no row came back,
compute a dummy HMAC so the work is the same.

Statement 2 runs only on a match and is the only thing that grants sign-in:

```sql
DELETE FROM who_email_link_token
 WHERE credential_id = :credentialId AND code_hash = :codeHash AND expires_at > :now
RETURNING credential_id
```

If two correct submissions arrive at once, exactly one gets a row back. Link consumption is
unchanged and ignores `code_attempts`, which is what keeps the link working after the code is
burnt.

**`EmailLinkTokenRepository`:**
- `issue` gains `codeHash`;
- add `attemptCode(address, maxAttempts, now)`;
- add `consumeCode(credentialId, codeHash, now)`.

The address is normalized the same way `EmailCredentialService.findByAddress` does it.

### Sender

`EmailLinkSender.send(EmailLinkMessage message)` replaces the existing method, with
`record EmailLinkMessage(String address, String link, String code, Instant expiresAt)`.

- `code` is the raw normalized code (`K7F2QM`), so a sender can lay it out however it likes, for
  example one box per character in HTML.
- The record also has a `formattedCode()` method that returns the grouped form (`K7F-2QM`), so no
  sender has to repeat the grouping rule. *(My call.)*
- It stays a one-method functional interface.
- The compile error forces custom senders to handle the code.
- The record can grow without another break.

`MailEmailLinkSender` text:

```
Sign in: <link>

Or enter this code: K7F-2QM

The link and the code expire in 5 minutes and work once.
```

The expiry text is computed from `expiresAt`.

### Configuration (`who.email-link.*`)

| Property | Default | Validation at startup |
|---|---|---|
| `code-length` | `6` | must be ≥ 6 |
| `code-max-attempts` | `5` | must be ≥ 1 |
| `code-secret` | none | required, at least 32 characters; the error message names the property |

These are added to `WhoProperties.EmailLink` and `EmailLinkSettings`, and validated where
`WhoAutoConfiguration` already checks the email-link settings. The code is never logged.

## Docs and tests

**Docs:**
- `docs/guides/email-links.md`: the flow, the `{email, code}` request body, the sender example,
  the secret, the attempt limit, and the property count;
- `docs/guides/configuration.md` and `docs/guides/database.md`;
- `docs/guides/upgrading.md` (a 0.15.0 section: the new secret, the sender, the repository, and
  "recreate the database");
- `CHANGELOG.md`;
- a pointer from plan 4 of the 2026-11-07 spec to this document.

**Tests:**
- **`JdbcEmailLinkTokenRepositoryTest`:**
  - concurrent wrong guesses stop at the cap;
  - concurrent correct codes produce exactly one winner;
  - a re-issue resets the attempts;
  - the link still works after the code is burnt;
  - an expired code fails.
- **Unit tests:**
  - `Secrets.code`, the HMAC helper and normalizing;
  - the converter (each body shape);
  - the provider (each proof);
  - the issuer;
  - `MailEmailLinkSender`.
- **`EmailLinkSignInTest`** (end to end):
  - sign-in with a code, including lower-case and dashed input;
  - the same 401 for an unknown address, a wrong code, a burnt code and both forms at once;
  - the startup failures.
- **`LiquibaseChangelogTest`.**

## Left to the builder

- Class and package names beyond those above, and whether the converter is renamed.
- The exact grouping rule for lengths other than 6.
- How `JsonBodies` exposes a multi-field read.
- The test structure.
