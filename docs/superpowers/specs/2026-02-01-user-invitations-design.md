# User Invitation System Design

**Date:** 2026-02-01
**Status:** Approved

## Overview

Enable admins to invite new users by email. When users accept invitations via OAuth, they are auto-provisioned with assigned roles and verified contact methods.

## Goals

- **Secure onboarding** - Only invited emails can join
- **No duplicate users** - Email matching prevents multiple identities for same person
- **Admin control** - Explicit invitation process, role assignment
- **Email verification** - Configurable trust of OAuth provider verification
- **Audit trail** - Track who invited whom, when invitations were accepted

## Core Concepts

### Invitation Lifecycle

Invitations progress through these states:

- **PENDING** - Created, awaiting acceptance
- **ACCEPTED** - User completed OAuth flow, account created
- **EXPIRED** - Past expiration timestamp (configurable, default 72h)
- **REVOKED** - Admin cancelled before acceptance

### Security Model

Two orthogonal security settings control email verification:

```yaml
who:
  security:
    require-verified-email: true          # Reject auth if email_verified: false in JWT
    trust-issuer-verification: true       # Auto-add verified contact from OAuth provider
    notify-on-contact-add: true           # Send "if this wasn't you" notification
  invitations:
    expiration-hours: 72                  # Default invitation expiration
```

**Setting combinations:**
- `require-verified-email: true` - Reject authentication if OAuth provider doesn't verify email
- `trust-issuer-verification: true` - Auto-add email as verified ContactMethod based on OAuth claim
- `notify-on-contact-add: true` - Send security notification when contact is added

### Key Business Rules

1. **One pending invitation per email** - Creating new invitation auto-revokes existing PENDING for same email
2. **No duplicate users** - Reject invitation creation if user already exists with that email
3. **Email matching required** - JWT email must match invitation email for acceptance
4. **Role assignment** - Invitation specifies Role (entity reference), assigned on acceptance
5. **Auto-provisioning** - Acceptance creates User + ExternalIdentity + Role assignment in one transaction

## Domain Model

### Invitation Entity

```java
public record Invitation(
    UUID id,
    String email,              // Invited email (normalized: lowercase, trimmed)
    UUID roleId,               // Role to assign on acceptance
    String token,              // Unique acceptance token (random UUID)
    InvitationStatus status,   // PENDING, ACCEPTED, EXPIRED, REVOKED
    UUID invitedBy,            // Admin user who created invite
    Instant createdAt,
    Instant expiresAt,
    Instant acceptedAt         // Null until accepted
) {
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isPending() {
        return status == InvitationStatus.PENDING && !isExpired();
    }
}
```

### ContactMethod Entity

```java
public record ContactMethod(
    UUID id,
    UUID userId,
    ContactType type,           // EMAIL, PHONE
    String value,               // Normalized (lowercase email, E.164 phone)
    boolean verified,           // True if user confirmed ownership
    Instant verifiedAt,         // When verification completed (null if unverified)
    Instant createdAt
) {
    public static ContactMethod createUnverified(UUID userId, ContactType type, String value) {
        return new ContactMethod(UUID.randomUUID(), userId, type,
                                normalize(value), false, null, Instant.now());
    }

    public ContactMethod markVerified() {
        return new ContactMethod(id, userId, type, value,
                                true, Instant.now(), createdAt);
    }
}

public enum ContactType {
    EMAIL,
    PHONE
}
```

## Service Interface

### InvitationService

```java
public interface InvitationService {

    /**
     * Create invitation for email with role.
     * Auto-revokes any existing PENDING invitation for this email.
     * Current user extracted from Spring SecurityContext.
     *
     * @param email the email to invite (will be normalized)
     * @param roleId the role to assign on acceptance
     * @return the created invitation
     * @throws UserAlreadyExistsException if user already exists with this email
     */
    Invitation create(String email, UUID roleId);

    /**
     * Accept invitation after OAuth authentication.
     * Extracts JWT claims from SecurityContext (JwtAuthenticationToken).
     * Creates User, links ExternalIdentity, assigns Role, optionally creates verified ContactMethod.
     *
     * @param token the invitation token
     * @return the accepted invitation
     * @throws InvitationNotFoundException if token invalid
     * @throws InvitationExpiredException if expired
     * @throws InvitationAlreadyAcceptedException if already used
     * @throws EmailMismatchException if JWT email doesn't match invitation email
     * @throws EmailNotVerifiedException if require-verified-email is true but email not verified
     * @throws IllegalStateException if SecurityContext doesn't contain JwtAuthenticationToken
     */
    Invitation accept(String token);

    /**
     * Revoke pending invitation.
     * Current user extracted from Spring SecurityContext.
     *
     * @param invitationId the invitation to revoke
     * @throws InvitationNotFoundException if not found
     */
    void revoke(UUID invitationId);

    /**
     * List invitations with optional filtering.
     *
     * @param status filter by status (null for all)
     * @param since filter by created after this time (null for all)
     * @return list of invitations
     */
    List<Invitation> list(InvitationStatus status, Instant since);

    /**
     * Get invitation by token (for validation).
     *
     * @param token the invitation token
     * @return the invitation if found
     */
    Optional<Invitation> findByToken(String token);
}
```

**Note:** Service is Spring-aware and extracts current user (WhoPrincipal) from SecurityContext for audit fields. This is acceptable since Who is explicitly a Spring Boot library.

## REST API

### InvitationController

Provided in `who-web` module. Applications can use this controller or implement their own.

```java
@RestController
@RequestMapping("/api/invitations")
public class InvitationController {

    /**
     * Create invitation.
     * Requires ADMIN role or user.invite permission.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('user.invite')")
    public InvitationResponse create(@RequestBody CreateInvitationRequest request) {
        Invitation invitation = invitationService.create(request.email(), request.roleId());
        return InvitationResponse.from(invitation);
    }

    /**
     * Accept invitation.
     * Service extracts JWT claims from SecurityContext.
     *
     * Note: This endpoint requires a separate SecurityFilterChain that uses JWT validation
     * without WhoAuthenticationConverter (since user doesn't exist yet).
     * See "Security Configuration" section for example.
     */
    @PostMapping("/accept")
    public InvitationResponse accept(@RequestParam String token) {
        Invitation invitation = invitationService.accept(token);
        return InvitationResponse.from(invitation);
    }

    /**
     * Revoke invitation.
     * Requires ADMIN role or user.invite permission.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('user.invite')")
    public void revoke(@PathVariable UUID id) {
        invitationService.revoke(id);
    }

    /**
     * List invitations.
     * Requires ADMIN role or user.invite permission.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('user.invite')")
    public List<InvitationResponse> list(
        @RequestParam(required = false) InvitationStatus status,
        @RequestParam(required = false) Instant since) {
        return invitationService.list(status, since)
            .stream()
            .map(InvitationResponse::from)
            .toList();
    }
}
```

## Security Configuration

### Automatic Filter Chain Configuration (Default)

By default, Who library auto-configures the necessary security filter chains for invitation acceptance and API endpoints. This provides drop-in ready functionality with sensible defaults.

**Default configuration (zero-config required):**
```yaml
who:
  security:
    create-filter-chains: true                        # Default - Who creates filter chains automatically
    invitation-accept-path: /api/invitations/accept   # Configurable path for invitation acceptance
    api-path-pattern: /api/**                         # Configurable pattern for API endpoints
```

**Who's autoconfiguration provides:**
```java
@Bean
@Order(100)
@ConditionalOnProperty(prefix = "who.security", name = "create-filter-chains",
                       havingValue = "true", matchIfMissing = true)
@ConditionalOnMissingBean(name = "whoInvitationSecurityFilterChain")
public SecurityFilterChain whoInvitationSecurityFilterChain(
        HttpSecurity http,
        JwtDecoder jwtDecoder,
        WhoProperties properties) {
    http
        .securityMatcher(properties.getSecurity().getInvitationAcceptPath())
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoder)))
        .sessionManagement(session -> session.sessionCreationPolicy(STATELESS))
        .csrf(csrf -> csrf.disable());
    return http.build();
}

@Bean
@Order(200)
@ConditionalOnProperty(prefix = "who.security", name = "create-filter-chains",
                       havingValue = "true", matchIfMissing = true)
@ConditionalOnMissingBean(name = "whoApiSecurityFilterChain")
public SecurityFilterChain whoApiSecurityFilterChain(
        HttpSecurity http,
        Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter,
        WhoProperties properties) {
    http
        .securityMatcher(properties.getSecurity().getApiPathPattern())
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
        .sessionManagement(session -> session.sessionCreationPolicy(STATELESS))
        .csrf(csrf -> csrf.disable());
    return http.build();
}
```

### Configuration Options

**Option 1: Use defaults (recommended for most applications)**
```yaml
# No configuration needed - Who creates filter chains with default paths
```

**Option 2: Customize paths**
```yaml
who:
  security:
    invitation-accept-path: /auth/invite/accept  # Custom path
    api-path-pattern: /v1/api/**                 # Custom pattern
```

**Option 3: Full manual control**
```yaml
who:
  security:
    create-filter-chains: false  # Disable auto-configuration, provide your own
```

### Manual Filter Chain Configuration (Optional)

If you set `create-filter-chains: false` or need complete control, configure manually:

```java
@Configuration
public class SecurityConfig {

    @Bean
    @Order(1)  // IMPORTANT: Before main API filter chain
    public SecurityFilterChain invitationAcceptanceFilterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder) throws Exception {
        http
            .securityMatcher("/api/invitations/accept")
            .authorizeHttpRequests(authorize -> authorize
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.decoder(jwtDecoder))  // JWT validation only, no custom converter
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable());

        return http.build();
    }

    @Bean
    @Order(2)  // Main API filter chain with WhoAuthenticationConverter
    public SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter) throws Exception {
        http
            .securityMatcher("/api/**")
            .authorizeHttpRequests(authorize -> authorize
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))  // Uses WhoAuthenticationConverter
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable());

        return http.build();
    }
}
```

**Why this works:**
1. Order(1) chain matches `/api/invitations/accept` first
2. Uses standard JWT validation → creates `JwtAuthenticationToken` in SecurityContext
3. `InvitationService.accept()` extracts `Jwt` from `JwtAuthenticationToken`
4. Service validates claims, creates user, links identity
5. Subsequent requests use Order(2) chain with `WhoPrincipal`

**Service implementation extracts JWT:**
```java
@Service
public class DefaultInvitationService implements InvitationService {

    @Override
    public Invitation accept(String token) {
        // Extract JWT from SecurityContext
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
            throw new IllegalStateException(
                "Expected JwtAuthenticationToken. Ensure invitation acceptance endpoint " +
                "uses separate filter chain with JWT validation only."
            );
        }

        Jwt jwt = jwtAuth.getToken();
        String email = jwt.getClaimAsString("email");
        Boolean emailVerified = jwt.getClaimAsBoolean("email_verified");
        String issuer = jwt.getClaimAsString("iss");
        String subject = jwt.getClaimAsString("sub");

        // Validate and process invitation...
        return processAcceptance(token, email, emailVerified, issuer, subject);
    }
}
```

## Service Provider Interfaces (SPIs)

Applications must implement these SPIs. Autoconfiguration fails at startup if required beans are missing.

### InvitationNotifier (Required Always)

```java
/**
 * SPI for sending invitation emails.
 * Application must provide a bean implementing this interface.
 */
public interface InvitationNotifier {

    /**
     * Send invitation email to user.
     * Application constructs the acceptance URL based on its own deployment.
     *
     * @param invitation the invitation details (includes token, email, expiration, role)
     */
    void sendInvitation(Invitation invitation);
}
```

**Example implementation:**
```java
@Component
public class EmailInvitationNotifier implements InvitationNotifier {

    @Value("${app.base-url}")
    private String baseUrl;

    @Autowired
    private EmailService emailService;

    @Override
    public void sendInvitation(Invitation invitation) {
        String acceptUrl = baseUrl + "/invite/accept?token=" + invitation.token();

        emailService.send(
            invitation.email(),
            "You're invited to join!",
            buildEmailTemplate(acceptUrl, invitation.expiresAt())
        );
    }
}
```

### ContactVerificationNotifier (Required Conditionally)

Required if `trust-issuer-verification: false` or when manual verification is needed.

```java
/**
 * SPI for sending contact verification codes.
 */
public interface ContactVerificationNotifier {

    /**
     * Send verification code to contact method.
     *
     * @param contact the contact method to verify
     * @param code the verification code (generated by Who)
     */
    void sendVerificationCode(ContactMethod contact, String code);
}
```

### ContactConfirmationNotifier (Required if notify-on-contact-add: true)

```java
/**
 * SPI for notifying users when contact methods are added.
 * Sends "if this wasn't you" security notifications.
 */
public interface ContactConfirmationNotifier {

    /**
     * Notify user that a contact method was added to their account.
     * User can revoke if this wasn't them.
     *
     * @param contact the contact method that was added
     * @param user the user it was added to
     */
    void notifyContactAdded(ContactMethod contact, User user);
}
```

## Acceptance Flow

### SPA Flow (Client-Side OAuth)

1. User clicks invite link: `https://app.com/tasks.html?invitation=abc123`
2. SPA detects `invitation` query parameter, stores in sessionStorage
3. SPA initiates OAuth PKCE flow with authorization server
4. User completes OAuth, SPA receives JWT
5. SPA calls: `POST /api/invitations/accept?token=abc123` with JWT in Authorization header
6. Who's InvitationController extracts JWT claims, calls service
7. Service validates invitation, matches email, creates user, assigns role
8. If `trust-issuer-verification: true` AND `email_verified: true` → creates verified ContactMethod
9. If `notify-on-contact-add: true` → calls ContactConfirmationNotifier
10. Returns success, SPA redirects to application

### Server-Side OAuth Flow

1. User clicks: `https://app.com/invite/accept?token=abc123`
2. Application stores token in session, redirects to OAuth authorization
3. Spring Security handles OAuth callback, creates WhoPrincipal
4. Application retrieves token from session, calls `invitationService.accept()`
5. Rest of flow same as SPA

## Error Handling

### Exception Types

```java
// Invitation not found or invalid token
public class InvitationNotFoundException extends RuntimeException

// Invitation has expired
public class InvitationExpiredException extends RuntimeException

// Invitation already accepted
public class InvitationAlreadyAcceptedException extends RuntimeException

// JWT email doesn't match invitation email
public class EmailMismatchException extends RuntimeException

// require-verified-email is true but email_verified is false
public class EmailNotVerifiedException extends RuntimeException

// User already exists with this email
public class UserAlreadyExistsException extends RuntimeException
```

### HTTP Status Mapping

- `InvitationNotFoundException` → 404 Not Found
- `InvitationExpiredException` → 410 Gone
- `InvitationAlreadyAcceptedException` → 409 Conflict
- `EmailMismatchException` → 403 Forbidden
- `EmailNotVerifiedException` → 401 Unauthorized
- `UserAlreadyExistsException` → 409 Conflict

## Security Considerations

### Email Verification Trust Model

The security of invitation acceptance relies on:

1. **OAuth issuer whitelisting** (primary) - Configure Spring Security to only accept JWTs from trusted issuers (Google, Microsoft, GitHub, etc.)
2. **Email verification check** (secondary) - `require-verified-email` setting enforces OAuth provider verified the email
3. **Email matching** (always) - JWT email must match invitation email

**Recommendation:** Whitelist OAuth issuers in Spring Security config:
```java
@Bean
public JwtDecoder jwtDecoder() {
    OAuth2TokenValidator<Jwt> issuerValidator =
        new JwtIssuerValidator("https://accounts.google.com",
                               "https://github.com",
                               "http://localhost:8080");
    // ... configure decoder with validator
}
```

### Attack Scenarios

**Scenario: Attacker intercepts invite link**
- Attacker needs email access to get invite link
- If they have email access, they could use real OAuth anyway
- Risk: Low (requires email compromise)

**Scenario: Unverified OAuth provider**
- Attacker uses OAuth provider that doesn't verify emails
- Mitigated by: Issuer whitelisting (primary) + require-verified-email (secondary)
- Risk: Very low with whitelisting, low with require-verified-email

**Scenario: OAuth account compromise**
- Attacker compromises legitimate OAuth account
- Mitigated by: notify-on-contact-add sends security notification
- Risk: Low (user can revoke if notified)

## Contact Method Integration

When invitation is accepted:

```java
// In InvitationService.accept()
User user = userService.create(User.create(UUID.randomUUID(), UserStatus.ACTIVE));
externalIdentityService.link(user.id(), new ExternalIdentityKey(issuer, subject));
userRoleService.assign(user.id(), invitation.roleId());

if (trustIssuerVerification && emailVerified) {
    // Auto-create verified contact method
    ContactMethod contact = contactMethodService.create(
        user.id(),
        ContactType.EMAIL,
        email,
        true  // verified
    );

    if (notifyOnContactAdd) {
        contactConfirmationNotifier.notifyContactAdded(contact, user);
    }
}

invitation = invitation.accept();  // Sets acceptedAt, status = ACCEPTED
```

## Configuration Properties

```java
@ConfigurationProperties(prefix = "who")
public class WhoProperties {

    private Security security = new Security();
    private Invitations invitations = new Invitations();

    public static class Security {
        /**
         * Auto-create security filter chains for invitation acceptance and API endpoints.
         * When true, Who configures opinionated defaults. Set false for full manual control.
         * Default: true
         */
        private boolean createFilterChains = true;

        /**
         * Path for invitation acceptance endpoint (only used if createFilterChains is true).
         * Default: /api/invitations/accept
         */
        private String invitationAcceptPath = "/api/invitations/accept";

        /**
         * Path pattern for API endpoints (only used if createFilterChains is true).
         * Default: /api/**
         */
        private String apiPathPattern = "/api/**";

        /**
         * Reject authentication if email_verified claim is false.
         * Default: true
         */
        private boolean requireVerifiedEmail = true;

        /**
         * Auto-add email as verified ContactMethod when email_verified is true.
         * Default: true
         */
        private boolean trustIssuerVerification = true;

        /**
         * Send security notification when contact method is added.
         * Default: true
         */
        private boolean notifyOnContactAdd = true;

        // getters/setters
    }

    public static class Invitations {
        /**
         * Default invitation expiration in hours.
         * Default: 72 (3 days)
         */
        private int expirationHours = 72;

        // getters/setters
    }
}
```

## Testing Considerations

### Unit Tests

- Test invitation creation with auto-revocation of existing PENDING
- Test acceptance with email matching validation
- Test email verification enforcement based on settings
- Test contact method creation based on trust settings
- Test all error scenarios (expired, already accepted, email mismatch, etc.)

### Integration Tests

- Test full acceptance flow with real JWT
- Test SPI invocation (verify notifiers are called)
- Test SecurityContext extraction of current user
- Test transaction rollback on errors

### Example Test

```java
@Test
void acceptInvitation_createsUserAndLinksIdentity() {
    // Given: Pending invitation
    Invitation invitation = invitationService.create("alice@example.com", userRoleId);

    // When: Accept with matching email and verified claim
    Invitation accepted = invitationService.accept(
        invitation.token(),
        "alice@example.com",
        true,  // emailVerified
        "https://accounts.google.com",
        "google-user-123"
    );

    // Then: User created, identity linked, role assigned
    assertThat(accepted.status()).isEqualTo(InvitationStatus.ACCEPTED);

    User user = userService.findByEmail("alice@example.com").orElseThrow();
    assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);

    ExternalIdentity identity = externalIdentityService.findByKey(
        new ExternalIdentityKey("https://accounts.google.com", "google-user-123")
    ).orElseThrow();
    assertThat(identity.userId()).isEqualTo(user.id());

    assertThat(userRoleService.hasRole(user.id(), userRoleId)).isTrue();

    // And: Verified contact created (if trust-issuer-verification is true)
    ContactMethod contact = contactMethodService.findByUserAndType(user.id(), ContactType.EMAIL)
        .orElseThrow();
    assertThat(contact.value()).isEqualTo("alice@example.com");
    assertThat(contact.verified()).isTrue();
}
```

## Future Enhancements

**Not in scope for initial implementation:**

- Personal message in invitation (admin can add custom message to email)
- Invitation templates (different invitation types with different permissions)
- Bulk invitations (CSV upload)
- Invitation usage limits (max number of invitations per admin)
- Re-invitation with role change (allow inviting existing user to update role)
- Team/organization invitations (multi-tenant support)

## Summary

This design provides:
- ✅ Secure user onboarding via email invitations
- ✅ Configurable email verification trust model
- ✅ Clean SPI pattern for email sending
- ✅ Automatic user provisioning on acceptance
- ✅ Protection against duplicate users
- ✅ Full audit trail
- ✅ Spring Security integration
- ✅ Contact method management with verification
