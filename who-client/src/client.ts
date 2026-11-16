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
import { type Email, email } from "./email";
import { json, nothing, signedOut, type WhoClientOptions, WhoHttp } from "./http";
import type { WhoResult } from "./result";
import { type Sessions, sessions } from "./sessions";

/** The signed-in identity. */
export interface Me {
  identityId: string;
  permissions: string[];
}

/** who's browser endpoints, each a typed call. */
export interface WhoClient<D> {
  /** The signed-in identity and its permissions. */
  me(): Promise<WhoResult<Me, "signed-out", D>>;
  /** Signs out, and fetches a fresh CSRF cookie for whoever signs in next. */
  signOut(): Promise<void>;
  /** Where this person is signed in. */
  sessions: Sessions<D>;
  /** Signing in by email link or code, and email addresses. */
  email: Email<D>;
}

/**
 * Creates a client for who at `baseUrl` (default `/who`, who's default `who.base-path`).
 *
 * @throws Error if `baseUrl` has a query or fragment
 */
export function createWhoClient<D = unknown>(options: WhoClientOptions<D> = {}): WhoClient<D> {
  const http = new WhoHttp<D>(options);
  return {
    me: () => http.call({ method: "GET", path: "/me", read: json<Me>(), reason: signedOut }),
    signOut: async () => {
      await http.call({ method: "POST", path: "/logout", read: nothing, reason: () => undefined });
      await http.refreshCsrf();
    },
    sessions: sessions(http),
    email: email(http),
  };
}
