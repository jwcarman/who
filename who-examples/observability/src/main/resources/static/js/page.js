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
// The buttons on the page. Each makes one request and shows its answer and its trace id, which
// the app sends in the X-Trace-Id header. Sign-in calls come from who.js, as the guides show them.

import { ensureCsrfCookie, post, send } from './who.js';

const DEMO = 'demo@example.com';
const GRAFANA = 'http://localhost:3000';

let key = null; // { id, key } from POST /keys, kept only in this page

// --- Links into Grafana ------------------------------------------------------------------------

function explore(datasource, query) {
  const panes = {
    a: {
      datasource,
      queries: [{ refId: 'A', datasource: { uid: datasource }, query }],
      range: { from: 'now-1h', to: 'now' }
    }
  };
  return `${GRAFANA}/explore?schemaVersion=1&panes=${encodeURIComponent(JSON.stringify(panes))}`;
}

const traceLink = id => explore('tempo', id);
const logsLink = id => explore('loki', `{service_name="who-example-observability"} | trace_id="${id}"`);

// --- Showing an answer ---------------------------------------------------------------------------

function show(request, res, says, signedIn = false) {
  document.getElementById('result-none').hidden = true;
  document.getElementById('result-body').hidden = false;
  document.getElementById('result-request').textContent = request;
  const status = document.getElementById('result-status');
  status.textContent = `${res.status}`;
  status.className = 'status ' + (res.ok ? 'ok' : 'refused');
  document.getElementById('result-says').textContent =
    res.status === 401 && !signedIn && !request.includes('X-API-Key') && !request.includes('login')
      ? 'You are not signed in. Email yourself a code and sign in first.'
      : says(res.status);
  const id = res.headers.get('X-Trace-Id');
  document.getElementById('result-trace').hidden = !id;
  if (id) {
    document.getElementById('result-trace-id').textContent = id;
    document.getElementById('result-trace-link').href = traceLink(id);
    document.getElementById('result-logs-link').href = logsLink(id);
  }
}

async function refreshSignedIn() {
  const res = await fetch('/who/me', { credentials: 'same-origin' });
  document.getElementById('signed-in').textContent = res.ok ? 'signed in' : 'signed out';
}

function showKey() {
  document.getElementById('key-state').textContent =
    key ? `Key ${key.key.slice(0, 12)}… (shown once; this page keeps it until you reload).` : 'No key yet.';
}

// Calls with the key send no cookies, so the key alone signs the request in.
function withKey(method, value, body) {
  return fetch('/api/greeting', {
    method,
    credentials: 'omit',
    headers: { 'X-API-Key': value, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
}

// --- The actions -----------------------------------------------------------------------------------

const actions = {
  'request-code': async () => show(`POST /who/email/send (${DEMO})`, await post('/who/email/send', { email: DEMO }),
    () => 'Answered alike whether or not the address is known. who.sign-in.emails counts it sent, or suppressed if you asked within a minute. Open Mailpit for the code.'),

  'unknown-address': async () => show('POST /who/email/send (nobody@example.com)',
    await post('/who/email/send', { email: 'nobody@example.com' }),
    () => 'The same answer as for a known address, so nobody can probe for accounts. who.sign-in.emails counts it unknown-address.'),

  'wrong-code': async () => show('POST /who/email/sign-in (wrong code)', await post('/who/email/sign-in', { email: DEMO, code: '000-000' }),
    s => s === 401 ? 'Refused: who.sign-ins counts an email-code failure, reason wrong-code (or no-live-code if you have no code waiting). Each wrong guess uses up an attempt on your code.' : 'Unexpected.'),

  'sign-out': async () => {
    const res = await post('/who/logout');
    await ensureCsrfCookie(); // signing out clears the CSRF cookie; get a new one
    key = null;
    showKey();
    await refreshSignedIn();
    show('POST /who/logout', res, () => 'Signed out: who.sign-outs counts it.');
  },

  'make-key': async () => {
    const res = await post('/keys');
    if (res.ok) {
      key = await res.clone().json();
      showKey();
    }
    // signed in, yet 401: the sign-in is more than ten minutes old, and a key needs a recent one
    const stale = res.status === 401 && (await fetch('/who/me', { credentials: 'same-origin' })).ok;
    show('POST /keys', res, s => s === 201
      ? 'Made: who.credentials.added counts an api-key.'
      : stale
        ? 'Not made: you signed in more than ten minutes ago, and making a key needs a recent sign-in (@RequiresRecentSignIn). Email yourself a code and sign in again.'
        : 'Not made.', stale);
  },

  read: async () => key
    ? show('GET /api/greeting with X-API-Key', await withKey('GET', key.key),
      s => s === 200 ? 'Signed in with the key. Spring Security\'s spring.security.authentications counts it; who.sign-ins counts only API-key failures, since a key signs in every request.' : 'Refused: the key was revoked, so who no longer knows it (unknown-key).')
    : noKey(),

  write: async () => key
    ? show('POST /api/greeting with X-API-Key', await withKey('POST', key.key, { greeting: 'Hello from the page' }),
      s => s === 403 ? 'The key signed in, but it may only read: 403. The key itself was fine, so nothing is counted as a failed sign-in.' : 'Refused: the key was revoked, so who no longer knows it (unknown-key).')
    : noKey(),

  'unknown-key': async () => show('GET /api/greeting with an unknown X-API-Key', await withKey('GET', 'who_made-up'),
    () => 'Refused: who.sign-ins counts an api-key failure, reason unknown-key.'),

  revoke: async () => {
    if (!key) {
      return noKey();
    }
    const res = await send('DELETE', `/keys/${key.id}`);
    show(`DELETE /keys/${key.id}`, res,
      s => s === 204 ? 'Revoked: who.credentials.revoked counts it. Read with the key now and it is refused.' : 'Not revoked.');
  }
};

function noKey() {
  document.getElementById('key-state').textContent = 'Make a key first.';
}

document.querySelectorAll('[data-action]').forEach(button =>
  button.addEventListener('click', () => actions[button.dataset.action]()));

document.getElementById('code-form').addEventListener('submit', async event => {
  event.preventDefault();
  const code = document.getElementById('code').value;
  const res = await post('/who/email/sign-in', { email: DEMO, code });
  await refreshSignedIn();
  show('POST /who/email/sign-in', res,
    s => s === 200 ? 'Signed in: who.sign-ins counts an email-code success.' : 'Refused: wrong, used or expired code.');
});

await ensureCsrfCookie();
await refreshSignedIn();
