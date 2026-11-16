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
import { json, nothing, signInRequired, type WhoHttp } from "./http";
import type { SignInRequired, WhoResult } from "./result";

/** Who signed in. */
export interface SignedIn {
  identityId: string;
}

/** The reasons confirming a new address can fail. */
export type ConfirmAddressFailure = SignInRequired | "rejected" | "conflict";

/** Signing in by email link or code, and adding or changing an email address. */
export interface Email<D> {
  /**
   * Emails a sign-in link and code. who answers the same whether or not the address is known, so
   * the answer tells an attacker nothing.
   */
  send(email: string): Promise<void>;
  /** Signs in with a link's token, or with the address and the emailed code. */
  signIn(credentials: { token: string } | { email: string; code: string }): Promise<WhoResult<SignedIn, "rejected", D>>;
  /**
   * Signs in with the link's token in this page's URL (`#token=...`), first taking it out of the
   * address bar and history; answers null when the URL has none.
   */
  signInFromLocation(): Promise<WhoResult<SignedIn, "rejected", D> | null>;
  /**
   * Emails a confirmation to a new address, or one replacing `replaces` (the id of one of your
   * addresses). Needs a recent sign-in.
   */
  addAddress(address: string, replaces?: string): Promise<WhoResult<void, SignInRequired | "not-found", D>>;
  /** Confirms a new address with the emailed link's token or code. */
  confirmAddress(proof: { token: string } | { code: string }): Promise<WhoResult<void, ConfirmAddressFailure, D>>;
  /**
   * Confirms with the token in this page's URL (`#confirm-address=...`), first taking it out of the
   * address bar and history; answers null when the URL has none.
   */
  confirmAddressFromLocation(): Promise<WhoResult<void, ConfirmAddressFailure, D> | null>;
}

/**
 * The token under `key` in this page's fragment, after taking the fragment out of the address bar
 * and history, so the token is never left behind whatever happens next.
 */
function takeFromFragment(key: string): string | null {
  const token = new URLSearchParams(location.hash.slice(1)).get(key);
  if (token !== null) {
    history.replaceState(null, "", location.pathname + location.search);
  }
  return token;
}

export function email<D>(http: WhoHttp<D>): Email<D> {
  const signIn = (credentials: { token: string } | { email: string; code: string }) =>
    http.call({
      method: "POST",
      path: "/email/sign-in",
      body: credentials,
      read: json<SignedIn>(),
      reason: (status) => (status === 401 ? "rejected" : undefined),
    });
  const confirmAddress = (proof: { token: string } | { code: string }) =>
    http.call({
      method: "POST",
      path: "/email/addresses/confirm",
      body: proof,
      read: nothing,
      reason: (status, code): ConfirmAddressFailure | undefined =>
        signInRequired(status, code) ?? (status === 400 ? "rejected" : status === 409 ? "conflict" : undefined),
    });
  return {
    send: async (address) => {
      await http.call({
        method: "POST",
        path: "/email/send",
        body: { email: address },
        read: nothing,
        reason: () => undefined,
      });
    },
    signIn,
    signInFromLocation: async () => {
      const token = takeFromFragment("token");
      return token === null ? null : signIn({ token });
    },
    addAddress: (address, replaces) =>
      http.call({
        method: "POST",
        path: "/email/addresses",
        body: replaces === undefined ? { address } : { address, replaces },
        read: nothing,
        reason: (status, code) => signInRequired(status, code) ?? (status === 404 ? "not-found" : undefined),
      }),
    confirmAddress,
    confirmAddressFromLocation: async () => {
      const token = takeFromFragment("confirm-address");
      return token === null ? null : confirmAddress({ token });
    },
  };
}
