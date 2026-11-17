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
import { json, nothing, removeCredential, signedOut, signInRequired, type WhoHttp } from "./http.js";
import type { LastCredential, SignInRequired, WhoResult } from "./result.js";

/** Who signed in. */
export interface SignedIn {
  identityId: string;
}

/** The human check's refusal: show its widget again and retry with a fresh token. */
export type HumanCheckFailed = "human-check-failed";

/** Options for a call the application's human check guards. */
export interface HumanCheckOptions {
  /** The token from the human check's widget, such as Cloudflare Turnstile's; each works once. */
  humanCheck?: string;
}

/** The reasons signing in by email can fail. */
export type EmailSignInFailure = "rejected" | HumanCheckFailed;

/** The reasons confirming a new address can fail. */
export type ConfirmAddressFailure = SignInRequired | "rejected" | "conflict";

/** Signing in by email link or code. Your own addresses are under `client.me.emailAddresses`. */
export interface Email<D> {
  /**
   * Emails a sign-in link and code. who answers the same whether or not the address is known, so
   * the answer tells an attacker nothing. Fails only when the application's human check refuses.
   */
  send(email: string, options?: HumanCheckOptions): Promise<WhoResult<void, HumanCheckFailed, D>>;
  /** Signs in with a link's token, or with the address and the emailed code. */
  signIn(
    credentials: { token: string } | { email: string; code: string },
    options?: HumanCheckOptions,
  ): Promise<WhoResult<SignedIn, EmailSignInFailure, D>>;
  /**
   * Signs in with the link's token in this page's URL (`#token=...`), first taking it out of the
   * address bar and history; answers null when the URL has none.
   */
  signInFromLocation(options?: HumanCheckOptions): Promise<WhoResult<SignedIn, EmailSignInFailure, D> | null>;
}

/** One of your email addresses. */
export interface EmailAddress {
  /** who's id for it, which `remove` and `add`'s `replaces` take. */
  id: string;
  address: string;
  createdAt: string;
}

/** The address a confirmation added. */
export interface ConfirmedAddress {
  credentialId: string;
}

/** Your email addresses: listing, adding, confirming and removing them (`client.me.emailAddresses`). */
export interface EmailAddresses<D> {
  /** Your addresses, oldest first. */
  list(): Promise<WhoResult<EmailAddress[], "signed-out", D>>;
  /**
   * Emails a confirmation to a new address, or one replacing `replaces` (the id of one of your
   * addresses). Needs a recent sign-in.
   */
  add(address: string, replaces?: string): Promise<WhoResult<void, SignInRequired | "not-found", D>>;
  /** Confirms a new address with the emailed link's token or code. */
  confirm(proof: { token: string } | { code: string }): Promise<WhoResult<ConfirmedAddress, ConfirmAddressFailure, D>>;
  /**
   * Confirms with the token in this page's URL (`#confirm-address=...`), first taking it out of the
   * address bar and history; answers null when the URL has none.
   */
  confirmFromLocation(): Promise<WhoResult<ConfirmedAddress, ConfirmAddressFailure, D> | null>;
  /**
   * Removes one of your addresses by its id. Needs a recent sign-in; another identity's address is
   * `"not-found"`, and your last sign-in credential is `"last-credential"`.
   */
  remove(id: string): Promise<WhoResult<void, SignInRequired | "not-found" | LastCredential, D>>;
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

/** The header carrying a human check's token, only when there is one. */
function humanCheckHeader(options?: HumanCheckOptions): Record<string, string> | undefined {
  return options?.humanCheck === undefined ? undefined : { "Who-Human-Check": options.humanCheck };
}

function humanCheckFailed(status: number, code: string | null): HumanCheckFailed | undefined {
  return status === 403 && code === "human-check-failed" ? code : undefined;
}

export function email<D>(http: WhoHttp<D>): Email<D> {
  const signIn = (credentials: { token: string } | { email: string; code: string }, options?: HumanCheckOptions) =>
    http.call({
      method: "POST",
      path: "/email/sign-in",
      body: credentials,
      headers: humanCheckHeader(options),
      read: json<SignedIn>(),
      reason: (status, code): EmailSignInFailure | undefined =>
        status === 401 ? "rejected" : humanCheckFailed(status, code),
    });
  return {
    send: (address, options) =>
      http.call({
        method: "POST",
        path: "/email/send",
        body: { email: address },
        headers: humanCheckHeader(options),
        read: nothing,
        reason: humanCheckFailed,
      }),
    signIn,
    signInFromLocation: async (options) => {
      const token = takeFromFragment("token");
      return token === null ? null : signIn({ token }, options);
    },
  };
}

export function emailAddresses<D>(http: WhoHttp<D>): EmailAddresses<D> {
  const confirm = (proof: { token: string } | { code: string }) =>
    http.call({
      method: "POST",
      path: "/me/email-addresses/confirmations",
      body: proof,
      read: json<ConfirmedAddress>(),
      reason: (status, code): ConfirmAddressFailure | undefined =>
        signInRequired(status, code) ?? (status === 400 ? "rejected" : status === 409 ? "conflict" : undefined),
    });
  return {
    list: () =>
      http.call({ method: "GET", path: "/me/email-addresses", read: json<EmailAddress[]>(), reason: signedOut }),
    add: (address, replaces) =>
      http.call({
        method: "POST",
        path: "/me/email-addresses",
        body: replaces === undefined ? { address } : { address, replaces },
        read: nothing,
        reason: (status, code) => signInRequired(status, code) ?? (status === 404 ? "not-found" : undefined),
      }),
    confirm,
    confirmFromLocation: async () => {
      const token = takeFromFragment("confirm-address");
      return token === null ? null : confirm({ token });
    },
    remove: (id) =>
      http.call({
        method: "DELETE",
        path: `/me/email-addresses/${encodeURIComponent(id)}`,
        read: nothing,
        reason: removeCredential,
      }),
  };
}
