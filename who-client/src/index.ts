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
export { createWhoClient, type Me, type WhoClient } from "./client.js";
export type {
  ConfirmAddressFailure,
  Email,
  EmailSignInFailure,
  HumanCheckFailed,
  HumanCheckOptions,
  SignedIn,
} from "./email.js";
export type { WhoClientOptions } from "./http.js";
export type { Oidc, PendingSignUp } from "./oidc.js";
export type { Passkeys } from "./passkeys.js";
export {
  type AlreadyRegistered,
  type Cancelled,
  type PromptFailure,
  type SignInRequired,
  unwrap,
  WhoError,
  type WhoResult,
} from "./result.js";
export type { Session, Sessions } from "./sessions.js";
