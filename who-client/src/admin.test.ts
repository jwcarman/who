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

const IDENTITY = {
  id: "i1",
  status: "ACTIVE",
  created: "2026-11-16T10:00:00Z",
  roles: ["admin"],
  emailAddresses: ["alice@example.com"],
  credentials: { email: 1, passkey: 0, account: 0 },
};

beforeEach(() => setCsrfCookie("t"));

function refused(status: number, code?: string): HttpResponse<null> {
  return new HttpResponse(null, { status, headers: code ? { "Who-Error": code } : {} });
}

describe("admin.identities.list", () => {
  it("answers a page, sending only the filters given", async () => {
    let query = "";
    const page = { items: [IDENTITY], page: 1, size: 10, total: 11 };
    server.use(
      http.get(at("/who/identities"), ({ request }) => {
        query = new URL(request.url).search;
        return HttpResponse.json(page);
      }),
    );

    await expect(
      createWhoClient().admin.identities.list({ status: "ACTIVE", role: "a b", page: 1, size: 10 }),
    ).resolves.toEqual({ ok: true, value: page });
    expect(query).toBe("?status=ACTIVE&role=a+b&page=1&size=10");
  });

  it("sends no query without filters", async () => {
    let query = "unset";
    server.use(
      http.get(at("/who/identities"), ({ request }) => {
        query = new URL(request.url).search;
        return HttpResponse.json({ items: [], page: 0, size: 20, total: 0 });
      }),
    );

    await createWhoClient().admin.identities.list();

    expect(query).toBe("");
  });

  it.each([
    [401, "not-signed-in", "signed-out"],
    [403, "permission-required", "permission-required"],
    [400, "invalid-admin-request", "invalid-admin-request"],
  ])("answers %i %s as %s", async (status, code, reason) => {
    server.use(http.get(at("/who/identities"), () => refused(status, code)));

    await expect(createWhoClient().admin.identities.list({ email: "x" })).resolves.toMatchObject({
      ok: false,
      reason,
      status,
    });
  });
});

describe("admin.identities.get", () => {
  it("answers one identity by its escaped id", async () => {
    server.use(http.get(at("/who/identities/a%2Fb"), () => HttpResponse.json(IDENTITY)));

    await expect(createWhoClient().admin.identities.get("a/b")).resolves.toEqual({ ok: true, value: IDENTITY });
  });

  it.each([
    [401, "not-signed-in", "signed-out"],
    [403, "permission-required", "permission-required"],
    [404, "identity-not-found", "identity-not-found"],
  ])("answers %i %s as %s", async (status, code, reason) => {
    server.use(http.get(at("/who/identities/i1"), () => refused(status, code)));

    await expect(createWhoClient().admin.identities.get("i1")).resolves.toMatchObject({ ok: false, reason, status });
  });
});

describe("admin.identities.setStatus", () => {
  it("patches the status and answers the identity", async () => {
    let body: unknown;
    let csrf: string | null = null;
    server.use(
      http.patch(at("/who/identities/i1"), async ({ request }) => {
        body = await request.json();
        csrf = request.headers.get("X-XSRF-TOKEN");
        return HttpResponse.json({ ...IDENTITY, status: "DISABLED" });
      }),
    );

    await expect(createWhoClient().admin.identities.setStatus("i1", "DISABLED")).resolves.toEqual({
      ok: true,
      value: { ...IDENTITY, status: "DISABLED" },
    });
    expect(body).toEqual({ status: "DISABLED" });
    expect(csrf).toBe("t");
  });

  it.each([
    [401, "reauthentication-required", "reauthentication-required"],
    [401, "not-signed-in", "signed-out"],
    [403, "permission-required", "permission-required"],
    [404, "identity-not-found", "identity-not-found"],
    [400, "invalid-admin-request", "invalid-admin-request"],
    [409, "self-deactivation", "self-deactivation"],
  ])("answers %i %s as %s", async (status, code, reason) => {
    server.use(http.patch(at("/who/identities/i1"), () => refused(status, code)));

    await expect(createWhoClient().admin.identities.setStatus("i1", "DISABLED")).resolves.toMatchObject({
      ok: false,
      reason,
      status,
    });
  });

  it("throws on a 409 that does not say self-deactivation", async () => {
    server.use(http.patch(at("/who/identities/i1"), () => refused(409)));

    await expect(createWhoClient().admin.identities.setStatus("i1", "DISABLED")).rejects.toMatchObject({
      reason: "unexpected",
      status: 409,
    });
  });
});

describe("admin.identities.addRole", () => {
  it("puts the escaped role", async () => {
    server.use(http.put(at("/who/identities/i1/roles/a%2Fb"), () => new HttpResponse(null, { status: 204 })));

    await expect(createWhoClient().admin.identities.addRole("i1", "a/b")).resolves.toEqual({
      ok: true,
      value: undefined,
    });
  });

  it.each([
    [401, "reauthentication-required", "reauthentication-required"],
    [403, "permission-required", "permission-required"],
    [403, "role-grant-forbidden", "role-grant-forbidden"],
    [404, "identity-not-found", "identity-not-found"],
    [404, "unknown-role", "unknown-role"],
  ])("answers %i %s as %s", async (status, code, reason) => {
    server.use(http.put(at("/who/identities/i1/roles/admin"), () => refused(status, code)));

    await expect(createWhoClient().admin.identities.addRole("i1", "admin")).resolves.toMatchObject({
      ok: false,
      reason,
      status,
    });
  });
});

describe("admin.identities.removeRole", () => {
  it("deletes the escaped role", async () => {
    server.use(http.delete(at("/who/identities/i1/roles/a%2Fb"), () => new HttpResponse(null, { status: 204 })));

    await expect(createWhoClient().admin.identities.removeRole("i1", "a/b")).resolves.toEqual({
      ok: true,
      value: undefined,
    });
  });

  it.each([
    [401, "reauthentication-required", "reauthentication-required"],
    [403, "permission-required", "permission-required"],
    [404, "identity-not-found", "identity-not-found"],
    [404, "unknown-role", "unknown-role"],
  ])("answers %i %s as %s", async (status, code, reason) => {
    server.use(http.delete(at("/who/identities/i1/roles/admin"), () => refused(status, code)));

    await expect(createWhoClient().admin.identities.removeRole("i1", "admin")).resolves.toMatchObject({
      ok: false,
      reason,
      status,
    });
  });
});

describe("admin.roles.list", () => {
  it("answers the roles", async () => {
    const roles = [{ name: "admin", permissions: ["who.identities.manage", "who.identities.read"] }];
    server.use(http.get(at("/who/roles"), () => HttpResponse.json(roles)));

    await expect(createWhoClient().admin.roles.list()).resolves.toEqual({ ok: true, value: roles });
  });

  it.each([
    [401, "not-signed-in", "signed-out"],
    [403, "permission-required", "permission-required"],
  ])("answers %i %s as %s", async (status, code, reason) => {
    server.use(http.get(at("/who/roles"), () => refused(status, code)));

    await expect(createWhoClient().admin.roles.list()).resolves.toMatchObject({ ok: false, reason, status });
  });
});

describe("admin.invite", () => {
  it("invites the address with its roles", async () => {
    let body: unknown;
    server.use(
      http.post(at("/who/invitations"), async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ identityId: "i2", linkSent: true }, { status: 201 });
      }),
    );

    await expect(createWhoClient().admin.invite("bob@example.com", ["member"])).resolves.toEqual({
      ok: true,
      value: { identityId: "i2", linkSent: true },
    });
    expect(body).toEqual({ email: "bob@example.com", roles: ["member"] });
  });

  it("invites with no roles", async () => {
    let body: unknown;
    server.use(
      http.post(at("/who/invitations"), async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ identityId: "i2", linkSent: false }, { status: 201 });
      }),
    );

    await createWhoClient().admin.invite("bob@example.com");

    expect(body).toEqual({ email: "bob@example.com", roles: [] });
  });

  it.each([
    [401, "reauthentication-required", "reauthentication-required"],
    [403, "permission-required", "permission-required"],
    [403, "role-grant-forbidden", "role-grant-forbidden"],
    [400, "invalid-invitation", "invalid-invitation"],
    [400, "invalid-admin-request", "invalid-admin-request"],
    [409, "email-address-taken", "email-address-taken"],
  ])("answers %i %s as %s", async (status, code, reason) => {
    server.use(http.post(at("/who/invitations"), () => refused(status, code)));

    await expect(createWhoClient().admin.invite("bob@example.com", ["member"])).resolves.toMatchObject({
      ok: false,
      reason,
      status,
    });
  });
});
