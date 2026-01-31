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

import tools.jackson.databind.JsonNode;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.service.PreferencesService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for managing user preferences.
 */
@RestController
@RequestMapping("/api/who/preferences")
public class PreferencesController {

    private final PreferencesService preferencesService;

    public PreferencesController(PreferencesService preferencesService) {
        this.preferencesService = preferencesService;
    }

    @GetMapping("/{namespace}")
    public JsonNode getPreferences(
            @AuthenticationPrincipal WhoPrincipal principal,
            @PathVariable String namespace) {
        return preferencesService.getPreferences(principal.userId(), namespace, JsonNode.class);
    }

    @PutMapping("/{namespace}")
    public void setPreferences(
            @AuthenticationPrincipal WhoPrincipal principal,
            @PathVariable String namespace,
            @RequestBody JsonNode preferences) {
        preferencesService.setPreferences(principal.userId(), namespace, preferences);
    }
}
