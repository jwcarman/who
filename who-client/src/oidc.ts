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
import type { SignedIn } from "./email.js";
import { json, nothing, signInRequired, type WhoHttp } from "./http.js";
import type { SignInRequired, WhoResult } from "./result.js";

/**
 * The provider account who holds in the session after a provider sign-in it could not finish
 * alone (`?pending=sign-up` or `?pending=link` on the page who sent the browser back to).
 */
export interface PendingSignUp {
  /** The provider's registration id. */
  provider: string;
  displayName: string;
  /** True when no one is signed in: the account can become a new identity. */
  canSignUp: boolean;
  /** True when someone is signed in: the account can be linked to them. */
  canLink: boolean;
}

/** Signing in with a provider (Google, GitHub...), and linking provider accounts. */
export interface Oidc<D> {
  /**
   * Where a provider's sign-in button points: a plain link, a full page load, no CSRF. who
   * sends the browser back to the application when done.
   */
  authorizeUrl(registrationId: string): string;
  /** The pending provider account, for a page asking the person to confirm. */
  pending(): Promise<WhoResult<PendingSignUp, "none-pending", D>>;
  /** Makes the pending account a new identity, if the application's sign-up policy allows. */
  signUp(): Promise<WhoResult<SignedIn, "refused" | "none-pending", D>>;
  /**
   * Links the pending account to the signed-in identity. Call it only after the person confirms:
   * the confirmation is what stops someone planting their own account on yours.
   */
  link(): Promise<WhoResult<void, SignInRequired | "conflict" | "none-pending", D>>;
  /**
   * Starts linking a provider account to the signed-in identity: sends the page to the provider,
   * so on success the promise never settles. Needs a recent sign-in.
   */
  startLink(registrationId: string): Promise<WhoResult<never, SignInRequired | "not-found", D>>;
}

export function oidc<D>(http: WhoHttp<D>): Oidc<D> {
  const noneOn404 = (status: number) => (status === 404 ? "none-pending" : undefined);
  return {
    authorizeUrl: (registrationId) => http.href(`/oidc/authorize/${encodeURIComponent(registrationId)}`),

    pending: () => http.call({ method: "GET", path: "/oidc/pending", read: json<PendingSignUp>(), reason: noneOn404 }),

    signUp: () =>
      http.call({
        method: "POST",
        path: "/oidc/sign-up",
        read: json<SignedIn>(),
        reason: (status): "refused" | "none-pending" | undefined => (status === 403 ? "refused" : noneOn404(status)),
      }),

    link: () =>
      http.call({
        method: "POST",
        path: "/oidc/link",
        read: nothing,
        reason: (status, code): SignInRequired | "conflict" | "none-pending" | undefined =>
          signInRequired(status, code) ?? (status === 409 ? "conflict" : noneOn404(status)),
      }),

    startLink: async (registrationId) => {
      // asked for JSON, who answers where the provider is instead of redirecting: fetch cannot
      // follow a redirect to another site, so the page goes there itself
      const started = await http.call({
        method: "POST",
        path: `/oidc/link/${encodeURIComponent(registrationId)}`,
        accept: "application/json",
        read: json<{ redirectUrl: string }>(),
        reason: (status, code): SignInRequired | "not-found" | undefined =>
          signInRequired(status, code) ?? (status === 404 ? "not-found" : undefined),
      });
      if (!started.ok) {
        return started;
      }
      http.navigate(started.value.redirectUrl);
      return new Promise<never>(() => {
        // the page is leaving
      });
    },
  };
}
