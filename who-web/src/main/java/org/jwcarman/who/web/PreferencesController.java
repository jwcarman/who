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
package org.jwcarman.who.web;

import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.service.PreferencesService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * REST controller for managing user preferences.
 * <p>
 * This controller provides endpoints for storing and retrieving user-specific preferences
 * organized by namespace. Preferences are stored as JSON and can be used for application
 * settings, UI customizations, or any user-specific configuration data.
 * <p>
 * The base path is configurable via {@code who.web.mount-point} property (defaults to {@code /api/who}).
 */
@RestController
@RequestMapping("${who.web.mount-point:/api/who}/preferences")
public class PreferencesController {

    private final PreferencesService preferencesService;

    /**
     * Constructs a new PreferencesController with required services.
     *
     * @param preferencesService the preferences service for managing user preferences
     */
    public PreferencesController(PreferencesService preferencesService) {
        this.preferencesService = preferencesService;
    }

    /**
     * Retrieves user preferences for a specific namespace.
     * <p>
     * HTTP GET to {@code /preferences/{namespace}}
     * <p>
     * Returns the preferences JSON for the authenticated user within the specified namespace.
     * Namespaces allow different parts of an application to store separate preference sets.
     * <p>
     * The authenticated user is automatically extracted from the security context.
     *
     * @param principal the authenticated user principal (automatically injected)
     * @param namespace the preference namespace (e.g., "ui", "notifications", "dashboard")
     * @return the preferences as a JSON object, or null if no preferences exist for this namespace
     */
    @GetMapping("/{namespace}")
    public JsonNode getPreferences(
            @AuthenticationPrincipal WhoPrincipal principal,
            @PathVariable String namespace) {
        return preferencesService.getPreferences(principal.userId(), namespace, JsonNode.class);
    }

    /**
     * Sets or updates user preferences for a specific namespace.
     * <p>
     * HTTP PUT to {@code /preferences/{namespace}}
     * <p>
     * Stores the provided JSON preferences for the authenticated user within the specified namespace.
     * If preferences already exist for this namespace, they will be replaced with the new values.
     * <p>
     * The authenticated user is automatically extracted from the security context.
     *
     * @param principal the authenticated user principal (automatically injected)
     * @param namespace the preference namespace (e.g., "ui", "notifications", "dashboard")
     * @param preferences the preferences JSON object to store
     */
    @PutMapping("/{namespace}")
    public void setPreferences(
            @AuthenticationPrincipal WhoPrincipal principal,
            @PathVariable String namespace,
            @RequestBody JsonNode preferences) {
        preferencesService.setPreferences(principal.userId(), namespace, preferences);
    }
}
