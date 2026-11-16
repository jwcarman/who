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
import { describe, expect, it, vi } from "vitest";
import { createWhoClient, WhoError } from "./index.js";
import { at, server, setCsrfCookie } from "./testing/server.js";

/** A fetch that records each request's init, then lets it through to the fake server. */
function recordingFetch(): { fetch: typeof fetch; calls: [string, RequestInit | undefined][] } {
  const calls: [string, RequestInit | undefined][] = [];
  return {
    calls,
    fetch: (input, init) => {
      calls.push([typeof input === "string" ? input : input instanceof URL ? input.href : input.url, init]);
      return fetch(input, init);
    },
  };
}

describe("createWhoClient", () => {
  it("defaults to /who", async () => {
    server.use(http.get(at("/who/me"), () => HttpResponse.json({ identityId: "i", permissions: [] })));

    await expect(createWhoClient().me()).resolves.toMatchObject({ ok: true });
  });

  it("drops a trailing slash from baseUrl", async () => {
    server.use(http.get(at("/auth/me"), () => HttpResponse.json({ identityId: "i", permissions: [] })));

    await expect(createWhoClient({ baseUrl: "/auth/" }).me()).resolves.toMatchObject({ ok: true });
  });

  it.each(["/who?x=1", "/who#x"])("refuses a baseUrl with a query or fragment: %s", (baseUrl) => {
    expect(() => createWhoClient({ baseUrl })).toThrow(/baseUrl/);
  });

  it("sends cookies same-origin for a relative baseUrl", async () => {
    server.use(http.get(at("/who/me"), () => HttpResponse.json({ identityId: "i", permissions: [] })));
    const recording = recordingFetch();

    await createWhoClient({ fetch: recording.fetch }).me();

    expect(recording.calls[0]?.[1]?.credentials).toBe("same-origin");
  });

  it("sends cookies to an absolute baseUrl", async () => {
    server.use(
      http.get("https://auth.example.com/who/me", () => HttpResponse.json({ identityId: "i", permissions: [] })),
    );
    const recording = recordingFetch();

    await createWhoClient({ baseUrl: "https://auth.example.com/who", fetch: recording.fetch }).me();

    expect(recording.calls[0]?.[0]).toBe("https://auth.example.com/who/me");
    expect(recording.calls[0]?.[1]?.credentials).toBe("include");
  });
});

describe("CSRF", () => {
  it("fetches the cookie before the first unsafe request, then echoes it decoded", async () => {
    const order: string[] = [];
    server.use(
      http.get(at("/who/me"), () => {
        order.push("GET /who/me");
        setCsrfCookie("a%2Fb");
        return new HttpResponse(null, { status: 401 });
      }),
      http.delete(at("/who/sessions/a"), ({ request }) => {
        order.push(`DELETE /who/sessions/a ${request.headers.get("X-XSRF-TOKEN")}`);
        return new HttpResponse(null, { status: 204 });
      }),
    );

    await expect(createWhoClient().sessions.end("a")).resolves.toEqual({ ok: true, value: undefined });

    expect(order).toEqual(["GET /who/me", "DELETE /who/sessions/a a/b"]);
  });

  it("reads the cookie fresh on every request", async () => {
    const tokens: (string | null)[] = [];
    server.use(
      http.delete(at("/who/sessions/a"), ({ request }) => {
        tokens.push(request.headers.get("X-XSRF-TOKEN"));
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const who = createWhoClient();

    setCsrfCookie("one");
    await who.sessions.end("a");
    setCsrfCookie("two");
    await who.sessions.end("a");

    expect(tokens).toEqual(["one", "two"]);
  });

  it("sends no CSRF header on a GET", async () => {
    setCsrfCookie("t");
    let header: string | null = "unset";
    server.use(
      http.get(at("/who/me"), ({ request }) => {
        header = request.headers.get("X-XSRF-TOKEN");
        return HttpResponse.json({ identityId: "i", permissions: [] });
      }),
    );

    await createWhoClient().me();

    expect(header).toBeNull();
  });
});

describe("me", () => {
  it("answers the signed-in identity", async () => {
    server.use(http.get(at("/who/me"), () => HttpResponse.json({ identityId: "i1", permissions: ["read"] })));

    await expect(createWhoClient().me()).resolves.toEqual({
      ok: true,
      value: { identityId: "i1", permissions: ["read"] },
    });
  });

  it("answers signed-out on a 401", async () => {
    server.use(http.get(at("/who/me"), () => new HttpResponse(null, { status: 401 })));

    await expect(createWhoClient().me()).resolves.toEqual({
      ok: false,
      reason: "signed-out",
      status: 401,
      detail: undefined,
    });
  });

  it("throws on a status it does not expect", async () => {
    server.use(http.get(at("/who/me"), () => new HttpResponse(null, { status: 500 })));

    const failure = createWhoClient().me();

    await expect(failure).rejects.toBeInstanceOf(WhoError);
    await expect(failure).rejects.toMatchObject({ reason: "unexpected", status: 500 });
  });

  it("throws on a success body that is not JSON", async () => {
    server.use(http.get(at("/who/me"), () => new HttpResponse("nope", { status: 200 })));

    await expect(createWhoClient().me()).rejects.toMatchObject({ reason: "unexpected", status: 200 });
  });

  it("lets a network failure through as it is", async () => {
    server.use(http.get(at("/who/me"), () => HttpResponse.error()));

    await expect(createWhoClient().me()).rejects.toBeInstanceOf(TypeError);
  });
});

describe("decodeError", () => {
  it("by default parses a JSON error body", async () => {
    server.use(http.get(at("/who/me"), () => HttpResponse.json({ title: "Sign in first" }, { status: 401 })));

    await expect(createWhoClient().me()).resolves.toMatchObject({ detail: { title: "Sign in first" } });
  });

  it("by default gives undefined for a body that is not JSON", async () => {
    server.use(http.get(at("/who/me"), () => new HttpResponse("<html>", { status: 401 })));

    await expect(createWhoClient().me()).resolves.toMatchObject({ detail: undefined });
  });

  it("puts the application's decoding in detail", async () => {
    server.use(http.get(at("/who/me"), () => HttpResponse.json({ detail: "Sign in first" }, { status: 401 })));
    const who = createWhoClient({
      decodeError: async (response) => ((await response.json()) as { detail: string }).detail,
    });

    const result = await who.me();

    expect(result.ok ? undefined : result.detail).toBe("Sign in first");
  });

  it("lets the decoder's own failure through", async () => {
    server.use(http.get(at("/who/me"), () => new HttpResponse(null, { status: 401 })));
    const broken = new Error("decoder broke");
    const who = createWhoClient({ decodeError: () => Promise.reject(broken) });

    await expect(who.me()).rejects.toBe(broken);
  });
});

describe("signOut", () => {
  it("signs out, then fetches a new CSRF cookie", async () => {
    setCsrfCookie("t");
    const order: string[] = [];
    server.use(
      http.post(at("/who/logout"), () => {
        order.push("POST /who/logout");
        return new HttpResponse(null, { status: 204 });
      }),
      http.get(at("/who/me"), () => {
        order.push("GET /who/me");
        return new HttpResponse(null, { status: 401 });
      }),
    );

    await createWhoClient().signOut();

    expect(order).toEqual(["POST /who/logout", "GET /who/me"]);
  });

  it("uses the fetch it was given for every request", async () => {
    setCsrfCookie("t");
    server.use(
      http.post(at("/who/logout"), () => new HttpResponse(null, { status: 204 })),
      http.get(at("/who/me"), () => new HttpResponse(null, { status: 401 })),
    );
    const spy = vi.fn(fetch);

    await createWhoClient({ fetch: spy }).signOut();

    expect(spy).toHaveBeenCalledTimes(2);
  });
});
