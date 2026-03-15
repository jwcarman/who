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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jwcarman.who.core.Identifiers;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TaskRepository {

  private static final String COL_TITLE = "title";
  private static final String COL_STATUS = "status";

  private final JdbcClient jdbcClient;

  public TaskRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public List<Task> findAll() {
    return jdbcClient
        .sql("SELECT id, title, status FROM task")
        .query(
            (rs, rowNum) ->
                new Task(
                    rs.getObject("id", UUID.class),
                    rs.getString(COL_TITLE),
                    TaskStatus.valueOf(rs.getString(COL_STATUS))))
        .list();
  }

  public Optional<Task> findById(UUID id) {
    return jdbcClient
        .sql("SELECT id, title, status FROM task WHERE id = :id")
        .param("id", id)
        .query(
            (rs, rowNum) ->
                new Task(
                    rs.getObject("id", UUID.class),
                    rs.getString(COL_TITLE),
                    TaskStatus.valueOf(rs.getString(COL_STATUS))))
        .optional();
  }

  public Task save(Task task) {
    UUID id = task.id() != null ? task.id() : Identifiers.uuid();
    Task toSave =
        new Task(id, task.title(), task.status() != null ? task.status() : TaskStatus.OPEN);
    jdbcClient
        .sql(
            """
                MERGE INTO task (id, title, status) KEY(id)
                VALUES (:id, :title, :status)
                """)
        .param("id", toSave.id())
        .param(COL_TITLE, toSave.title())
        .param(COL_STATUS, toSave.status().name())
        .update();
    return toSave;
  }

  public void deleteById(UUID id) {
    jdbcClient.sql("DELETE FROM task WHERE id = :id").param("id", id).update();
  }
}
