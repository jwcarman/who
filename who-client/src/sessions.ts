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
import { json, nothing, signedOut, signInRequired, type WhoHttp } from "./http";
import type { SignInRequired, WhoResult } from "./result";

/**
 * One browser this person is signed in on. The id is who's own, never the session cookie, so the
 * list can be shown without exposing a session.
 */
export interface Session {
  id: string;
  device: string;
  userAgent: string | null;
  signInMethod: string;
  signedInAt: string;
  lastSeenAt: string;
  current: boolean;
}

/** Where this person is signed in, and signing browsers out. */
export interface Sessions<D> {
  /** Where this person is signed in, newest first. */
  list(): Promise<WhoResult<Session[], "signed-out", D>>;
  /** Signs one browser out. This browser's own session ends at once; another needs a recent sign-in. */
  end(id: string): Promise<WhoResult<void, SignInRequired | "not-found", D>>;
  /** Signs out every browser but this one; needs a recent sign-in. */
  endOthers(): Promise<WhoResult<{ ended: number }, SignInRequired, D>>;
}

export function sessions<D>(http: WhoHttp<D>): Sessions<D> {
  return {
    list: () => http.call({ method: "GET", path: "/sessions", read: json<Session[]>(), reason: signedOut }),
    end: (id) =>
      http.call({
        method: "DELETE",
        path: `/sessions/${encodeURIComponent(id)}`,
        read: nothing,
        reason: (status, code) => signInRequired(status, code) ?? (status === 404 ? "not-found" : undefined),
      }),
    endOthers: () =>
      http.call({
        method: "DELETE",
        path: "/sessions?others=true",
        read: json<{ ended: number }>(),
        reason: signInRequired,
      }),
  };
}
