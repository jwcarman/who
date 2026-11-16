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

import java.util.List;
import java.util.UUID;

import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.spring.security.RequiresPermission;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The task board, guarded by permission on every call. */
@RestController
class TasksApiController {

  /**
   * A new task.
   *
   * @param title what needs doing
   */
  record NewTask(String title) {}

  private static final int MAX_TITLE = 200;

  private final TaskStore tasks;

  TasksApiController(TaskStore tasks) {
    this.tasks = tasks;
  }

  @GetMapping("/api/tasks")
  @RequiresPermission(Permissions.TASK_READ)
  List<Task> list() {
    return tasks.findAll();
  }

  @PostMapping("/api/tasks")
  @RequiresPermission(Permissions.TASK_CREATE)
  ResponseEntity<ProblemDetail> add(
      @RequestBody NewTask task, @AuthenticationPrincipal WhoPrincipal principal) {
    if (task == null || !StringUtils.hasText(task.title())) {
      return ResponseEntity.of(
              ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "A task needs a title"))
          .build();
    }
    // the column's width: a longer title is the caller's mistake, not a database error
    if (task.title().strip().length() > MAX_TITLE) {
      return ResponseEntity.of(
              ProblemDetail.forStatusAndDetail(
                  HttpStatus.BAD_REQUEST,
                  "A task's title must be at most " + MAX_TITLE + " characters"))
          .build();
    }
    tasks.add(task.title().strip(), principal.identity().id());
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/api/tasks/{id}")
  @RequiresPermission(Permissions.TASK_DELETE)
  ResponseEntity<Void> delete(@PathVariable UUID id) {
    tasks.delete(id);
    return ResponseEntity.noContent().build();
  }
}
