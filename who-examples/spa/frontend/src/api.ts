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
// means "sign in": the page goes to /signin. Calls to who itself are in who.ts.
import { send } from './who';

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
