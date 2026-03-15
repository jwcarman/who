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
import org.springframework.boot.sql.init.DatabaseInitializationMode;

import java.time.Duration;

/**
 * Configuration properties for the Who identity framework.
 */
@ConfigurationProperties(prefix = "who")
public class WhoProperties {

    /** When to run Who's bundled DDL schema scripts: {@code always}, {@code embedded}, or {@code never}. */
    private DatabaseInitializationMode initializeSchema = DatabaseInitializationMode.EMBEDDED;

    private Enrollment enrollment = new Enrollment();
    private ApiKey apiKey = new ApiKey();

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
     * Properties for the {@code who-enrollment} module.
     */
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

    /**
     * Properties for the {@code who-apikey} module.
     */
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
