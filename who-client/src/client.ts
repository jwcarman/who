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
import { type Admin, admin } from "./admin.js";
import { type Email, email } from "./email.js";
import { json, nothing, type WhoClientOptions, WhoHttp } from "./http.js";
import { me, type MyAccount } from "./me.js";
import { type Oidc, oidc } from "./oidc.js";
import { type Passkeys, passkeys } from "./passkeys.js";
import type { WhoResult } from "./result.js";

/** A provider to draw a sign-in button for. */
export interface SignInProvider {
  /** The registration id, which `oidc.authorizeUrl` takes. */
  id: string;
  name: string;
}

/** The ways of signing in that are on, for a signed-out page to draw. */
export interface SignInOptions {
  email: boolean;
  passkey: boolean;
  providers: SignInProvider[];
}

/** who's browser endpoints, each a typed call. */
export interface WhoClient<D> {
  /** The ways of signing in that are on; public. */
  signInOptions(): Promise<WhoResult<SignInOptions, never, D>>;
  /** Signs out, and fetches a fresh CSRF cookie for whoever signs in next. */
  signOut(): Promise<void>;
  /** Your own account: who you are, your sessions, passkeys, addresses, linked accounts, API keys. */
  me: MyAccount<D>;
  /** Signing in by email link or code. */
  email: Email<D>;
  /** Signing in with a passkey. */
  passkeys: Passkeys<D>;
  /** Signing in with a provider, and finishing a pending sign-up or link. */
  oidc: Oidc<D>;
  /** Managing identities, roles and invitations, with who's admin permissions. */
  admin: Admin<D>;
}

/**
 * Creates a client for who at `baseUrl` (default `/who`, who's default `who.base-path`).
 *
 * @throws Error if `baseUrl` has a query or fragment
 */
export function createWhoClient<D = unknown>(options: WhoClientOptions<D> = {}): WhoClient<D> {
  const http = new WhoHttp<D>(options);
  return {
    signInOptions: () =>
      http.call<SignInOptions, never>({
        method: "GET",
        path: "/sign-in",
        read: json<SignInOptions>(),
        reason: () => undefined,
      }),
    signOut: async () => {
      await http.call({ method: "POST", path: "/logout", read: nothing, reason: () => undefined });
      await http.refreshCsrf();
    },
    me: me(http),
    email: email(http),
    passkeys: passkeys(http),
    oidc: oidc(http),
    admin: admin(http),
  };
}
