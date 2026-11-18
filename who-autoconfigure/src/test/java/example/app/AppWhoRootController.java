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
package example.app;

import org.jwcarman.who.spring.security.WhoPaths;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** An application's own controller that uses who's root, outside who's packages. */
@RestController
@RequestMapping("/app")
public class AppWhoRootController {

  /**
   * Two paths under who's root.
   *
   * @return a greeting
   */
  @GetMapping({WhoPaths.ROOT + "/extra/a", WhoPaths.ROOT + "/extra/b"})
  public String extra() {
    return "extra";
  }
}
