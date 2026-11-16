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
import { api, getJson, type Member } from '../api';

export function Members() {
  const [members, setMembers] = useState<Member[]>([]);
  const [email, setEmail] = useState('');
  const [message, setMessage] = useState<string | null>(null);

  const load = useCallback(async () => setMembers(await getJson<Member[]>('/api/members')), []);
  useEffect(() => { void load(); }, [load]);

  async function invite(e: FormEvent) {
    e.preventDefault();
    const res = await api('POST', '/api/members', { email });
    if (res.ok) {
      setMessage(`Invited ${email}. They sign in with an email link.`);
      setEmail('');
      await load();
    } else {
      setMessage((await res.json()).detail ?? 'The invite failed.');
    }
  }

  return (
    <>
      <h1>Members</h1>
      {message && <div className="message">{message}</div>}
      <section>
        <form className="inline" onSubmit={invite}>
          <input type="email" value={email} onChange={e => setEmail(e.target.value)}
                 placeholder="name@example.com" required />
          <button type="submit">Invite</button>
        </form>
      </section>
      <section>
        <ul className="list">
          {members.map(m => (
            <li key={m.identityId}>
              <span className="grow">{m.email ?? <span className="muted">signed up with a provider</span>}</span>
              <span className="muted">{m.roles.join(', ').toLowerCase()}</span>
            </li>
          ))}
        </ul>
      </section>
    </>
  );
}
