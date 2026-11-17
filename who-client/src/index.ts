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
export type {
  Admin,
  AdminChangeFailure,
  AdminIdentities,
  AdminIdentity,
  AdminReadFailure,
  AdminRole,
  AdminRoles,
  CredentialCounts,
  IdentityOutranksCaller,
  IdentityPage,
  IdentityQuery,
  IdentityStatus,
  InvalidAdminRequest,
  Invitation,
  LastAdmin,
  PermissionRequired,
  RoleGrantForbidden,
} from "./admin.js";
export type { ApiKey, ApiKeyRequest, ApiKeys, InvalidApiKeyRequest, NewApiKey } from "./apiKeys.js";
export { createWhoClient, type SignInOptions, type SignInProvider, type WhoClient } from "./client.js";
export type {
  ConfirmAddressFailure,
  ConfirmedAddress,
  Email,
  EmailAddress,
  EmailAddresses,
  EmailSignInFailure,
  HumanCheckFailed,
  HumanCheckOptions,
  SignedIn,
} from "./email.js";
export type { WhoClientOptions } from "./http.js";
export type { Me, MyAccount } from "./me.js";
export type { Accounts, LinkedAccount, Oidc, PendingSignUp } from "./oidc.js";
export type { MyPasskeys, Passkey, Passkeys } from "./passkeys.js";
export {
  type AlreadyRegistered,
  type Cancelled,
  type LastCredential,
  type PromptFailure,
  type SignInRequired,
  unwrap,
  WhoError,
  type WhoResult,
} from "./result.js";
export type { Session, Sessions } from "./sessions.js";
