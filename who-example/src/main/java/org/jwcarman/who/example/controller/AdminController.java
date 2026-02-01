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

import org.jwcarman.who.example.controller.dto.TaskResponse;
import org.jwcarman.who.example.domain.Task;
import org.jwcarman.who.example.repository.TaskRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final TaskRepository taskRepository;

    public AdminController(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @GetMapping("/tasks")
    @PreAuthorize("hasAuthority('task.all.read')")
    public List<TaskResponse> listAllTasks() {
        return taskRepository.findAll()
            .stream()
            .map(TaskResponse::from)
            .toList();
    }

    @GetMapping("/tasks/{id}")
    @PreAuthorize("hasAuthority('task.all.read')")
    public TaskResponse getAnyTask(@PathVariable UUID id) {
        Task task = taskRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
        return TaskResponse.from(task);
    }
}
