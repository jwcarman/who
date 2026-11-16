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

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.sql.init.DatabaseInitializationMode;

/** Configuration properties for the Who identity framework. */
@ConfigurationProperties(prefix = "who")
public class WhoProperties {

  /** Creates who's properties with their defaults; Spring Boot binds {@code who.*} onto them. */
  public WhoProperties() {
    // defaults are set on the fields
  }

  /**
   * When to run Who's bundled DDL schema scripts: {@code always}, {@code embedded}, or {@code
   * never}.
   */
  private DatabaseInitializationMode initializeSchema = DatabaseInitializationMode.EMBEDDED;

  private Enrollment enrollment = new Enrollment();
  private ApiKey apiKey = new ApiKey();
  private MethodSecurity methodSecurity = new MethodSecurity();
  private EmailLink emailLink = new EmailLink();
  private Passkey passkey = new Passkey();
  private Jwt jwt = new Jwt();
  private Security security = new Security();
  private Oidc oidc = new Oidc();

  /**
   * Returns when to run Who's bundled DDL schema scripts.
   *
   * @return schema initialization mode
   */
  public DatabaseInitializationMode getInitializeSchema() {
    return initializeSchema;
  }

  /**
   * Sets when to run Who's bundled DDL schema scripts.
   *
   * @param initializeSchema schema initialization mode
   */
  public void setInitializeSchema(DatabaseInitializationMode initializeSchema) {
    this.initializeSchema = initializeSchema;
  }

  /**
   * Returns the enrollment module properties.
   *
   * @return enrollment properties
   */
  public Enrollment getEnrollment() {
    return enrollment;
  }

  /**
   * Sets the enrollment module properties.
   *
   * @param enrollment enrollment properties
   */
  public void setEnrollment(Enrollment enrollment) {
    this.enrollment = enrollment;
  }

  /**
   * Returns the API key module properties.
   *
   * @return api key properties
   */
  public ApiKey getApiKey() {
    return apiKey;
  }

  /**
   * Sets the API key module properties.
   *
   * @param apiKey api key properties
   */
  public void setApiKey(ApiKey apiKey) {
    this.apiKey = apiKey;
  }

  /**
   * Returns the method security properties.
   *
   * @return method security properties
   */
  public MethodSecurity getMethodSecurity() {
    return methodSecurity;
  }

  /**
   * Sets the method security properties.
   *
   * @param methodSecurity method security properties
   */
  public void setMethodSecurity(MethodSecurity methodSecurity) {
    this.methodSecurity = methodSecurity;
  }

  /**
   * Returns the email link module properties.
   *
   * @return email link properties
   */
  public EmailLink getEmailLink() {
    return emailLink;
  }

  /**
   * Sets the email link module properties.
   *
   * @param emailLink email link properties
   */
  public void setEmailLink(EmailLink emailLink) {
    this.emailLink = emailLink;
  }

  /**
   * Returns the passkey module properties.
   *
   * @return passkey properties
   */
  public Passkey getPasskey() {
    return passkey;
  }

  /**
   * Sets the passkey module properties.
   *
   * @param passkey passkey properties
   */
  public void setPasskey(Passkey passkey) {
    this.passkey = passkey;
  }

  /** Properties for the {@code who-passkey} module. */
  public static class Passkey {

    /** Creates the settings with their defaults. */
    public Passkey() {
      // defaults are set on the fields
    }

    /** Whether passkey sign-in is on. It is on whenever its module is on the classpath. */
    private boolean enabled = true;

    /**
     * Returns whether passkey sign-in is on.
     *
     * @return true unless turned off
     */
    public boolean isEnabled() {
      return enabled;
    }

    /**
     * Turns passkey sign-in off with {@code false}.
     *
     * @param enabled false to turn it off
     */
    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    /**
     * Relying party id: the application's own host, such as {@code app.example.com}. Required when
     * {@code who-passkey} is on the classpath; startup fails without it.
     */
    private String rpId;

    /** Relying party name that authenticators show. */
    private String rpName = "who";

    /**
     * Origins a registration or sign-in response may come from, each a scheme ({@code http} or
     * {@code https}), a host and an optional port, with no path, such as {@code
     * http://localhost:8080}; defaults to {@code https://<rp-id>}.
     */
    private Set<String> allowedOrigins = new LinkedHashSet<>();

    /**
     * Sender address of the email that tells the owner a passkey was added. The email is sent only
     * when it is set, {@code who-email-link} is on and a {@code JavaMailSender} exists; otherwise
     * who logs a warning at startup. It does not fall back to {@code who.email-link.from}.
     */
    private String from;

    /**
     * Returns the relying party id.
     *
     * @return the relying party id
     */
    public String getRpId() {
      return rpId;
    }

    /**
     * Sets the relying party id.
     *
     * @param rpId the relying party id
     */
    public void setRpId(String rpId) {
      this.rpId = rpId;
    }

    /**
     * Returns the relying party name.
     *
     * @return the relying party name
     */
    public String getRpName() {
      return rpName;
    }

    /**
     * Sets the relying party name.
     *
     * @param rpName the relying party name
     */
    public void setRpName(String rpName) {
      this.rpName = rpName;
    }

    /**
     * Returns the allowed origins; empty means {@code https://<rp-id>}.
     *
     * @return the allowed origins
     */
    public Set<String> getAllowedOrigins() {
      return allowedOrigins;
    }

    /**
     * Sets the allowed origins.
     *
     * @param allowedOrigins the allowed origins
     */
    public void setAllowedOrigins(Set<String> allowedOrigins) {
      this.allowedOrigins = allowedOrigins;
    }

    /**
     * Returns the sender address of passkey-added emails.
     *
     * @return the sender address, or {@code null}
     */
    public String getFrom() {
      return from;
    }

    /**
     * Sets the sender address of passkey-added emails.
     *
     * @param from the sender address
     */
    public void setFrom(String from) {
      this.from = from;
    }
  }

  /** Properties for the {@code who-email-link} module. */
  public static class EmailLink {

    /** Creates the settings with their defaults. */
    public EmailLink() {
      // defaults are set on the fields
    }

    /** Whether email-link sign-in is on. It is on whenever its module is on the classpath. */
    private boolean enabled = true;

    /**
     * Returns whether email-link sign-in is on.
     *
     * @return true unless turned off
     */
    public boolean isEnabled() {
      return enabled;
    }

    /**
     * Turns email-link sign-in off with {@code false}.
     *
     * @param enabled false to turn it off
     */
    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    /**
     * Page of the application that receives sign-in links; the token is appended as a {@code
     * #token=...} fragment. Required when {@code who-email-link} is on the classpath; startup fails
     * without it.
     */
    private String linkBaseUrl;

    /** How long a sign-in link stays valid. */
    private Duration tokenTtl = Duration.ofMinutes(5);

    /**
     * Minimum time between two sign-in links for the same address; must not exceed the token TTL.
     */
    private Duration resendInterval = Duration.ofSeconds(60);

    /**
     * Sender address of sign-in emails; required when email-link sign-in is on and links are sent
     * through Spring Boot's {@code JavaMailSender}.
     */
    private String from;

    /** Subject of sign-in emails. */
    private String subject = "Your sign-in link and code";

    /**
     * Page of the application that receives the link confirming an address being added or changed;
     * the token is appended as a {@code #confirm-address=...} fragment. Defaults to {@code
     * link-base-url}.
     */
    private String addressConfirmationUrl;

    /** Subject of the email confirming an address being added or changed. */
    private String addressConfirmationSubject = "Confirm your email address";

    /** Subject of the email telling an address it was replaced. */
    private String addressChangedSubject = "Your email address was changed";

    /** Number of characters in the typed sign-in code; at least 6. */
    private int codeLength = 6;

    /** How many wrong guesses a sign-in code survives before it is burnt; at least 1. */
    private int codeMaxAttempts = 5;

    /**
     * Secret keying the sign-in code hash; required when email-link sign-in is on, at least 32
     * characters.
     */
    private String codeSecret;

    /**
     * Returns the page that receives sign-in links.
     *
     * @return the link base URL
     */
    public String getLinkBaseUrl() {
      return linkBaseUrl;
    }

    /**
     * Sets the page that receives sign-in links.
     *
     * @param linkBaseUrl the link base URL
     */
    public void setLinkBaseUrl(String linkBaseUrl) {
      this.linkBaseUrl = linkBaseUrl;
    }

    /**
     * Returns how long a sign-in link stays valid.
     *
     * @return the token time to live
     */
    public Duration getTokenTtl() {
      return tokenTtl;
    }

    /**
     * Sets how long a sign-in link stays valid.
     *
     * @param tokenTtl the token time to live
     */
    public void setTokenTtl(Duration tokenTtl) {
      this.tokenTtl = tokenTtl;
    }

    /**
     * Returns the minimum time between two sign-in links for the same address.
     *
     * @return the resend interval
     */
    public Duration getResendInterval() {
      return resendInterval;
    }

    /**
     * Sets the minimum time between two sign-in links for the same address.
     *
     * @param resendInterval the resend interval
     */
    public void setResendInterval(Duration resendInterval) {
      this.resendInterval = resendInterval;
    }

    /**
     * Returns the sender address of sign-in emails.
     *
     * @return the from address
     */
    public String getFrom() {
      return from;
    }

    /**
     * Sets the sender address of sign-in emails.
     *
     * @param from the from address
     */
    public void setFrom(String from) {
      this.from = from;
    }

    /**
     * Returns the subject of sign-in emails.
     *
     * @return the subject
     */
    public String getSubject() {
      return subject;
    }

    /**
     * Sets the subject of sign-in emails.
     *
     * @param subject the subject
     */
    public void setSubject(String subject) {
      this.subject = subject;
    }

    /**
     * Returns the page that receives address confirmation links.
     *
     * @return the URL, or null for {@code link-base-url}
     */
    public String getAddressConfirmationUrl() {
      return addressConfirmationUrl;
    }

    /**
     * Sets the page that receives address confirmation links.
     *
     * @param addressConfirmationUrl the URL
     */
    public void setAddressConfirmationUrl(String addressConfirmationUrl) {
      this.addressConfirmationUrl = addressConfirmationUrl;
    }

    /**
     * Returns the subject of an address confirmation.
     *
     * @return the subject
     */
    public String getAddressConfirmationSubject() {
      return addressConfirmationSubject;
    }

    /**
     * Sets the subject of an address confirmation.
     *
     * @param addressConfirmationSubject the subject
     */
    public void setAddressConfirmationSubject(String addressConfirmationSubject) {
      this.addressConfirmationSubject = addressConfirmationSubject;
    }

    /**
     * Returns the subject of the notice to a replaced address.
     *
     * @return the subject
     */
    public String getAddressChangedSubject() {
      return addressChangedSubject;
    }

    /**
     * Sets the subject of the notice to a replaced address.
     *
     * @param addressChangedSubject the subject
     */
    public void setAddressChangedSubject(String addressChangedSubject) {
      this.addressChangedSubject = addressChangedSubject;
    }

    /**
     * Returns the number of characters in the typed sign-in code.
     *
     * @return the code length
     */
    public int getCodeLength() {
      return codeLength;
    }

    /**
     * Sets the number of characters in the typed sign-in code.
     *
     * @param codeLength the code length
     */
    public void setCodeLength(int codeLength) {
      this.codeLength = codeLength;
    }

    /**
     * Returns how many wrong guesses a sign-in code survives.
     *
     * @return the maximum number of attempts
     */
    public int getCodeMaxAttempts() {
      return codeMaxAttempts;
    }

    /**
     * Sets how many wrong guesses a sign-in code survives.
     *
     * @param codeMaxAttempts the maximum number of attempts
     */
    public void setCodeMaxAttempts(int codeMaxAttempts) {
      this.codeMaxAttempts = codeMaxAttempts;
    }

    /**
     * Returns the secret keying the sign-in code hash.
     *
     * @return the code secret
     */
    public String getCodeSecret() {
      return codeSecret;
    }

    /**
     * Sets the secret keying the sign-in code hash.
     *
     * @param codeSecret the code secret
     */
    public void setCodeSecret(String codeSecret) {
      this.codeSecret = codeSecret;
    }
  }

  /** Properties for method security. */
  public static class MethodSecurity {

    /** Creates the settings with their defaults. */
    public MethodSecurity() {
      // defaults are set on the fields
    }

    /** Whether Who enables method security so {@code @RequiresPermission} is enforced. */
    private boolean enabled = true;

    /**
     * Returns whether Who enables method security.
     *
     * @return true if enabled
     */
    public boolean isEnabled() {
      return enabled;
    }

    /**
     * Sets whether Who enables method security.
     *
     * @param enabled true to enable
     */
    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }
  }

  /** Properties for the {@code who-enrollment} module. */
  public static class Enrollment {

    /** Creates the settings with their defaults. */
    public Enrollment() {
      // defaults are set on the fields
    }

    /** Whether enrollment is on. It is on whenever its module is on the classpath. */
    private boolean enabled = true;

    /**
     * Returns whether enrollment is on.
     *
     * @return true unless turned off
     */
    public boolean isEnabled() {
      return enabled;
    }

    /**
     * Turns enrollment off with {@code false}.
     *
     * @param enabled false to turn it off
     */
    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    /** Duration after which a newly created enrollment token expires. */
    private Duration tokenExpiration = Duration.ofHours(24);

    /**
     * Returns the duration after which a newly created enrollment token expires.
     *
     * @return token expiration duration
     */
    public Duration getTokenExpiration() {
      return tokenExpiration;
    }

    /**
     * Sets the duration after which a newly created enrollment token expires.
     *
     * @param tokenExpiration token expiration duration
     */
    public void setTokenExpiration(Duration tokenExpiration) {
      this.tokenExpiration = tokenExpiration;
    }
  }

  /** Properties for the {@code who-apikey} module. */
  public static class ApiKey {

    /** Creates the settings with their defaults. */
    public ApiKey() {
      // defaults are set on the fields
    }

    /** Whether API-key sign-in is on. It is on whenever its module is on the classpath. */
    private boolean enabled = true;

    /**
     * Returns whether API-key sign-in is on.
     *
     * @return true unless turned off
     */
    public boolean isEnabled() {
      return enabled;
    }

    /**
     * Turns API-key sign-in off with {@code false}.
     *
     * @param enabled false to turn it off
     */
    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    /** HTTP header name that carries the API key. */
    private String headerName = "X-API-Key";

    /**
     * Returns the HTTP header name that carries the API key.
     *
     * @return header name
     */
    public String getHeaderName() {
      return headerName;
    }

    /**
     * Sets the HTTP header name that carries the API key.
     *
     * @param headerName header name
     */
    public void setHeaderName(String headerName) {
      this.headerName = headerName;
    }

    /**
     * Starts every new API key, so a secret scanner can recognize a leaked one: letters, digits and
     * {@code _}, 2 to 20 characters, ending in {@code _}. Keys made under an earlier prefix keep
     * working.
     */
    private String prefix = "who_";

    /**
     * Returns the prefix that starts every new API key.
     *
     * @return the prefix
     */
    public String getPrefix() {
      return prefix;
    }

    /**
     * Sets the prefix that starts every new API key.
     *
     * @param prefix the prefix
     */
    public void setPrefix(String prefix) {
      this.prefix = prefix;
    }

    /** The longest lifetime a new API key may be given. */
    private Duration maxLifetime = Duration.ofDays(365);

    /**
     * Returns the longest lifetime a new API key may be given.
     *
     * @return the maximum lifetime
     */
    public Duration getMaxLifetime() {
      return maxLifetime;
    }

    /**
     * Sets the longest lifetime a new API key may be given; positive, or startup fails.
     *
     * @param maxLifetime the maximum lifetime
     */
    public void setMaxLifetime(Duration maxLifetime) {
      this.maxLifetime = requireNonNull(maxLifetime, "who.api-key.max-lifetime must not be null");
    }

    /** How often, at most, an API key's last use is written: a busy key costs one write each. */
    private Duration lastUsedInterval = Duration.ofMinutes(5);

    /**
     * Returns how often, at most, an API key's last use is written.
     *
     * @return the interval
     */
    public Duration getLastUsedInterval() {
      return lastUsedInterval;
    }

    /**
     * Sets how often, at most, an API key's last use is written; positive, or startup fails.
     *
     * @param lastUsedInterval the interval
     */
    public void setLastUsedInterval(Duration lastUsedInterval) {
      this.lastUsedInterval =
          requireNonNull(lastUsedInterval, "who.api-key.last-used-interval must not be null");
    }
  }

  /**
   * Returns the JWT module properties.
   *
   * @return JWT properties
   */
  public Jwt getJwt() {
    return jwt;
  }

  /**
   * Sets the JWT module properties.
   *
   * @param jwt JWT properties
   */
  public void setJwt(Jwt jwt) {
    this.jwt = jwt;
  }

  /** Properties for the {@code who-jwt} module. */
  public static class Jwt {

    /** Creates the settings with their defaults. */
    public Jwt() {
      // defaults are set on the fields
    }

    /** Whether JWT sign-in is on. It is on whenever its module is on the classpath. */
    private boolean enabled = true;

    /**
     * Returns whether JWT sign-in is on.
     *
     * @return true unless turned off
     */
    public boolean isEnabled() {
      return enabled;
    }

    /**
     * Turns JWT sign-in off with {@code false}.
     *
     * @param enabled false to turn it off
     */
    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }
  }

  /**
   * Returns the security chain properties.
   *
   * @return security properties
   */
  public Security getSecurity() {
    return security;
  }

  /**
   * Sets the security chain properties.
   *
   * @param security security properties
   */
  public void setSecurity(Security security) {
    this.security = security;
  }

  /**
   * Returns the provider sign-in settings.
   *
   * @return the settings
   */
  public Oidc getOidc() {
    return oidc;
  }

  /**
   * Sets the provider sign-in settings.
   *
   * @param oidc the settings
   */
  public void setOidc(Oidc oidc) {
    this.oidc = oidc;
  }

  /** Properties for who's security chain. */
  public static class Security {

    /** Creates the settings with their defaults. */
    public Security() {
      // defaults are set on the fields
    }

    /**
     * Paths of the application a signed-out request may reach, such as its sign-in page and the
     * assets that page loads ({@code /signin}, {@code /assets/**}). who's own sign-in endpoints and
     * {@code /error} are public already.
     */
    private List<String> publicPaths = new ArrayList<>();

    /** The cookie that remembers which way someone last signed in. */
    private LastSignInCookie lastSignInCookie = new LastSignInCookie();

    /**
     * How recently the browser session must have signed in to add a way to sign in: registering a
     * passkey, linking a provider account, or adding or changing an email address. Checked when the
     * request starts and when it is confirmed.
     */
    private Duration recentSignIn = Duration.ofMinutes(10);

    /**
     * Returns how recently the session must have signed in to add a way to sign in.
     *
     * @return the window
     */
    public Duration getRecentSignIn() {
      return recentSignIn;
    }

    /**
     * Sets how recently the session must have signed in to add a way to sign in.
     *
     * @param recentSignIn the window
     */
    public void setRecentSignIn(Duration recentSignIn) {
      this.recentSignIn = recentSignIn;
    }

    /**
     * Returns the last-sign-in cookie's settings.
     *
     * @return the settings
     */
    public LastSignInCookie getLastSignInCookie() {
      return lastSignInCookie;
    }

    /**
     * Sets the last-sign-in cookie's settings.
     *
     * @param lastSignInCookie the settings
     */
    public void setLastSignInCookie(LastSignInCookie lastSignInCookie) {
      this.lastSignInCookie = lastSignInCookie;
    }

    /**
     * Returns the application's public paths.
     *
     * @return path patterns
     */
    public List<String> getPublicPaths() {
      return publicPaths;
    }

    /**
     * Sets the application's public paths.
     *
     * @param publicPaths path patterns
     */
    public void setPublicPaths(List<String> publicPaths) {
      this.publicPaths = publicPaths;
    }

    /** Settings for the cookie that remembers which way someone last signed in. */
    public static class LastSignInCookie {

      /** Creates the settings with their defaults. */
      public LastSignInCookie() {
        // defaults are set on the fields
      }

      /** Whether who sets the cookie after a browser sign-in. */
      private boolean enabled = true;

      /**
       * Returns whether who sets the cookie.
       *
       * @return {@code true} unless turned off
       */
      public boolean isEnabled() {
        return enabled;
      }

      /**
       * Sets whether who sets the cookie.
       *
       * @param enabled {@code false} to turn it off
       */
      public void setEnabled(boolean enabled) {
        this.enabled = enabled;
      }
    }
  }

  /**
   * Properties for sign-in with Google, GitHub or any OpenID Connect provider ({@code who-oidc}).
   */
  public static class Oidc {

    /** Creates the settings with their defaults. */
    public Oidc() {
      // defaults are set on the fields
    }

    /** Whether provider sign-in is on; it is whenever {@code who-oidc} is on the classpath. */
    private boolean enabled = true;

    /** Where a provider sign-in lands: a path on this site. */
    private String successUrl = "/";

    /**
     * Where a pending sign-up ({@code pending=sign-up}) and every failed provider sign-in ({@code
     * error=<code>}) land: a path on this site, such as the sign-in page.
     */
    private String failureUrl = "/";

    /**
     * Where a link the signed-in person asked for lands: {@code pending=link} to confirm it, or
     * {@code error=already-linked}. A path on this site, such as the account settings page;
     * defaults to {@code success-url}.
     */
    private String linkUrl;

    /** How long a pending sign-up waits for the person to confirm it. */
    private Duration signUpWindow = Duration.ofMinutes(10);

    /** Per client registration settings, by registration id. */
    private Map<String, Provider> providers = new LinkedHashMap<>();

    /**
     * Returns whether provider sign-in is on.
     *
     * @return {@code true} unless turned off
     */
    public boolean isEnabled() {
      return enabled;
    }

    /**
     * Sets whether provider sign-in is on.
     *
     * @param enabled {@code false} to turn it off
     */
    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    /**
     * Returns where a provider sign-in lands.
     *
     * @return a path on this site
     */
    public String getSuccessUrl() {
      return successUrl;
    }

    /**
     * Sets where a provider sign-in lands.
     *
     * @param successUrl a path on this site
     */
    public void setSuccessUrl(String successUrl) {
      this.successUrl = successUrl;
    }

    /**
     * Returns where every other outcome lands.
     *
     * @return a path on this site
     */
    public String getFailureUrl() {
      return failureUrl;
    }

    /**
     * Sets where every other outcome lands.
     *
     * @param failureUrl a path on this site
     */
    public void setFailureUrl(String failureUrl) {
      this.failureUrl = failureUrl;
    }

    /**
     * Returns where a link lands.
     *
     * @return a path on this site, or null for {@code success-url}
     */
    public String getLinkUrl() {
      return linkUrl;
    }

    /**
     * Sets where a link lands.
     *
     * @param linkUrl a path on this site
     */
    public void setLinkUrl(String linkUrl) {
      this.linkUrl = linkUrl;
    }

    /**
     * Returns how long a pending sign-up waits.
     *
     * @return the window
     */
    public Duration getSignUpWindow() {
      return signUpWindow;
    }

    /**
     * Sets how long a pending sign-up waits.
     *
     * @param signUpWindow the window
     */
    public void setSignUpWindow(Duration signUpWindow) {
      this.signUpWindow = signUpWindow;
    }

    /**
     * Returns the per registration settings.
     *
     * @return settings by registration id
     */
    public Map<String, Provider> getProviders() {
      return providers;
    }

    /**
     * Sets the per registration settings.
     *
     * @param providers settings by registration id
     */
    public void setProviders(Map<String, Provider> providers) {
      this.providers = providers;
    }

    /** Settings for one client registration. */
    public static class Provider {

      /** Creates the settings with their defaults. */
      public Provider() {
        // defaults are set on the fields
      }

      /** Whether who signs people in with this registration. */
      private boolean enabled = true;

      /**
       * The issuer credentials from this registration are stored under. Required for a provider
       * configured without {@code issuer-uri}; must equal it when both are set.
       */
      private String issuer;

      /**
       * The user-info attribute that identifies the account, for a provider that is not OpenID
       * Connect (github.com has a default).
       */
      private String subjectAttribute;

      /**
       * Whether the provider's verified email may be used. Defaults to true for Google and
       * github.com, false for every other provider.
       */
      private Boolean trustEmail;

      /**
       * Returns whether who signs people in with this registration.
       *
       * @return {@code true} unless turned off
       */
      public boolean isEnabled() {
        return enabled;
      }

      /**
       * Sets whether who signs people in with this registration.
       *
       * @param enabled {@code false} to leave it out
       */
      public void setEnabled(boolean enabled) {
        this.enabled = enabled;
      }

      /**
       * Returns the issuer credentials are stored under.
       *
       * @return the issuer, or null
       */
      public String getIssuer() {
        return issuer;
      }

      /**
       * Sets the issuer credentials are stored under.
       *
       * @param issuer the issuer
       */
      public void setIssuer(String issuer) {
        this.issuer = issuer;
      }

      /**
       * Returns the attribute that identifies the account.
       *
       * @return the attribute, or null
       */
      public String getSubjectAttribute() {
        return subjectAttribute;
      }

      /**
       * Sets the attribute that identifies the account.
       *
       * @param subjectAttribute the attribute
       */
      public void setSubjectAttribute(String subjectAttribute) {
        this.subjectAttribute = subjectAttribute;
      }

      /**
       * Returns whether the provider's verified email may be used.
       *
       * @return the setting, or null for the default
       */
      public Boolean getTrustEmail() {
        return trustEmail;
      }

      /**
       * Sets whether the provider's verified email may be used.
       *
       * @param trustEmail the setting
       */
      public void setTrustEmail(Boolean trustEmail) {
        this.trustEmail = trustEmail;
      }
    }
  }
}
