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
package org.jwcarman.who.autoconfig;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Who library.
 */
@ConfigurationProperties(prefix = "who")
public class WhoProperties {

    private Web web = new Web();
    private Provisioning provisioning = new Provisioning();
    private Invitation invitation = new Invitation();

    public Web getWeb() {
        return web;
    }

    public void setWeb(Web web) {
        this.web = web;
    }

    public Provisioning getProvisioning() {
        return provisioning;
    }

    public void setProvisioning(Provisioning provisioning) {
        this.provisioning = provisioning;
    }

    public Invitation getInvitation() {
        return invitation;
    }

    public void setInvitation(Invitation invitation) {
        this.invitation = invitation;
    }

    public static class Web {
        /**
         * Base path (mount point) for Who web controllers.
         * Default is "/api/who".
         */
        private String mountPoint = "/api/who";

        public String getMountPoint() {
            return mountPoint;
        }

        public void setMountPoint(String mountPoint) {
            this.mountPoint = mountPoint;
        }
    }

    public static class Provisioning {
        /**
         * Auto-provision new users for unknown identities.
         * If false, unknown identities are denied access.
         */
        private boolean autoProvision = false;

        public boolean isAutoProvision() {
            return autoProvision;
        }

        public void setAutoProvision(boolean autoProvision) {
            this.autoProvision = autoProvision;
        }
    }

    public static class Invitation {
        /**
         * Number of hours before an invitation expires.
         * Default is 24 hours.
         */
        private int expirationHours = 24;

        /**
         * Whether to require verified email before accepting invitation.
         * Default is true.
         */
        private boolean requireVerifiedEmail = true;

        /**
         * Whether to trust email verification from the issuer.
         * If true, emails verified by the issuer are automatically trusted.
         * Default is false.
         */
        private boolean trustIssuerVerification = false;

        public int getExpirationHours() {
            return expirationHours;
        }

        public void setExpirationHours(int expirationHours) {
            this.expirationHours = expirationHours;
        }

        public boolean isRequireVerifiedEmail() {
            return requireVerifiedEmail;
        }

        public void setRequireVerifiedEmail(boolean requireVerifiedEmail) {
            this.requireVerifiedEmail = requireVerifiedEmail;
        }

        public boolean isTrustIssuerVerification() {
            return trustIssuerVerification;
        }

        public void setTrustIssuerVerification(boolean trustIssuerVerification) {
            this.trustIssuerVerification = trustIssuerVerification;
        }
    }
}
