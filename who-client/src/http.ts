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
import { type SignInRequired, WhoError, type WhoResult } from "./result.js";

/** How a client is made. */
export interface WhoClientOptions<D> {
  /**
   * Where who is: `who.base-path` on the page's origin (default `/who`), or an absolute URL. An
   * absolute one works only when the page can read who's `XSRF-TOKEN` cookie (the same site, with
   * the cookie's domain covering both).
   */
  baseUrl?: string;
  /** Turns a failed response's body into the application's own error type, the results' `detail`. */
  decodeError?: (response: Response) => Promise<D>;
  /** Replaces the global `fetch`, for tests or instrumentation. */
  fetch?: typeof fetch;
  /**
   * Sends the page to another site, for the calls that leave it (starting a provider link).
   * Defaults to `location.assign`.
   */
  navigate?: (url: string) => void;
}

/** One request a call makes, and how it reads the answer. */
export interface Call<T, R extends string> {
  method: "GET" | "POST" | "DELETE";
  /** Below the base URL, starting with `/`. */
  path: string;
  body?: unknown;
  accept?: string;
  /** Reads a success's value. */
  read: (response: Response) => Promise<T>;
  /** The call's reason for a failed status and `Who-Error` code, or undefined if unexpected. */
  reason: (status: number, code: string | null) => R | undefined;
}

const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";
const ERROR_HEADER = "Who-Error";

/** Reads a JSON success body. */
export function json<T>(): (response: Response) => Promise<T> {
  return (response) => response.json() as Promise<T>;
}

/** Reads nothing from a success. */
export function nothing(): Promise<void> {
  return Promise.resolve();
}

/** A 401 on a call that needs a recent sign-in, told apart by who's `Who-Error` code. */
export function signInRequired(status: number, code: string | null): SignInRequired | undefined {
  if (status !== 401) {
    return undefined;
  }
  if (code === "not-signed-in") {
    return "signed-out";
  }
  return code === "reauthentication-required" ? "reauthentication-required" : "sign-in-required";
}

/** A 401 on a call that needs only a sign-in. */
export function signedOut(status: number): "signed-out" | undefined {
  return status === 401 ? "signed-out" : undefined;
}

/** The default `decodeError`: a JSON body, or undefined. */
async function readJson(response: Response): Promise<unknown> {
  const text = await response.text();
  if (text === "") {
    return undefined;
  }
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return undefined;
  }
}

function normalize(baseUrl: string): string {
  if (baseUrl.includes("?") || baseUrl.includes("#")) {
    throw new Error(`who-client: baseUrl must have no query or fragment, but is ${baseUrl}`);
  }
  return baseUrl.replace(/\/+$/, "");
}

function csrfToken(): string | undefined {
  const cookie = document.cookie.split("; ").find((c) => c.startsWith(`${CSRF_COOKIE}=`));
  return cookie === undefined ? undefined : decodeURIComponent(cookie.slice(CSRF_COOKIE.length + 1));
}

/** Sends who's requests: the URL, the cookies, CSRF, and reading the answer into a result. */
export class WhoHttp<D> {
  private readonly base: string;
  private readonly credentials: RequestCredentials;
  private readonly fetch: typeof fetch;
  private readonly decodeError: (response: Response) => Promise<D>;
  /** Sends the page elsewhere. */
  readonly navigate: (url: string) => void;

  constructor(options: WhoClientOptions<D>) {
    this.base = normalize(options.baseUrl ?? "/who");
    // an absolute base (scheme or //host) is another origin's, whose cookies must be asked for
    this.credentials = /^([a-z][a-z\d+.-]*:)?\/\//i.test(this.base) ? "include" : "same-origin";
    const given = options.fetch;
    // called as a plain function: a browser's fetch refuses to run with another `this`
    this.fetch = given ?? ((input, init) => fetch(input, init));
    this.decodeError = options.decodeError ?? (readJson as (response: Response) => Promise<D>);
    this.navigate = options.navigate ?? ((url) => location.assign(url));
  }

  /** A path below the base, as given (relative or absolute), for links the page renders. */
  href(path: string): string {
    return this.base + path;
  }

  /** The absolute URL of a path below the base. */
  url(path: string): string {
    return new URL(this.base + path, location.href).href;
  }

  /** Makes the call, answering its result; throws for anything the call does not expect. */
  async call<T, R extends string>(call: Call<T, R>): Promise<WhoResult<T, R, D>> {
    const response = await this.send(call.method, call.path, call.body, call.accept);
    const where = `${call.method} ${call.path}`;
    if (response.ok) {
      try {
        return { ok: true, value: await call.read(response) };
      } catch (e) {
        throw new WhoError(
          `who answered ${where} with a body it could not read`,
          "unexpected",
          response.status,
          undefined,
          {
            cause: e,
          },
        );
      }
    }
    const reason = call.reason(response.status, response.headers.get(ERROR_HEADER));
    if (reason === undefined) {
      throw new WhoError(`who answered ${where} with ${response.status}`, "unexpected", response.status);
    }
    return { ok: false, reason, status: response.status, detail: await this.decodeError(response) };
  }

  /** Asks who for a fresh CSRF cookie; `/me` sets one whether or not anyone is signed in. */
  async refreshCsrf(): Promise<void> {
    await this.fetch(this.url("/me"), { credentials: this.credentials });
  }

  private async send(method: string, path: string, body: unknown, accept: string | undefined): Promise<Response> {
    const unsafe = method !== "GET";
    // the cookie exists only after a response from who
    if (unsafe && csrfToken() === undefined) {
      await this.refreshCsrf();
    }
    const headers: Record<string, string> = {};
    if (accept !== undefined) {
      headers.Accept = accept;
    }
    if (body !== undefined) {
      headers["Content-Type"] = "application/json";
    }
    // read at each request, never kept: it changes at sign-in and sign-out
    const token = unsafe ? csrfToken() : undefined;
    if (token !== undefined) {
      headers[CSRF_HEADER] = token;
    }
    return this.fetch(this.url(path), {
      method,
      credentials: this.credentials,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  }
}
