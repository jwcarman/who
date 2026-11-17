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
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vitest";
import { createWhoClient, type WhoClient } from "./index.js";
import { at, server, setCsrfCookie } from "./testing/server.js";

beforeEach(() => setCsrfCookie("t"));

type Method = "get" | "post" | "put" | "patch" | "delete";

/** A call that needs a browser session on its own, and the request it sends first. */
type BrowserOnly = [name: string, call: (who: WhoClient<unknown>) => Promise<unknown>, method: Method, path: string];

const BROWSER_ONLY: BrowserOnly[] = [
  ["me.sessions.list", (who) => who.me.sessions.list(), "get", "/who/me/sessions"],
  ["me.sessions.end", (who) => who.me.sessions.end("s1"), "delete", "/who/me/sessions/s1"],
  ["me.sessions.endOthers", (who) => who.me.sessions.endOthers(), "delete", "/who/me/sessions"],
  ["me.passkeys.list", (who) => who.me.passkeys.list(), "get", "/who/me/passkeys"],
  ["me.passkeys.register", (who) => who.me.passkeys.register("Laptop"), "post", "/who/me/passkeys/options"],
  ["me.passkeys.remove", (who) => who.me.passkeys.remove("c1"), "delete", "/who/me/passkeys/c1"],
  ["me.emailAddresses.add", (who) => who.me.emailAddresses.add("a@example.com"), "post", "/who/me/email-addresses"],
  [
    "me.emailAddresses.confirm",
    (who) => who.me.emailAddresses.confirm({ code: "123456" }),
    "post",
    "/who/me/email-addresses/confirmations",
  ],
  ["me.emailAddresses.remove", (who) => who.me.emailAddresses.remove("e1"), "delete", "/who/me/email-addresses/e1"],
  ["me.accounts.link", (who) => who.me.accounts.link("github"), "post", "/who/me/accounts/github"],
  ["me.accounts.unlink", (who) => who.me.accounts.unlink("a1"), "delete", "/who/me/accounts/a1"],
  ["me.apiKeys.list", (who) => who.me.apiKeys.list(), "get", "/who/me/api-keys"],
  [
    "me.apiKeys.create",
    (who) => who.me.apiKeys.create({ name: "CI", lifetime: "P1D", scopes: ["x"] }),
    "post",
    "/who/me/api-keys",
  ],
  ["me.apiKeys.rotate", (who) => who.me.apiKeys.rotate("k1", "PT1H"), "post", "/who/me/api-keys/k1/rotations"],
  ["me.apiKeys.revoke", (who) => who.me.apiKeys.revoke("k1"), "delete", "/who/me/api-keys/k1"],
  [
    "admin.identities.setStatus",
    (who) => who.admin.identities.setStatus("i1", "DISABLED"),
    "patch",
    "/who/identities/i1",
  ],
  ["admin.identities.addRole", (who) => who.admin.identities.addRole("i1", "r"), "put", "/who/identities/i1/roles/r"],
  [
    "admin.identities.removeRole",
    (who) => who.admin.identities.removeRole("i1", "r"),
    "delete",
    "/who/identities/i1/roles/r",
  ],
  ["admin.invite", (who) => who.admin.invite("a@example.com"), "post", "/who/invitations"],
];

const API_KEY_MANAGEMENT = BROWSER_ONLY.filter(([name]) => name.startsWith("me.apiKeys."));

function refused(code: string): HttpResponse<null> {
  return new HttpResponse(null, { status: 403, headers: { "Who-Error": code } });
}

describe("a call that needs a browser session on its own", () => {
  it.each(BROWSER_ONLY)("%s answers browser-session-required", async (_name, call, method, path) => {
    server.use(http[method](at(path), () => refused("browser-session-required")));

    await expect(call(createWhoClient())).resolves.toMatchObject({
      ok: false,
      reason: "browser-session-required",
      status: 403,
    });
  });
});

describe("managing API keys with an API key", () => {
  it.each(API_KEY_MANAGEMENT)("%s answers api-key-cannot-manage-keys", async (_name, call, method, path) => {
    server.use(http[method](at(path), () => refused("api-key-cannot-manage-keys")));

    await expect(call(createWhoClient())).resolves.toMatchObject({
      ok: false,
      reason: "api-key-cannot-manage-keys",
      status: 403,
    });
  });
});
