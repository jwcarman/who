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
}
