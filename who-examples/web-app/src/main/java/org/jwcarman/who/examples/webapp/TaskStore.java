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
package org.jwcarman.who.examples.webapp;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The board's tasks, in the app's own table. */
@Repository
class TaskStore {

  private final JdbcClient jdbc;

  TaskStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  List<Task> findAll() {
    return jdbc.sql("SELECT id, title, created_by, created_at FROM task ORDER BY created_at")
        .query(
            (rs, row) ->
                new Task(
                    rs.getObject("id", UUID.class),
                    rs.getString("title"),
                    rs.getObject("created_by", UUID.class),
                    rs.getTimestamp("created_at").toInstant()))
        .list();
  }

  void add(String title, UUID createdBy) {
    jdbc.sql("INSERT INTO task (id, title, created_by, created_at) VALUES (:id, :title, :by, :at)")
        .param("id", UUID.randomUUID())
        .param("title", title)
        .param("by", createdBy)
        .param("at", Timestamp.from(Instant.now()))
        .update();
  }

  void delete(UUID id) {
    jdbc.sql("DELETE FROM task WHERE id = :id").param("id", id).update();
  }
}
