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
import { json, nothing, signedOut, signInRequired, type WhoHttp } from "./http.js";
import type { SignInRequired, WhoResult } from "./result.js";

/** One of your API keys, as lists show it: never its secret. */
export interface ApiKey {
  id: string;
  name: string;
  /** Its scopes, sorted. */
  scopes: string[];
  createdAt: string;
  /** When it stops, or stopped, signing in. */
  expiresAt: string;
  /** When it last signed in, to within who's last-used interval; null if never. */
  lastUsedAt: string | null;
}

/** A key just made or rotated: the only answer that holds the raw `key`, so show it once. */
export interface NewApiKey {
  id: string;
  name: string;
  scopes: string[];
  expiresAt: string;
  /** The raw key, which who never shows again. */
  key: string;
}

/** What a new key is. */
export interface ApiKeyRequest {
  name: string;
  /** How long it signs in for, as an ISO-8601 duration such as `P30D`. */
  lifetime: string;
  scopes: string[];
}

/** A body, lifetime, overlap, name or scope who refuses (`400`). */
export type InvalidApiKeyRequest = "invalid-api-key-request";

/**
 * Your API keys (`client.me.apiKeys`). A browser session or a bearer token may manage them; a
 * request an API key signed in may not, so a key never mints keys.
 */
export interface ApiKeys<D> {
  /** Your keys, by name. */
  list(): Promise<WhoResult<ApiKey[], "signed-out", D>>;
  /** Makes a key, answering its secret once. A browser needs a recent sign-in. */
  create(request: ApiKeyRequest): Promise<WhoResult<NewApiKey, SignInRequired | InvalidApiKeyRequest, D>>;
  /**
   * Replaces one of your keys with a new one, answering its secret once; the old key keeps working
   * for `overlap` (an ISO-8601 duration such as `PT1H`). A browser needs a recent sign-in.
   */
  rotate(
    id: string,
    overlap: string,
  ): Promise<WhoResult<NewApiKey, SignInRequired | "not-found" | InvalidApiKeyRequest, D>>;
  /** Revokes one of your keys; another identity's is `"not-found"`. */
  revoke(id: string): Promise<WhoResult<void, "signed-out" | "not-found", D>>;
  /** The key that signed this request in; `"not-found"` when no API key did. */
  current(): Promise<WhoResult<ApiKey, "signed-out" | "not-found", D>>;
}

function invalidRequest(status: number): InvalidApiKeyRequest | undefined {
  return status === 400 ? "invalid-api-key-request" : undefined;
}

function signedOutOrNotFound(status: number): "signed-out" | "not-found" | undefined {
  return signedOut(status) ?? (status === 404 ? "not-found" : undefined);
}

export function apiKeys<D>(http: WhoHttp<D>): ApiKeys<D> {
  return {
    list: () => http.call({ method: "GET", path: "/me/api-keys", read: json<ApiKey[]>(), reason: signedOut }),
    create: (request) =>
      http.call({
        method: "POST",
        path: "/me/api-keys",
        body: { name: request.name, lifetime: request.lifetime, scopes: request.scopes },
        read: json<NewApiKey>(),
        reason: (status, code) => signInRequired(status, code) ?? invalidRequest(status),
      }),
    rotate: (id, overlap) =>
      http.call({
        method: "POST",
        path: `/me/api-keys/${encodeURIComponent(id)}/rotations`,
        body: { overlap },
        read: json<NewApiKey>(),
        reason: (status, code): SignInRequired | "not-found" | InvalidApiKeyRequest | undefined =>
          signInRequired(status, code) ?? (status === 404 ? "not-found" : invalidRequest(status)),
      }),
    revoke: (id) =>
      http.call({
        method: "DELETE",
        path: `/me/api-keys/${encodeURIComponent(id)}`,
        read: nothing,
        reason: signedOutOrNotFound,
      }),
    current: () =>
      http.call({ method: "GET", path: "/me/api-keys/current", read: json<ApiKey>(), reason: signedOutOrNotFound }),
  };
}
