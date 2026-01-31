package org.jwcarman.who.web;

import com.fasterxml.jackson.databind.JsonNode;
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
