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
package org.jwcarman.who.examples.spa;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The front end's own pages. The browser may load any of them directly (a bookmark, a reload, the
 * link in an email), so each one answers {@code index.html} and React Router takes it from there.
 * They are listed in {@code who.security.public-paths} too: the page shell is public, and the data
 * behind it, under {@code /api}, is not.
 */
@Controller
class ClientRoutes {

  @GetMapping({"/", "/signin", "/signin/link", "/members", "/profile"})
  String index() {
    return "forward:/index.html";
  }
}
