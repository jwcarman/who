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
package org.jwcarman.who.examples.webandapi;

import java.util.UUID;

import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.spring.security.RequiresPermission;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The task board. Each action names the permission it needs; who resolves the caller's permissions
 * from their roles on every request.
 */
@Controller
class BoardController {

  private final TaskStore tasks;

  BoardController(TaskStore tasks) {
    this.tasks = tasks;
  }

  @GetMapping("/")
  @RequiresPermission(Permissions.TASK_READ)
  String board(Model model) {
    model.addAttribute("tasks", tasks.findAll());
    return "board";
  }

  @PostMapping("/tasks")
  @RequiresPermission(Permissions.TASK_CREATE)
  String add(@RequestParam String title, @AuthenticationPrincipal WhoPrincipal principal) {
    if (StringUtils.hasText(title)) {
      tasks.add(title.strip(), principal.identity().id());
    }
    return "redirect:/";
  }

  @PostMapping("/tasks/{id}/delete")
  @RequiresPermission(Permissions.TASK_DELETE)
  String delete(@PathVariable UUID id) {
    tasks.delete(id);
    return "redirect:/";
  }
}
