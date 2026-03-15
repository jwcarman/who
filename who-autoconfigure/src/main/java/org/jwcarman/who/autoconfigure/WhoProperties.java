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

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for the Who identity framework.
 *
 * <p>All properties are informational hints documenting where each module's schema DDL lives.
 * Applications that want automatic schema initialization should configure
 * {@code spring.sql.init.schema-locations} (or use {@code ResourceDatabasePopulator}) pointing
 * at these locations.
 */
@ConfigurationProperties(prefix = "who")
public class WhoProperties {

    private Jdbc jdbc = new Jdbc();
    private Rbac rbac = new Rbac();
    private Jwt jwt = new Jwt();
    private Enrollment enrollment = new Enrollment();

    /**
     * Returns the JDBC module properties.
     *
     * @return jdbc properties
     */
    public Jdbc getJdbc() {
        return jdbc;
    }

    /**
     * Sets the JDBC module properties.
     *
     * @param jdbc jdbc properties
     */
    public void setJdbc(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns the RBAC module properties.
     *
     * @return rbac properties
     */
    public Rbac getRbac() {
        return rbac;
    }

    /**
     * Sets the RBAC module properties.
     *
     * @param rbac rbac properties
     */
    public void setRbac(Rbac rbac) {
        this.rbac = rbac;
    }

    /**
     * Returns the JWT module properties.
     *
     * @return jwt properties
     */
    public Jwt getJwt() {
        return jwt;
    }

    /**
     * Sets the JWT module properties.
     *
     * @param jwt jwt properties
     */
    public void setJwt(Jwt jwt) {
        this.jwt = jwt;
    }

    /**
     * Properties for the {@code who-jdbc} module.
     */
    public static class Jdbc {

        private List<String> schemaLocations = new ArrayList<>(
                List.of("classpath:org/jwcarman/who/jdbc/schema.sql"));

        /**
         * Locations of the who-jdbc DDL schema files.
         *
         * @return schema locations
         */
        public List<String> getSchemaLocations() {
            return schemaLocations;
        }

        /**
         * Sets the locations of the who-jdbc DDL schema files.
         *
         * @param schemaLocations schema locations
         */
        public void setSchemaLocations(List<String> schemaLocations) {
            this.schemaLocations = schemaLocations;
        }
    }

    /**
     * Properties for the {@code who-rbac} module.
     */
    public static class Rbac {

        private List<String> schemaLocations = new ArrayList<>(
                List.of("classpath:org/jwcarman/who/rbac/schema.sql"));

        /**
         * Locations of the who-rbac DDL schema files.
         *
         * @return schema locations
         */
        public List<String> getSchemaLocations() {
            return schemaLocations;
        }

        /**
         * Sets the locations of the who-rbac DDL schema files.
         *
         * @param schemaLocations schema locations
         */
        public void setSchemaLocations(List<String> schemaLocations) {
            this.schemaLocations = schemaLocations;
        }
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
     * Properties for the {@code who-enrollment} module.
     */
    public static class Enrollment {

        private int expirationHours = 24;

        /**
         * Hours after which a newly created enrollment token expires.
         *
         * @return expiration hours
         */
        public int getExpirationHours() {
            return expirationHours;
        }

        /**
         * Sets the hours after which a newly created enrollment token expires.
         *
         * @param expirationHours expiration hours
         */
        public void setExpirationHours(int expirationHours) {
            this.expirationHours = expirationHours;
        }
    }

    /**
     * Properties for the {@code who-jwt} module.
     */
    public static class Jwt {

        private List<String> schemaLocations = new ArrayList<>(
                List.of("classpath:org/jwcarman/who/jwt/schema.sql"));

        /**
         * Locations of the who-jwt DDL schema files.
         *
         * @return schema locations
         */
        public List<String> getSchemaLocations() {
            return schemaLocations;
        }

        /**
         * Sets the locations of the who-jwt DDL schema files.
         *
         * @param schemaLocations schema locations
         */
        public void setSchemaLocations(List<String> schemaLocations) {
            this.schemaLocations = schemaLocations;
        }
    }
}
