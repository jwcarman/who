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

/**
 * What a call answers: its value, or why it failed. `reason` is one of the call's own reasons, so
 * the compiler knows every way it can fail; `detail` is what the client's `decodeError` made of the
 * error body.
 */
export type WhoResult<T, R extends string, D> =
  { ok: true; value: T } | { ok: false; reason: R; status: number; detail: D };

/**
 * The browser's passkey prompt ended without a passkey, so nothing reached who: there is no status
 * (0) and no detail.
 */
export interface PromptFailure<R extends string> {
  ok: false;
  reason: R;
  status: 0;
  detail: undefined;
}

/** The person dismissed the browser's passkey prompt. */
export type Cancelled = PromptFailure<"cancelled">;

/** This device already holds a passkey for the account (WebAuthn's `excludeCredentials`). */
export type AlreadyRegistered = PromptFailure<"already-registered">;

/**
 * The reasons a call that needs a recent sign-in can fail with a 401. who's `Who-Error` header tells
 * `"signed-out"` from `"reauthentication-required"`; `"sign-in-required"` is either, when an
 * application's error handling dropped the header (`me()` then tells which).
 */
export type SignInRequired = "signed-out" | "reauthentication-required" | "sign-in-required";

/**
 * The call needs a browser session on its own (`403`, `Who-Error: browser-session-required`): who
 * refused a bearer token or an API key, or a session cookie sent with one beside it. Send the
 * cookie alone.
 */
export type BrowserSessionRequired = "browser-session-required";

/** A query, path or body who refuses (`400`); the problem's `detail` says what was wrong. */
export type InvalidRequest = "invalid-request";

/**
 * Removing the credential would leave the identity no way to sign in: who keeps an identity's last
 * email address, passkey or linked account (`409`, `Who-Error: last-credential`).
 */
export type LastCredential = "last-credential";

/**
 * A failure thrown rather than answered: a failed result passed to {@link unwrap}, or (with reason
 * `"unexpected"`) an answer the call does not expect, such as a 500 or a malformed success body.
 */
export class WhoError<D = unknown> extends Error {
  /** The failed result's reason, or `"unexpected"`. */
  readonly reason: string;
  /** The response's status, when there was a response. */
  readonly status: number | undefined;
  /** What `decodeError` made of the body, for a failed result; otherwise undefined. */
  readonly detail: D | undefined;

  constructor(message: string, reason: string, status: number | undefined, detail?: D, options?: ErrorOptions) {
    super(message, options);
    this.name = "WhoError";
    this.reason = reason;
    this.status = status;
    this.detail = detail;
  }
}

/**
 * Gives a result's value, or throws a {@link WhoError} carrying why it failed: for callers who
 * would rather catch than check `ok`.
 */
export function unwrap<T, R extends string, D>(result: WhoResult<T, R, D> | PromptFailure<string>): T {
  if (result.ok) {
    return result.value;
  }
  throw new WhoError<D | undefined>(
    `who answered ${result.status}: ${result.reason}`,
    result.reason,
    result.status,
    result.detail,
  );
}
