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
import { decode, encode } from "./base64url";

describe("base64url", () => {
  it("round-trips every byte value", () => {
    const bytes = Uint8Array.from({ length: 256 }, (_, i) => i);

    expect(Array.from(decode(encode(bytes.buffer)))).toEqual(Array.from(bytes));
  });

  it("uses - and _ and no padding", () => {
    expect(encode(Uint8Array.from([0xfb, 0xff]).buffer)).toBe("-_8");
  });

  it("decodes - and _ without padding", () => {
    expect(Array.from(decode("-_8"))).toEqual([0xfb, 0xff]);
  });

  it("decodes into bytes backed by an ArrayBuffer", () => {
    expect(decode("AAE").buffer).toBeInstanceOf(ArrayBuffer);
  });
});
