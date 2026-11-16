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
import { setupServer } from "msw/node";

/**
 * The fake who every test runs against (Mock Service Worker). It starts with no handlers: a test
 * answers what it expects with `server.use(http.get(...))`, and the handlers go after each test.
 */
export const server = setupServer();

const unanswered: string[] = [];

/** Starts the fake server. A request no handler answers fails the test that sent it. */
export function listen(): void {
  server.events.on("request:unhandled", ({ request }) => {
    unanswered.push(`${request.method} ${new URL(request.url).pathname}`);
  });
  server.listen({ onUnhandledFrame: "error" });
}

/** Throws if the test sent a request no handler answered. */
export function checkAllAnswered(): void {
  const requests = unanswered.splice(0);
  if (requests.length > 0) {
    throw new Error(`No handler answered: ${requests.join(", ")}`);
  }
}

/** An absolute URL on the page's origin, as the client sends it. */
export function at(path: string): string {
  return new URL(path, location.href).href;
}

/** Sets the CSRF cookie the way who's response would, as the browser stores it. */
export function setCsrfCookie(value: string): void {
  document.cookie = `XSRF-TOKEN=${value}; path=/`;
}

/** Removes the CSRF cookie. */
export function clearCsrfCookie(): void {
  document.cookie = "XSRF-TOKEN=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT";
}
