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

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.sql.init.DatabaseInitializationMode;

/** Configuration properties for the Who identity framework. */
@ConfigurationProperties(prefix = "who")
public class WhoProperties {

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

    /**
     * Relying party id: the application's own host, such as {@code app.example.com}. Setting it
     * turns passkeys on; when unset, no passkey beans are registered.
     */
    private String rpId;

    /** Relying party name that authenticators show. */
    private String rpName = "who";

    /**
     * Origins a registration or sign-in response may come from; defaults to {@code
     * https://<rp-id>}.
     */
    private Set<String> allowedOrigins = new LinkedHashSet<>();

    /** How recently the session must have signed in to register a passkey. */
    private Duration registrationWindow = Duration.ofMinutes(10);

    /**
     * Sender address of the email that tells the owner a passkey was added. The email is sent only
     * when it is set, {@code who-email-link} is on and a {@code JavaMailSender} exists.
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
     * Returns the registration window.
     *
     * @return the registration window
     */
    public Duration getRegistrationWindow() {
      return registrationWindow;
    }

    /**
     * Sets the registration window.
     *
     * @param registrationWindow the registration window
     */
    public void setRegistrationWindow(Duration registrationWindow) {
      this.registrationWindow = registrationWindow;
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

    /**
     * Page of the application that receives sign-in links; the token is appended as a {@code
     * #token=...} fragment. Setting it turns email-link sign-in on; when unset, no email-link beans
     * are registered.
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
    private String subject = "Your sign-in link";

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
  }

  /** Properties for method security. */
  public static class MethodSecurity {

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
  }
}
