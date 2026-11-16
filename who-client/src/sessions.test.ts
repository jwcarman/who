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

const SESSION = {
  id: "s1",
  device: "Firefox on macOS",
  userAgent: null,
  signInMethod: "PASSKEY",
  signedInAt: "2026-11-16T10:00:00Z",
  lastSeenAt: "2026-11-16T10:05:00Z",
  current: true,
};

beforeEach(() => setCsrfCookie("t"));

function refused(status: number, code?: string): HttpResponse<null> {
  return new HttpResponse(null, { status, headers: code ? { "Who-Error": code } : {} });
}

describe("sessions.list", () => {
  it("answers the sessions", async () => {
    server.use(http.get(at("/who/sessions"), () => HttpResponse.json([SESSION])));

    await expect(createWhoClient().sessions.list()).resolves.toEqual({ ok: true, value: [SESSION] });
  });

  it("answers signed-out on a 401", async () => {
    server.use(http.get(at("/who/sessions"), () => refused(401, "not-signed-in")));

    await expect(createWhoClient().sessions.list()).resolves.toMatchObject({ ok: false, reason: "signed-out" });
  });
});

describe("sessions.end", () => {
  it.each([
    ["reauthentication-required", "reauthentication-required"],
    ["not-signed-in", "signed-out"],
    [undefined, "sign-in-required"],
  ])("tells a 401 with Who-Error %s apart: %s", async (code, reason) => {
    server.use(http.delete(at("/who/sessions/s1"), () => refused(401, code)));

    await expect(createWhoClient().sessions.end("s1")).resolves.toMatchObject({ ok: false, reason, status: 401 });
  });

  it("answers not-found on a 404", async () => {
    server.use(http.delete(at("/who/sessions/s1"), () => refused(404)));

    await expect(createWhoClient().sessions.end("s1")).resolves.toMatchObject({ ok: false, reason: "not-found" });
  });

  it("escapes the id", async () => {
    server.use(http.delete(at("/who/sessions/a%2Fb"), () => new HttpResponse(null, { status: 204 })));

    await expect(createWhoClient().sessions.end("a/b")).resolves.toEqual({ ok: true, value: undefined });
  });

  it("throws on a 500", async () => {
    server.use(http.delete(at("/who/sessions/s1"), () => refused(500)));

    await expect(createWhoClient().sessions.end("s1")).rejects.toMatchObject({ reason: "unexpected", status: 500 });
  });
});

describe("sessions.endOthers", () => {
  it("ends every other session", async () => {
    server.use(
      http.delete(at("/who/sessions"), ({ request }) =>
        new URL(request.url).searchParams.get("others") === "true" ? HttpResponse.json({ ended: 2 }) : refused(400),
      ),
    );

    await expect(createWhoClient().sessions.endOthers()).resolves.toEqual({ ok: true, value: { ended: 2 } });
  });

  it("needs a recent sign-in", async () => {
    server.use(http.delete(at("/who/sessions"), () => refused(401, "reauthentication-required")));

    await expect(createWhoClient().sessions.endOthers()).resolves.toMatchObject({
      ok: false,
      reason: "reauthentication-required",
    });
  });
});
