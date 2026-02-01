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
package org.jwcarman.who.example;

import org.jwcarman.who.core.domain.Invitation;
import org.jwcarman.who.core.spi.InvitationNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Simple invitation notifier for demonstration purposes.
 * <p>
 * This implementation logs invitation details to demonstrate the notification flow.
 * In a production application, this would send actual emails using a service like
 * SendGrid, Amazon SES, or a custom SMTP implementation.
 * </p>
 * <p>
 * Example production implementation:
 * <pre>
 * &#64;Component
 * public class EmailInvitationNotifier implements InvitationNotifier {
 *     private final EmailService emailService;
 *     private final String baseUrl;
 *
 *     &#64;Override
 *     public void sendInvitation(Invitation invitation) {
 *         String acceptanceUrl = buildAcceptanceUrl(invitation.token());
 *         emailService.send(
 *             invitation.email(),
 *             "You've been invited!",
 *             "Click here to accept: " + acceptanceUrl
 *         );
 *     }
 * }
 * </pre>
 */
@Component
public class SimpleInvitationNotifier implements InvitationNotifier {

    private static final Logger log = LoggerFactory.getLogger(SimpleInvitationNotifier.class);

    private final String baseUrl;

    /**
     * Creates a new SimpleInvitationNotifier.
     *
     * @param baseUrl the base URL of the application (e.g., "http://localhost:8080")
     */
    public SimpleInvitationNotifier(
            @Value("${invitation.base-url:http://localhost:8080}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * Sends an invitation notification by logging the details.
     * <p>
     * In a production implementation, this would:
     * <ol>
     *   <li>Construct the acceptance URL with the invitation token</li>
     *   <li>Render an email template with invitation details</li>
     *   <li>Send the email via an email service provider</li>
     *   <li>Handle any errors (retry logic, dead letter queue, etc.)</li>
     * </ol>
     * </p>
     *
     * @param invitation the invitation to send
     */
    @Override
    public void sendInvitation(Invitation invitation) {
        String acceptanceUrl = buildAcceptanceUrl(invitation.token());

        if(log.isInfoEnabled()){
            log.info("=".repeat(80));
            log.info("INVITATION NOTIFICATION");
            log.info("=".repeat(80));
            log.info("To: {}", invitation.email());
            log.info("Role ID: {}", invitation.roleId());
            log.info("Expires: {}", invitation.expiresAt());
            log.info("Acceptance URL: {}", acceptanceUrl);
            log.info("=".repeat(80));
            log.info("");
            log.info("In production, this would send an email containing:");
            log.info("  - A personalized greeting");
            log.info("  - Information about the role they're being invited to");
            log.info("  - The acceptance link (valid until {})", invitation.expiresAt());
            log.info("  - Instructions on what to do if they didn't expect this invitation");
            log.info("=".repeat(80));
        }
    }

    /**
     * Constructs the invitation acceptance URL.
     * <p>
     * The URL format should match your application's routing structure.
     * This example assumes a REST endpoint at /api/invitations/accept/{token}
     * </p>
     *
     * @param token the invitation token
     * @return the full acceptance URL
     */
    private String buildAcceptanceUrl(String token) {
        return String.format("%s/api/invitations/accept/%s", baseUrl, token);
    }
}
