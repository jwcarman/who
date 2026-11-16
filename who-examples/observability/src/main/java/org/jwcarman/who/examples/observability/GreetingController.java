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
package org.jwcarman.who.examples.observability;

import java.util.concurrent.atomic.AtomicReference;

import org.jwcarman.who.spring.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The API the keys call: just enough to have a read and a write. */
@RestController
class GreetingController {

  /**
   * The greeting.
   *
   * @param greeting what it says
   */
  record Greeting(String greeting) {}

  private final AtomicReference<String> greeting = new AtomicReference<>("Hello from who");

  @GetMapping("/api/greeting")
  @RequiresPermission(Permissions.GREETING_READ)
  Greeting read() {
    return new Greeting(greeting.get());
  }

  @PostMapping("/api/greeting")
  @RequiresPermission(Permissions.GREETING_WRITE)
  Greeting write(@RequestBody(required = false) Greeting body) {
    if (body != null && body.greeting() != null && !body.greeting().isBlank()) {
      greeting.set(body.greeting());
    }
    return new Greeting(greeting.get());
  }
}
