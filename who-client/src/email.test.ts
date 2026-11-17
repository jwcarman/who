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
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createWhoClient } from "./index.js";
import { at, server, setCsrfCookie } from "./testing/server.js";

beforeEach(() => setCsrfCookie("t"));
afterEach(() => history.replaceState(null, "", "/"));

function refused(status: number, code?: string): HttpResponse<null> {
  return new HttpResponse(null, { status, headers: code ? { "Who-Error": code } : {} });
}

describe("email.send", () => {
  it("asks for a link and code", async () => {
    let body: unknown;
    server.use(
      http.post(at("/who/email/send"), async ({ request }) => {
        body = await request.json();
        return new HttpResponse(null, { status: 202 });
      }),
    );

    await expect(createWhoClient().email.send("alice@example.com")).resolves.toEqual({
      ok: true,
      value: undefined,
    });

    expect(body).toEqual({ email: "alice@example.com" });
  });

  it("sends a human check token only when given one", async () => {
    const seen: (string | null)[] = [];
    server.use(
      http.post(at("/who/email/send"), ({ request }) => {
        seen.push(request.headers.get("Who-Human-Check"));
        return new HttpResponse(null, { status: 202 });
      }),
    );

    await createWhoClient().email.send("alice@example.com", { humanCheck: "tok" });
    await createWhoClient().email.send("alice@example.com");

    expect(seen).toEqual(["tok", null]);
  });

  it("answers human-check-failed when the check refuses", async () => {
    server.use(http.post(at("/who/email/send"), () => refused(403, "human-check-failed")));

    await expect(createWhoClient().email.send("alice@example.com")).resolves.toMatchObject({
      ok: false,
      reason: "human-check-failed",
    });
  });
});

describe("email.signIn", () => {
  it.each([[{ token: "abc" }], [{ email: "alice@example.com", code: "123456" }]])(
    "signs in with %o",
    async (credentials) => {
      let body: unknown;
      server.use(
        http.post(at("/who/email/sign-in"), async ({ request }) => {
          body = await request.json();
          return HttpResponse.json({ identityId: "i1" });
        }),
      );

      await expect(createWhoClient().email.signIn(credentials)).resolves.toEqual({
        ok: true,
        value: { identityId: "i1" },
      });
      expect(body).toEqual(credentials);
    },
  );

  it("sends a human check token and answers human-check-failed", async () => {
    let check: string | null = null;
    server.use(
      http.post(at("/who/email/sign-in"), ({ request }) => {
        check = request.headers.get("Who-Human-Check");
        return refused(403, "human-check-failed");
      }),
    );

    await expect(
      createWhoClient().email.signIn({ email: "alice@example.com", code: "123456" }, { humanCheck: "tok" }),
    ).resolves.toMatchObject({ ok: false, reason: "human-check-failed" });
    expect(check).toBe("tok");
  });

  it("throws on another 403", async () => {
    server.use(http.post(at("/who/email/sign-in"), () => refused(403)));

    await expect(createWhoClient().email.signIn({ token: "abc" })).rejects.toThrow();
  });

  it("answers rejected on a 401", async () => {
    server.use(http.post(at("/who/email/sign-in"), () => refused(401, "sign-in-failed")));

    await expect(createWhoClient().email.signIn({ token: "bad" })).resolves.toMatchObject({
      ok: false,
      reason: "rejected",
    });
  });
});

describe("email.signInFromLocation", () => {
  it("answers null and sends nothing without a token", async () => {
    history.replaceState(null, "", "/signin#other=1");

    await expect(createWhoClient().email.signInFromLocation()).resolves.toBeNull();
  });

  it("takes the token out of the address bar before signing in, keeping the path and query", async () => {
    history.replaceState(null, "", "/signin?next=%2Fboard#token=abc&x=1");
    const replace = vi.spyOn(history, "replaceState");
    let urlAtRequest = "";
    let body: unknown;
    server.use(
      http.post(at("/who/email/sign-in"), async ({ request }) => {
        urlAtRequest = location.href;
        body = await request.json();
        return HttpResponse.json({ identityId: "i1" });
      }),
    );

    await expect(createWhoClient().email.signInFromLocation()).resolves.toMatchObject({ ok: true });

    expect(replace).toHaveBeenCalledWith(null, "", "/signin?next=%2Fboard");
    expect(urlAtRequest).toBe(at("/signin?next=%2Fboard"));
    expect(body).toEqual({ token: "abc" });
  });

  it("takes the token out even when the sign-in fails", async () => {
    history.replaceState(null, "", "/signin#token=bad");
    server.use(http.post(at("/who/email/sign-in"), () => refused(401)));

    await createWhoClient().email.signInFromLocation();

    expect(location.hash).toBe("");
  });
});

describe("me.emailAddresses.add", () => {
  it("adds an address", async () => {
    let body: unknown;
    server.use(
      http.post(at("/who/me/email-addresses"), async ({ request }) => {
        body = await request.json();
        return new HttpResponse(null, { status: 202 });
      }),
    );

    await expect(createWhoClient().me.emailAddresses.add("bob@example.com")).resolves.toEqual({
      ok: true,
      value: undefined,
    });
    expect(body).toEqual({ address: "bob@example.com" });
  });

  it("replaces an address", async () => {
    let body: unknown;
    server.use(
      http.post(at("/who/me/email-addresses"), async ({ request }) => {
        body = await request.json();
        return new HttpResponse(null, { status: 202 });
      }),
    );

    await createWhoClient().me.emailAddresses.add("bob@example.com", "id1");

    expect(body).toEqual({ address: "bob@example.com", replaces: "id1" });
  });

  it.each([
    [401, "reauthentication-required", "reauthentication-required"],
    [401, "not-signed-in", "signed-out"],
    [404, "email-address-not-found", "not-found"],
  ])("answers %i %s as %s", async (status, code, reason) => {
    server.use(http.post(at("/who/me/email-addresses"), () => refused(status, code)));

    await expect(createWhoClient().me.emailAddresses.add("bob@example.com", "id1")).resolves.toMatchObject({
      ok: false,
      reason,
    });
  });
});

describe("me.emailAddresses.confirm", () => {
  it.each([[{ token: "abc" }], [{ code: "123456" }]])("confirms with %o", async (proof) => {
    let body: unknown;
    server.use(
      http.post(at("/who/me/email-addresses/confirmations"), async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ credentialId: "c1" });
      }),
    );

    await expect(createWhoClient().me.emailAddresses.confirm(proof)).resolves.toEqual({
      ok: true,
      value: { credentialId: "c1" },
    });
    expect(body).toEqual(proof);
  });

  it.each([
    [400, "invalid-confirmation", "rejected"],
    [409, "email-address-taken", "conflict"],
    [401, "reauthentication-required", "reauthentication-required"],
  ])("answers %i %s as %s", async (status, code, reason) => {
    server.use(http.post(at("/who/me/email-addresses/confirmations"), () => refused(status, code)));

    await expect(createWhoClient().me.emailAddresses.confirm({ code: "000000" })).resolves.toMatchObject({
      ok: false,
      reason,
    });
  });
});

describe("me.emailAddresses.confirmFromLocation", () => {
  it("answers null without a confirm-address token", async () => {
    history.replaceState(null, "", "/profile");

    await expect(createWhoClient().me.emailAddresses.confirmFromLocation()).resolves.toBeNull();
  });

  it("takes the token out of the address bar before confirming", async () => {
    history.replaceState(null, "", "/profile?tab=email#confirm-address=abc");
    let urlAtRequest = "";
    let body: unknown;
    server.use(
      http.post(at("/who/me/email-addresses/confirmations"), async ({ request }) => {
        urlAtRequest = location.href;
        body = await request.json();
        return HttpResponse.json({ credentialId: "c1" });
      }),
    );

    await expect(createWhoClient().me.emailAddresses.confirmFromLocation()).resolves.toEqual({
      ok: true,
      value: { credentialId: "c1" },
    });

    expect(urlAtRequest).toBe(at("/profile?tab=email"));
    expect(body).toEqual({ token: "abc" });
  });
});

describe("me.emailAddresses.list", () => {
  it("answers the addresses", async () => {
    const address = { id: "e1", address: "bob@example.com", created: "2026-11-16T10:00:00Z" };
    server.use(http.get(at("/who/me/email-addresses"), () => HttpResponse.json([address])));

    await expect(createWhoClient().me.emailAddresses.list()).resolves.toEqual({ ok: true, value: [address] });
  });

  it("answers signed-out on a 401", async () => {
    server.use(http.get(at("/who/me/email-addresses"), () => refused(401, "not-signed-in")));

    await expect(createWhoClient().me.emailAddresses.list()).resolves.toMatchObject({
      ok: false,
      reason: "signed-out",
    });
  });
});

describe("me.emailAddresses.remove", () => {
  it("removes by the escaped id", async () => {
    server.use(http.delete(at("/who/me/email-addresses/a%2Fb"), () => new HttpResponse(null, { status: 204 })));

    await expect(createWhoClient().me.emailAddresses.remove("a/b")).resolves.toEqual({ ok: true, value: undefined });
  });

  it.each([
    [401, "reauthentication-required", "reauthentication-required"],
    [401, "not-signed-in", "signed-out"],
    [404, "email-address-not-found", "not-found"],
    [409, "last-credential", "last-credential"],
  ])("answers %i %s as %s", async (status, code, reason) => {
    server.use(http.delete(at("/who/me/email-addresses/e1"), () => refused(status, code)));

    await expect(createWhoClient().me.emailAddresses.remove("e1")).resolves.toMatchObject({
      ok: false,
      reason,
      status,
    });
  });

  it("throws on a 409 that does not say last-credential", async () => {
    server.use(http.delete(at("/who/me/email-addresses/e1"), () => refused(409)));

    await expect(createWhoClient().me.emailAddresses.remove("e1")).rejects.toMatchObject({
      reason: "unexpected",
      status: 409,
    });
  });
});
