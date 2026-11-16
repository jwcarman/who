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
// The app's own JSON API, under /api. who's chain answers 401 to a signed-out call there, so a 401
// means "sign in": the page goes to /signin. Calls to who itself go through who-client.ts.

// who protects the chain with Spring's csrf.spa(), the app's own API included: read the XSRF-TOKEN
// cookie and send it back in the X-XSRF-TOKEN header. Read it at each call, never cache it: it
// changes when someone signs in or out. (who-client does the same for who's own calls.)
function xsrf(): string | undefined {
  return document.cookie.split('; ')
    .find(c => c.startsWith('XSRF-TOKEN='))?.split('=')[1];
}

function send(method: string, path: string, body?: unknown): Promise<Response> {
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

export type EmailAddress = { id: string; address: string };
export type Passkey = { id: string; credentialId: string; label: string; created: string };
export type Account = { id: string; issuer: string; subject: string };
export type Provider = { id: string; name: string };
export type Me = {
  identityId: string;
  permissions: string[];
  emails: EmailAddress[];
  passkeys: Passkey[];
  accounts: Account[];
};
export type Task = { id: string; title: string; createdBy: string; createdAt: string };
export type Member = { identityId: string; email: string | null; roles: string[] };

export class SignInRequired extends Error {}

export async function api(method: string, path: string, body?: unknown): Promise<Response> {
  const res = await send(method, path, body);
  if (res.status === 401) {
    throw new SignInRequired();
  }
  return res;
}

export async function getJson<T>(path: string): Promise<T> {
  const res = await api('GET', path);
  if (!res.ok) {
    throw new Error(`${path} answered ${res.status}`);
  }
  return res.json();
}

// Public, so the sign-in page can show a button per provider.
export async function providers(): Promise<Provider[]> {
  const res = await fetch('/api/providers', { credentials: 'same-origin' });
  return res.ok ? res.json() : [];
}
