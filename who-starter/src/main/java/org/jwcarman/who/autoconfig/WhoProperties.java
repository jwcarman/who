package org.jwcarman.who.autoconfig;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Who library.
 */
@ConfigurationProperties(prefix = "who")
public class WhoProperties {

    private Provisioning provisioning = new Provisioning();

    public Provisioning getProvisioning() {
        return provisioning;
    }

    public void setProvisioning(Provisioning provisioning) {
        this.provisioning = provisioning;
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
}
