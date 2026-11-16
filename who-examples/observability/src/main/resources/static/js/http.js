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
// The page's raw requests: its buttons show each answer's status and X-Trace-Id header, so they
// call fetch themselves instead of going through who-client (sessions.js uses who-client). Only
// the CSRF header is needed for that.

// --- CSRF (Email Links guide, "The sign-in flow") ---------------------------------------------
// who protects the chain with Spring's csrf.spa(): read the XSRF-TOKEN cookie and send it back in
// the X-XSRF-TOKEN header on every POST. Read it at each call, never cache it: it changes when
// someone signs in or out.

function xsrf() {
  return document.cookie.split('; ')
    .find(c => c.startsWith('XSRF-TOKEN='))?.split('=')[1];
}

export async function post(path, body) {
  return send('POST', path, body);
}

export async function send(method, path, body) {
  return fetch(path, {
    method,
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      'X-XSRF-TOKEN': decodeURIComponent(xsrf() ?? '')
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
}

// The cookie exists only after a response from the server, so make a GET before the first POST.
// GET /who/me answers 401 when signed out, but sets the cookie either way.
export async function ensureCsrfCookie() {
  if (!xsrf()) {
    await fetch('/who/me', { credentials: 'same-origin' });
  }
}
