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

import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.example.controller.dto.CreateTaskRequest;
import org.jwcarman.who.example.controller.dto.TaskResponse;
import org.jwcarman.who.example.controller.dto.UpdateTaskRequest;
import org.jwcarman.who.example.domain.Task;
import org.jwcarman.who.example.repository.TaskRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskRepository taskRepository;

    public TaskController(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('task.own.read')")
    public List<TaskResponse> listMyTasks(@AuthenticationPrincipal WhoPrincipal principal) {
        return taskRepository.findByUserId(principal.userId())
            .stream()
            .map(TaskResponse::from)
            .toList();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('task.own.write')")
    public TaskResponse createTask(
        @AuthenticationPrincipal WhoPrincipal principal,
        @RequestBody CreateTaskRequest request
    ) {
        Task task = new Task(
            principal.userId(),
            request.title(),
            request.description(),
            request.status()
        );
        Task savedTask = taskRepository.save(task);
        return TaskResponse.from(savedTask);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('task.own.read')")
    public TaskResponse getMyTask(
        @AuthenticationPrincipal WhoPrincipal principal,
        @PathVariable UUID id
    ) {
        Task task = taskRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));

        if (!task.getUserId().equals(principal.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only access your own tasks");
        }

        return TaskResponse.from(task);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('task.own.write')")
    public TaskResponse updateMyTask(
        @AuthenticationPrincipal WhoPrincipal principal,
        @PathVariable UUID id,
        @RequestBody UpdateTaskRequest request
    ) {
        Task task = taskRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));

        if (!task.getUserId().equals(principal.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only update your own tasks");
        }

        task.setTitle(request.title());
        task.setDescription(request.description());
        task.setStatus(request.status());

        Task savedTask = taskRepository.save(task);
        return TaskResponse.from(savedTask);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('task.own.write')")
    public void deleteMyTask(
        @AuthenticationPrincipal WhoPrincipal principal,
        @PathVariable UUID id
    ) {
        Task task = taskRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));

        if (!task.getUserId().equals(principal.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only delete your own tasks");
        }

        taskRepository.delete(task);
    }
}
