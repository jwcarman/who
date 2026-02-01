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
package org.jwcarman.who.example.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class OAuthCallbackController {

    @GetMapping("/authorized")
    public String authorized(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            @RequestParam(required = false, name = "error_description") String errorDescription,
            Model model) {

        if (error != null) {
            model.addAttribute("error", error);
            model.addAttribute("errorDescription", errorDescription != null ? errorDescription : "No description");
            return "authorized";
        }

        String curlCommand = String.format(
            "curl -X POST http://localhost:8080/oauth2/token " +
            "-u demo-client:secret " +
            "-H 'Content-Type: application/x-www-form-urlencoded' " +
            "-d 'grant_type=authorization_code&code=%s&redirect_uri=http://127.0.0.1:8080/authorized'",
            code != null ? code : "YOUR_CODE"
        );

        model.addAttribute("code", code);
        model.addAttribute("curlCommand", curlCommand);
        return "authorized";
    }
}
