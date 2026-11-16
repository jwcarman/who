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
import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, getJson, type Task } from '../api';
import { useMe } from '../App';

export function Board() {
  const { me } = useMe();
  const [tasks, setTasks] = useState<Task[]>([]);
  const [title, setTitle] = useState('');
  const may = (permission: string) => me.permissions.includes(permission);

  const load = useCallback(async () => setTasks(await getJson<Task[]>('/api/tasks')), []);
  useEffect(() => { void load(); }, [load]);

  async function add(e: FormEvent) {
    e.preventDefault();
    await api('POST', '/api/tasks', { title });
    setTitle('');
    await load();
  }

  async function remove(id: string) {
    await api('DELETE', `/api/tasks/${id}`);
    await load();
  }

  return (
    <>
      <h1>Board</h1>
      {may('task.create') && (
        <section>
          <form className="inline" onSubmit={add}>
            <input type="text" value={title} onChange={e => setTitle(e.target.value)}
                   maxLength={200} placeholder="What needs doing?" required />
            <button type="submit">Add</button>
          </form>
        </section>
      )}
      <section>
        {tasks.length === 0 && <p className="muted">Nothing on the board yet.</p>}
        <ul className="list">
          {tasks.map(task => (
            <li key={task.id}>
              <span className="grow">{task.title}</span>
              <span className="muted">{new Date(task.createdAt).toLocaleString()}</span>
              {may('task.delete') && (
                <button type="button" className="secondary" onClick={() => remove(task.id)}>Delete</button>
              )}
            </li>
          ))}
        </ul>
      </section>
    </>
  );
}
