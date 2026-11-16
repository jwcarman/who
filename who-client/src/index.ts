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
export { createWhoClient, type Me, type WhoClient } from "./client";
export type { ConfirmAddressFailure, Email, SignedIn } from "./email";
export type { WhoClientOptions } from "./http";
export type { Oidc, PendingSignUp } from "./oidc";
export type { Passkeys } from "./passkeys";
export { type Cancelled, type SignInRequired, unwrap, WhoError, type WhoResult } from "./result";
export type { Session, Sessions } from "./sessions";
