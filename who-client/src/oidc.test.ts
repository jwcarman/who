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
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createWhoClient } from "./index";
import { at, server, setCsrfCookie } from "./testing/server";

beforeEach(() => setCsrfCookie("t"));

function refused(status: number, code?: string): HttpResponse<null> {
  return new HttpResponse(null, { status, headers: code ? { "Who-Error": code } : {} });
}

describe("oidc.authorizeUrl", () => {
  it("is the provider's authorize path below the base, escaped", () => {
    expect(createWhoClient({ baseUrl: "/auth" }).oidc.authorizeUrl("a b")).toBe("/auth/oidc/authorize/a%20b");
  });

  it("keeps an absolute base", () => {
    expect(createWhoClient({ baseUrl: "https://auth.example.com/who" }).oidc.authorizeUrl("google")).toBe(
      "https://auth.example.com/who/oidc/authorize/google",
    );
  });
});

describe("oidc.pending", () => {
  it("describes the pending provider account", async () => {
    const pending = { provider: "google", displayName: "Alice", canSignUp: true, canLink: false };
    server.use(http.get(at("/who/oidc/pending"), () => HttpResponse.json(pending)));

    await expect(createWhoClient().oidc.pending()).resolves.toEqual({ ok: true, value: pending });
  });

  it("answers none-pending on a 404", async () => {
    server.use(http.get(at("/who/oidc/pending"), () => refused(404, "no-pending-sign-up")));

    await expect(createWhoClient().oidc.pending()).resolves.toMatchObject({ ok: false, reason: "none-pending" });
  });
});

describe("oidc.signUp", () => {
  it("signs up", async () => {
    server.use(http.post(at("/who/oidc/sign-up"), () => HttpResponse.json({ identityId: "i1" })));

    await expect(createWhoClient().oidc.signUp()).resolves.toEqual({ ok: true, value: { identityId: "i1" } });
  });

  it.each([
    [403, "sign-up-refused", "refused"],
    [404, "no-pending-sign-up", "none-pending"],
  ])("answers %i %s as %s", async (status, code, reason) => {
    server.use(http.post(at("/who/oidc/sign-up"), () => refused(status, code)));

    await expect(createWhoClient().oidc.signUp()).resolves.toMatchObject({ ok: false, reason });
  });
});

describe("oidc.link", () => {
  it("links the pending account", async () => {
    server.use(http.post(at("/who/oidc/link"), () => HttpResponse.json({ credentialId: "c1" })));

    await expect(createWhoClient().oidc.link()).resolves.toEqual({ ok: true, value: undefined });
  });

  it.each([
    [409, "already-linked", "conflict"],
    [404, "no-pending-sign-up", "none-pending"],
    [401, "reauthentication-required", "reauthentication-required"],
    [401, "not-signed-in", "signed-out"],
  ])("answers %i %s as %s", async (status, code, reason) => {
    server.use(http.post(at("/who/oidc/link"), () => refused(status, code)));

    await expect(createWhoClient().oidc.link()).resolves.toMatchObject({ ok: false, reason });
  });
});

describe("oidc.startLink", () => {
  it("asks for JSON, then sends the page to the provider and never settles", async () => {
    let accept: string | null = null;
    server.use(
      http.post(at("/who/oidc/link/a%20b"), ({ request }) => {
        accept = request.headers.get("Accept");
        return HttpResponse.json({ redirectUrl: "https://provider.example/authorize?x=1" });
      }),
    );
    const assign = vi.fn<(url: string) => void>();
    const who = createWhoClient({ navigate: assign });

    let settled = false;
    void who.oidc.startLink("a b").finally(() => {
      settled = true;
    });
    await vi.waitFor(() => expect(assign).toHaveBeenCalledWith("https://provider.example/authorize?x=1"));
    await new Promise((resolve) => setTimeout(resolve, 20));

    expect(accept).toBe("application/json");
    expect(settled).toBe(false);
  });

  it.each([
    [404, "unknown-provider", "not-found"],
    [401, "reauthentication-required", "reauthentication-required"],
  ])("answers %i %s as %s", async (status, code, reason) => {
    server.use(http.post(at("/who/oidc/link/google"), () => refused(status, code)));

    await expect(createWhoClient().oidc.startLink("google")).resolves.toMatchObject({ ok: false, reason });
  });
});
