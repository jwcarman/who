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
import type { CredentialCounts } from "./admin.js";
import { type ApiKeys, apiKeys } from "./apiKeys.js";
import { type EmailAddresses, emailAddresses } from "./email.js";
import { json, signedOut, type WhoHttp } from "./http.js";
import { type Accounts, accounts } from "./oidc.js";
import { type MyPasskeys, myPasskeys } from "./passkeys.js";
import type { WhoResult } from "./result.js";
import { type Sessions, sessions } from "./sessions.js";

/** The signed-in identity. */
export interface Me {
  identityId: string;
  permissions: string[];
  /**
   * How this request signed in, as a lowercase hyphenated tag (`email-link`, `passkey`, `oidc`,
   * `api-key`...); for a browser, how its session signed in. Null when who does not know.
   */
  signInMethod: string | null;
  /**
   * When this browser session's sign-in stops being recent, which may have passed already: calls
   * needing a recent sign-in fail after it. Null for anything but a browser session.
   */
  recentSignInUntil: string | null;
  /** How many sign-in credentials you hold, by kind; empty when who cannot count them. */
  credentials: CredentialCounts;
}

/** Your own account, everything under who's `/me` (`client.me`). */
export interface MyAccount<D> {
  /** Who is signed in, and their permissions. */
  get(): Promise<WhoResult<Me, "signed-out", D>>;
  /** Where you are signed in. */
  sessions: Sessions<D>;
  /** Your passkeys. */
  passkeys: MyPasskeys<D>;
  /** Your email addresses. */
  emailAddresses: EmailAddresses<D>;
  /** Your linked provider accounts. */
  accounts: Accounts<D>;
  /** Your API keys. */
  apiKeys: ApiKeys<D>;
}

export function me<D>(http: WhoHttp<D>): MyAccount<D> {
  return {
    get: () => http.call({ method: "GET", path: "/me", read: json<Me>(), reason: signedOut }),
    sessions: sessions(http),
    passkeys: myPasskeys(http),
    emailAddresses: emailAddresses(http),
    accounts: accounts(http),
    apiKeys: apiKeys(http),
  };
}
