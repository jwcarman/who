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
import { createWhoClient } from "./index.js";
import { at, server, setCsrfCookie } from "./testing/server.js";

const LISTED = {
  id: "k1",
  name: "CI",
  scopes: ["read"],
  createdAt: "2026-11-16T10:00:00Z",
  expiresAt: "2026-12-16T10:00:00Z",
  lastUsedAt: null,
};

const CREATED = { id: "k1", name: "CI", scopes: ["read"], expiresAt: "2026-12-16T10:00:00Z", key: "who_secret" };

beforeEach(() => setCsrfCookie("t"));

function refused(status: number, code?: string): HttpResponse<null> {
  return new HttpResponse(null, { status, headers: code ? { "Who-Error": code } : {} });
}

describe("me.apiKeys.list", () => {
  it("answers the keys", async () => {
    server.use(http.get(at("/who/me/api-keys"), () => HttpResponse.json([LISTED])));

    await expect(createWhoClient().me.apiKeys.list()).resolves.toEqual({ ok: true, value: [LISTED] });
  });

  it("answers signed-out on a 401", async () => {
    server.use(http.get(at("/who/me/api-keys"), () => refused(401, "not-signed-in")));

    await expect(createWhoClient().me.apiKeys.list()).resolves.toMatchObject({ ok: false, reason: "signed-out" });
  });
});

describe("me.apiKeys.create", () => {
  it("makes a key and answers it with its secret", async () => {
    let body: unknown;
    server.use(
      http.post(at("/who/me/api-keys"), async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(CREATED, { status: 201 });
      }),
    );

    await expect(
      createWhoClient().me.apiKeys.create({ name: "CI", lifetime: "P30D", scopes: ["read"] }),
    ).resolves.toEqual({ ok: true, value: CREATED });
    expect(body).toEqual({ name: "CI", lifetime: "P30D", scopes: ["read"] });
  });

  it.each([
    [401, "reauthentication-required", "reauthentication-required"],
    [401, "not-signed-in", "signed-out"],
    [400, "invalid-api-key-request", "invalid-api-key-request"],
  ])("answers %i %s as %s", async (status, code, reason) => {
    server.use(http.post(at("/who/me/api-keys"), () => refused(status, code)));

    await expect(
      createWhoClient().me.apiKeys.create({ name: "CI", lifetime: "P30D", scopes: [] }),
    ).resolves.toMatchObject({ ok: false, reason, status });
  });
});

describe("me.apiKeys.rotate", () => {
  it("rotates the escaped id with the overlap", async () => {
    let body: unknown;
    server.use(
      http.post(at("/who/me/api-keys/a%2Fb/rotations"), async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(CREATED, { status: 201 });
      }),
    );

    await expect(createWhoClient().me.apiKeys.rotate("a/b", "PT1H")).resolves.toEqual({ ok: true, value: CREATED });
    expect(body).toEqual({ overlap: "PT1H" });
  });

  it.each([
    [401, "reauthentication-required", "reauthentication-required"],
    [404, "api-key-not-found", "not-found"],
    [400, "invalid-api-key-request", "invalid-api-key-request"],
  ])("answers %i %s as %s", async (status, code, reason) => {
    server.use(http.post(at("/who/me/api-keys/k1/rotations"), () => refused(status, code)));

    await expect(createWhoClient().me.apiKeys.rotate("k1", "PT1H")).resolves.toMatchObject({
      ok: false,
      reason,
      status,
    });
  });
});

describe("me.apiKeys.revoke", () => {
  it("revokes by the escaped id", async () => {
    server.use(http.delete(at("/who/me/api-keys/a%2Fb"), () => new HttpResponse(null, { status: 204 })));

    await expect(createWhoClient().me.apiKeys.revoke("a/b")).resolves.toEqual({ ok: true, value: undefined });
  });

  it.each([
    [401, "not-signed-in", "signed-out"],
    [404, "api-key-not-found", "not-found"],
  ])("answers %i %s as %s", async (status, code, reason) => {
    server.use(http.delete(at("/who/me/api-keys/k1"), () => refused(status, code)));

    await expect(createWhoClient().me.apiKeys.revoke("k1")).resolves.toMatchObject({ ok: false, reason, status });
  });
});

describe("me.apiKeys.current", () => {
  it("answers the key this request used", async () => {
    server.use(http.get(at("/who/me/api-keys/current"), () => HttpResponse.json(LISTED)));

    await expect(createWhoClient().me.apiKeys.current()).resolves.toEqual({ ok: true, value: LISTED });
  });

  it.each([
    [401, "not-signed-in", "signed-out"],
    [404, "api-key-not-found", "not-found"],
  ])("answers %i %s as %s", async (status, code, reason) => {
    server.use(http.get(at("/who/me/api-keys/current"), () => refused(status, code)));

    await expect(createWhoClient().me.apiKeys.current()).resolves.toMatchObject({ ok: false, reason, status });
  });
});
