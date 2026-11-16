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
import { useCallback, useEffect, useState } from 'react';
import type { Session } from '@jwcarman/who-client';
import { signInAgain, who } from '../who-client';

// how they signed in, as /who/sessions names it
const METHODS: Record<string, string> = {
  'email-link': 'an email link',
  'email-code': 'an email code',
  passkey: 'a passkey',
  oidc: 'a provider'
};

const when = (iso: string) =>
  new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });

const TOO_LONG =
  'You signed in too long ago to sign out other browsers. Sign out, sign in again, then try again.';

// "Where you're signed in": every browser this person is signed in on, and a way out of each.
export function Sessions() {
  const [sessions, setSessions] = useState<Session[]>([]);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    const result = await who.sessions.list();
    setSessions(result.ok ? result.value : []);
  }, []);

  useEffect(() => { void load(); }, [load]);

  async function end(session: Session) {
    const result = await who.sessions.end(session.id);
    // this browser's own session ends at once, with no recent sign-in needed
    if (result.ok && session.current) window.location.assign('/signin');
    else if (!result.ok && result.reason !== 'not-found') setError(signInAgain(result.reason, TOO_LONG));
    else await load();
  }

  async function endOthers() {
    const result = await who.sessions.endOthers();
    if (!result.ok) setError(signInAgain(result.reason, TOO_LONG));
    else await load();
  }

  return (
    <section className="stack">
      <h2>Where you're signed in</h2>
      {error && <div className="message warn">{error}</div>}
      <ul className="list">
        {sessions.map(s => (
          <li key={s.id}>
            <span className="grow">
              <strong>{s.device}</strong>{s.current && ' (this browser)'}<br />
              <span className="muted" title={s.userAgent ?? ''}>
                Signed in {when(s.signedInAt)} with {METHODS[s.signInMethod] ?? s.signInMethod}
                {' · '}last active {when(s.lastSeenAt)}
              </span>
            </span>
            <button type="button" className="secondary" onClick={() => end(s)}>Sign out</button>
          </li>
        ))}
      </ul>
      {sessions.length > 1 && (
        <div className="inline">
          <button type="button" className="secondary" onClick={endOthers}>
            Sign out everywhere else
          </button>
        </div>
      )}
      <p className="muted">Signing out another browser needs a sign-in within the last 10 minutes.</p>
    </section>
  );
}
