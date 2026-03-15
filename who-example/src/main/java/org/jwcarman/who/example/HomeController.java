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

import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.jwt.JwtCredentialRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    private final WhoService whoService;
    private final JwtCredentialRepository jwtCredentialRepository;

    public HomeController(WhoService whoService, JwtCredentialRepository jwtCredentialRepository) {
        this.whoService = whoService;
        this.jwtCredentialRepository = jwtCredentialRepository;
    }

    @GetMapping("/")
    public String home(
            @AuthenticationPrincipal OAuth2User oauth2User,
            @RegisteredOAuth2AuthorizedClient("demo-client") OAuth2AuthorizedClient authorizedClient,
            Model model) {

        if (oauth2User == null) {
            return "index";
        }

        String username = oauth2User.getName();
        model.addAttribute("username", username);

        // Look up the WhoPrincipal for this user via JWT credential
        String issuer = "http://localhost:8080";
        jwtCredentialRepository.findByIssuerAndSubject(issuer, username).ifPresent(cred -> {
            WhoPrincipal principal = whoService.resolve(cred).orElse(null);
            if (principal != null) {
                model.addAttribute("identityId", principal.identityId());
                model.addAttribute("permissions", principal.permissions());
            }
        });

        // Retrieve the access token for curl examples
        if (authorizedClient != null && authorizedClient.getAccessToken() != null) {
            model.addAttribute("accessToken", authorizedClient.getAccessToken().getTokenValue());
        }

        return "index";
    }
}
