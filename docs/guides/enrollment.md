# Enrollment

Before a JWT can authenticate, a `JwtCredential` row must exist for the `(issuer, subject)` pair and must be linked to an active `Identity`. Who does not auto-provision — access is denied for unknown credentials.

Take the `issuer` and `subject` from the authenticated redeemer's own validated JWT. Never take them from the request body.

## Using `WhoEnrollmentService`

```java
// 1. Create an identity via WhoService
Identity identity = whoService.createIdentity();

// 2. Issue an enrollment token and deliver token.value() to the user out of band
EnrollmentToken token = enrollmentService.createToken(identity);
notifyUser(token.value()); // email, admin console, etc.

// 3. The user logs in, then redeems the token. issuer and subject come from the
//    redeemer's validated JWT. Do this in one @Transactional method: find the
//    credential or create it, then enroll. A failed enroll then leaves no credential
//    row behind, and a retry finds the existing one.
JwtCredential credential = jwtCredentialRepository
    .findByIssuerAndSubject(issuer, subject)
    .orElseGet(() -> jwtCredentialRepository.save(JwtCredential.create(issuer, subject)));
Identity enrolled = enrollmentService.enroll(token.value(), credential);

// To cancel a token before it is redeemed:
enrollmentService.revokeToken(token);
```

A token can be redeemed once, even if two requests race. `enroll` throws:

- `EnrollmentTokenNotFoundException` if the token does not exist,
- `EnrollmentTokenExpiredException` if it has expired,
- `EnrollmentTokenNotPendingException` if it was already redeemed or revoked,
- `EnrollmentIdentityInactiveException` if the identity is not active,
- `CredentialAlreadyLinkedException` if the credential is already linked to an identity.

## Manual SQL (bootstrapping and tests)

```sql
-- Create identity
INSERT INTO who_identity (id, status, created_at, updated_at)
VALUES (gen_random_uuid(), 'ACTIVE', NOW(), NOW());

-- Create JWT credential
INSERT INTO who_jwt_credential (id, issuer, subject)
VALUES (gen_random_uuid(), 'https://your-issuer.com', 'alice');

-- Link credential to identity
INSERT INTO who_credential_identity (credential_id, identity_id)
VALUES (<credential_id>, <identity_id>);
```
