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
import { describe, expect, it } from "vitest";
import { unwrap, WhoError, type WhoResult } from "./index";

describe("unwrap", () => {
  it("gives the value of a success", () => {
    const result: WhoResult<number, "nope", string> = { ok: true, value: 42 };

    expect(unwrap(result)).toBe(42);
  });

  it("throws a WhoError carrying the failure", () => {
    const result: WhoResult<number, "nope", string> = { ok: false, reason: "nope", status: 409, detail: "taken" };

    expect(() => unwrap(result)).toThrow(WhoError);
    try {
      unwrap(result);
    } catch (e) {
      expect(e).toMatchObject({ reason: "nope", status: 409, detail: "taken" });
    }
  });
});
