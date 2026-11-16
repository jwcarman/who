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
import { encode } from "./base64url.js";
import { createWhoClient } from "./index.js";
import { at, server, setCsrfCookie } from "./testing/server.js";

const bytes = (...values: number[]) => Uint8Array.from(values).buffer;

const CREATION_OPTIONS = {
  challenge: encode(bytes(1, 2, 3)),
  rp: { id: "app.test", name: "App" },
  user: { id: encode(bytes(9, 9)), name: "alice", displayName: "Alice" },
  pubKeyCredParams: [{ type: "public-key", alg: -7 }],
  excludeCredentials: [{ id: encode(bytes(7)), type: "public-key", transports: ["internal"] }],
};

const REQUEST_OPTIONS = {
  challenge: encode(bytes(4, 5)),
  rpId: "app.test",
  allowCredentials: [{ id: encode(bytes(8)), type: "public-key" }],
};

/** What navigator.credentials.create gives: a new passkey's attestation. */
const created = {
  id: "cred-1",
  rawId: bytes(1, 1),
  type: "public-key",
  authenticatorAttachment: "platform",
  getClientExtensionResults: () => ({ credProps: { rk: true } }),
  response: {
    attestationObject: bytes(2, 2),
    clientDataJSON: bytes(3, 3),
    getTransports: () => ["internal", "hybrid"],
  },
};

/** What navigator.credentials.get gives: an assertion. */
const asserted = {
  id: "cred-1",
  rawId: bytes(1, 1),
  type: "public-key",
  authenticatorAttachment: "platform",
  getClientExtensionResults: () => ({}),
  response: {
    authenticatorData: bytes(4, 4),
    clientDataJSON: bytes(5, 5),
    signature: bytes(6, 6),
    userHandle: bytes(9, 9),
  },
};

const create = vi.fn<(options: CredentialCreationOptions) => Promise<unknown>>();
const get = vi.fn<(options: CredentialRequestOptions) => Promise<unknown>>();

beforeEach(() => {
  setCsrfCookie("t");
  create.mockReset();
  get.mockReset();
  Object.defineProperty(navigator, "credentials", { value: { create, get }, configurable: true });
});

afterEach(() => {
  Reflect.deleteProperty(window, "PublicKeyCredential");
});

function refused(status: number, code?: string): HttpResponse<null> {
  return new HttpResponse(null, { status, headers: code ? { "Who-Error": code } : {} });
}

describe("passkeys.supported", () => {
  it("is false without WebAuthn", () => {
    expect(createWhoClient().passkeys.supported()).toBe(false);
  });

  it("is true with WebAuthn", () => {
    Object.defineProperty(window, "PublicKeyCredential", {
      value: function PublicKeyCredential() {},
      configurable: true,
    });

    expect(createWhoClient().passkeys.supported()).toBe(true);
  });
});

describe("passkeys.register", () => {
  it("turns who's options into bytes, and the new passkey back into who's JSON", async () => {
    let body: unknown;
    server.use(
      http.post(at("/who/passkey/register/options"), () => HttpResponse.json(CREATION_OPTIONS)),
      http.post(at("/who/passkey/register"), async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ success: true });
      }),
    );
    create.mockResolvedValue(created);

    await expect(createWhoClient().passkeys.register("Laptop")).resolves.toEqual({ ok: true, value: undefined });

    const publicKey = create.mock.calls[0]?.[0].publicKey;
    expect(Array.from(new Uint8Array(publicKey?.challenge as ArrayBuffer))).toEqual([1, 2, 3]);
    expect(Array.from(new Uint8Array(publicKey?.user.id as ArrayBuffer))).toEqual([9, 9]);
    expect(Array.from(new Uint8Array(publicKey?.excludeCredentials?.[0]?.id as ArrayBuffer))).toEqual([7]);
    expect(publicKey?.rp).toEqual({ id: "app.test", name: "App" });
    expect(body).toEqual({
      publicKey: {
        credential: {
          id: "cred-1",
          rawId: encode(bytes(1, 1)),
          type: "public-key",
          response: {
            attestationObject: encode(bytes(2, 2)),
            clientDataJSON: encode(bytes(3, 3)),
            transports: ["internal", "hybrid"],
          },
          clientExtensionResults: { credProps: { rk: true } },
          authenticatorAttachment: "platform",
        },
        label: "Laptop",
      },
    });
  });

  it("asks nothing of the browser when who needs a recent sign-in", async () => {
    server.use(http.post(at("/who/passkey/register/options"), () => refused(401, "reauthentication-required")));

    await expect(createWhoClient().passkeys.register("Laptop")).resolves.toMatchObject({
      ok: false,
      reason: "reauthentication-required",
    });
    expect(create).not.toHaveBeenCalled();
  });

  it("answers rejected when who refuses the passkey", async () => {
    server.use(
      http.post(at("/who/passkey/register/options"), () => HttpResponse.json(CREATION_OPTIONS)),
      http.post(at("/who/passkey/register"), () => refused(400, "invalid-passkey-registration")),
    );
    create.mockResolvedValue(created);

    await expect(createWhoClient().passkeys.register("Laptop")).resolves.toMatchObject({
      ok: false,
      reason: "rejected",
    });
  });

  it("answers cancelled when the person dismisses the prompt", async () => {
    server.use(http.post(at("/who/passkey/register/options"), () => HttpResponse.json(CREATION_OPTIONS)));
    create.mockRejectedValue(new DOMException("dismissed", "NotAllowedError"));

    await expect(createWhoClient().passkeys.register("Laptop")).resolves.toEqual({
      ok: false,
      reason: "cancelled",
      status: 0,
      detail: undefined,
    });
  });

  it("answers already-registered when this device holds a passkey for the account", async () => {
    server.use(http.post(at("/who/passkey/register/options"), () => HttpResponse.json(CREATION_OPTIONS)));
    create.mockRejectedValue(new DOMException("excluded", "InvalidStateError"));

    await expect(createWhoClient().passkeys.register("Laptop")).resolves.toEqual({
      ok: false,
      reason: "already-registered",
      status: 0,
      detail: undefined,
    });
  });

  it("lets any other browser failure through", async () => {
    server.use(http.post(at("/who/passkey/register/options"), () => HttpResponse.json(CREATION_OPTIONS)));
    const failure = new DOMException("wrong origin", "SecurityError");
    create.mockRejectedValue(failure);

    await expect(createWhoClient().passkeys.register("Laptop")).rejects.toBe(failure);
  });
});

describe("passkeys.signIn", () => {
  it("fetches the CSRF cookie first, then signs in with the assertion", async () => {
    document.cookie = "XSRF-TOKEN=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT";
    let body: unknown;
    server.use(
      http.get(at("/who/me"), () => {
        setCsrfCookie("fresh");
        return refused(401);
      }),
      http.post(at("/who/passkey/sign-in/options"), () => HttpResponse.json(REQUEST_OPTIONS)),
      http.post(at("/who/passkey/sign-in"), async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ identityId: "i1" });
      }),
    );
    get.mockResolvedValue(asserted);

    await expect(createWhoClient().passkeys.signIn()).resolves.toEqual({ ok: true, value: { identityId: "i1" } });

    const publicKey = get.mock.calls[0]?.[0].publicKey;
    expect(Array.from(new Uint8Array(publicKey?.challenge as ArrayBuffer))).toEqual([4, 5]);
    expect(Array.from(new Uint8Array(publicKey?.allowCredentials?.[0]?.id as ArrayBuffer))).toEqual([8]);
    expect(body).toEqual({
      id: "cred-1",
      rawId: encode(bytes(1, 1)),
      type: "public-key",
      response: {
        authenticatorData: encode(bytes(4, 4)),
        clientDataJSON: encode(bytes(5, 5)),
        signature: encode(bytes(6, 6)),
        userHandle: encode(bytes(9, 9)),
      },
      clientExtensionResults: {},
      authenticatorAttachment: "platform",
    });
  });

  it("answers rejected on a 401", async () => {
    server.use(
      http.post(at("/who/passkey/sign-in/options"), () => HttpResponse.json(REQUEST_OPTIONS)),
      http.post(at("/who/passkey/sign-in"), () => refused(401, "sign-in-failed")),
    );
    get.mockResolvedValue(asserted);

    await expect(createWhoClient().passkeys.signIn()).resolves.toMatchObject({ ok: false, reason: "rejected" });
  });

  it("answers cancelled when the person dismisses the prompt", async () => {
    server.use(http.post(at("/who/passkey/sign-in/options"), () => HttpResponse.json(REQUEST_OPTIONS)));
    get.mockRejectedValue(new DOMException("dismissed", "NotAllowedError"));

    await expect(createWhoClient().passkeys.signIn()).resolves.toMatchObject({ ok: false, reason: "cancelled" });
  });

  it("lets any other browser failure through", async () => {
    server.use(http.post(at("/who/passkey/sign-in/options"), () => HttpResponse.json(REQUEST_OPTIONS)));
    const failure = new DOMException("wrong origin", "SecurityError");
    get.mockRejectedValue(failure);

    await expect(createWhoClient().passkeys.signIn()).rejects.toBe(failure);
  });
});

describe("passkeys.remove", () => {
  it("removes by the escaped credential id", async () => {
    server.use(http.delete(at("/who/passkey/register/a%2Fb"), () => new HttpResponse(null, { status: 204 })));

    await expect(createWhoClient().passkeys.remove("a/b")).resolves.toEqual({ ok: true, value: undefined });
  });

  it("answers refused for someone else's passkey", async () => {
    server.use(http.delete(at("/who/passkey/register/x"), () => refused(403)));

    await expect(createWhoClient().passkeys.remove("x")).resolves.toMatchObject({ ok: false, reason: "refused" });
  });

  it("answers signed-out on a 401", async () => {
    server.use(http.delete(at("/who/passkey/register/x"), () => refused(401)));

    await expect(createWhoClient().passkeys.remove("x")).resolves.toMatchObject({ ok: false, reason: "signed-out" });
  });
});
