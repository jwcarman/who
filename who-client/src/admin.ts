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
import { browserSessionRequired, json, nothing, signedOut, signInRequired, type WhoHttp } from "./http.js";
import type { BrowserSessionRequired, SignInRequired, WhoResult } from "./result.js";

/** Whether an identity may sign in. */
export type IdentityStatus = "ACTIVE" | "SUSPENDED" | "DISABLED";

/** An identity as who's admin endpoints show it. */
export interface AdminIdentity {
  id: string;
  status: IdentityStatus;
  createdAt: string;
  /** The names of its roles, sorted. */
  roles: string[];
  /** Its email addresses, sorted; empty without email sign-in. */
  emailAddresses: string[];
  /** How many sign-in credentials it holds, by kind; empty when who cannot count them. */
  credentials: CredentialCounts;
}

/** How many sign-in credentials an identity holds, by kind. */
export interface CredentialCounts {
  email?: number;
  passkey?: number;
  account?: number;
}

/** Which identities to list; every filter given must match. */
export interface IdentityQuery {
  status?: IdentityStatus;
  /** A role's name; an unknown role matches no one. */
  role?: string;
  /** An email address, matched as email sign-in matches it. */
  email?: string;
  /** The zero-based page (default 0). */
  page?: number;
  /** Identities per page, 1 to 100 (default 20). */
  size?: number;
}

/** One page of identities, oldest first. */
export interface IdentityPage {
  items: AdminIdentity[];
  page: number;
  size: number;
  /** How many identities match, on every page. */
  total: number;
}

/** A role and its permissions, sorted. */
export interface AdminRole {
  name: string;
  permissions: string[];
}

/** An invitation who made. */
export interface Invitation {
  identityId: string;
  /** Whether the sign-in link was sent; the invitation stands either way. */
  linkSent: boolean;
}

/** The caller lacks the admin permission the call needs (`403`). */
export type PermissionRequired = "permission-required";

/** The caller may not grant a role carrying a permission it does not hold itself (`403`). */
export type RoleGrantForbidden = "role-grant-forbidden";

/** The identity holds a permission the caller does not, so the caller may not change it (`403`). */
export type IdentityOutranksCaller = "identity-outranks-caller";

/** The change would leave no active identity that may manage identities (`409`). */
export type LastAdmin = "last-admin";

/** A query or body who refuses (`400`). */
export type InvalidAdminRequest = "invalid-admin-request";

/** The reasons reading as an admin can fail. */
export type AdminReadFailure = "signed-out" | PermissionRequired;

/**
 * The reasons changing something as an admin can fail; a browser session needs a recent sign-in,
 * and a session cookie sent with an API key or a bearer token beside it is
 * `"browser-session-required"`.
 */
export type AdminChangeFailure = SignInRequired | BrowserSessionRequired | PermissionRequired;

/** Identities: listing, reading, (de)activating them and assigning roles (`client.admin.identities`). */
export interface AdminIdentities<D> {
  /** A page of identities. Needs `who.identities.read`. */
  list(query?: IdentityQuery): Promise<WhoResult<IdentityPage, AdminReadFailure | InvalidAdminRequest, D>>;
  /** One identity. Needs `who.identities.read`. */
  get(id: string): Promise<WhoResult<AdminIdentity, AdminReadFailure | "identity-not-found", D>>;
  /**
   * Reactivates (`ACTIVE`) or deactivates (`DISABLED`) an identity, answering it. Needs
   * `who.identities.manage` and every permission the identity holds (`"identity-outranks-caller"`);
   * you cannot deactivate yourself (`"self-deactivation"`), nor the last identity that may manage
   * identities (`"last-admin"`).
   */
  setStatus(
    id: string,
    status: "ACTIVE" | "DISABLED",
  ): Promise<
    WhoResult<
      AdminIdentity,
      | AdminChangeFailure
      | IdentityOutranksCaller
      | "identity-not-found"
      | InvalidAdminRequest
      | "self-deactivation"
      | LastAdmin,
      D
    >
  >;
  /**
   * Assigns a role; done already is done. Needs `who.identities.manage`, every permission the
   * identity holds, and every permission the role carries.
   */
  addRole(
    id: string,
    role: string,
  ): Promise<
    WhoResult<
      void,
      AdminChangeFailure | IdentityOutranksCaller | RoleGrantForbidden | "identity-not-found" | "unknown-role",
      D
    >
  >;
  /**
   * Removes a role; not held is done. Needs `who.identities.manage` and every permission the
   * identity holds; the last identity that may manage identities keeps the role that lets it
   * (`"last-admin"`).
   */
  removeRole(
    id: string,
    role: string,
  ): Promise<
    WhoResult<void, AdminChangeFailure | IdentityOutranksCaller | "identity-not-found" | "unknown-role" | LastAdmin, D>
  >;
}

/** The roles that exist (`client.admin.roles`). */
export interface AdminRoles<D> {
  /** Every role, by name. Needs `who.identities.read`. */
  list(): Promise<WhoResult<AdminRole[], AdminReadFailure, D>>;
}

/** Managing identities with who's own permissions (`client.admin`). Needs who-admin. */
export interface Admin<D> {
  identities: AdminIdentities<D>;
  roles: AdminRoles<D>;
  /**
   * Invites an address as a new identity holding `roles`, and emails it a sign-in link. Needs
   * `who.identities.manage` and every permission the roles carry. An unknown role is
   * `"invalid-invitation"`; an address already someone's is `"email-address-taken"`.
   */
  invite(
    email: string,
    roles?: string[],
  ): Promise<
    WhoResult<
      Invitation,
      AdminChangeFailure | RoleGrantForbidden | "invalid-invitation" | InvalidAdminRequest | "email-address-taken",
      D
    >
  >;
}

/** A 403: the role grant refused when who says so, else the missing permission. */
function forbidden(code: string | null): PermissionRequired | RoleGrantForbidden {
  return code === "role-grant-forbidden" ? code : "permission-required";
}

/** A change to an identity refused because it outranks the caller, when who says so. */
function outranked(status: number, code: string | null): IdentityOutranksCaller | undefined {
  return status === 403 && code === "identity-outranks-caller" ? code : undefined;
}

function read(status: number): AdminReadFailure | undefined {
  return signedOut(status) ?? (status === 403 ? "permission-required" : undefined);
}

function change(status: number, code: string | null): AdminChangeFailure | undefined {
  return (
    signInRequired(status, code) ??
    browserSessionRequired(status, code) ??
    (status === 403 ? "permission-required" : undefined)
  );
}

/** A 404 on a role call: the unknown role when who says so, else the identity. */
function roleNotFound(status: number, code: string | null): "identity-not-found" | "unknown-role" | undefined {
  if (status !== 404) {
    return undefined;
  }
  return code === "unknown-role" ? code : "identity-not-found";
}

function invalid(status: number): InvalidAdminRequest | undefined {
  return status === 400 ? "invalid-admin-request" : undefined;
}

function identityPath(id: string): string {
  return `/identities/${encodeURIComponent(id)}`;
}

function rolePath(id: string, role: string): string {
  return `${identityPath(id)}/roles/${encodeURIComponent(role)}`;
}

function queryString(query: IdentityQuery): string {
  const params = new URLSearchParams();
  for (const [name, value] of Object.entries(query)) {
    if (value !== undefined) {
      params.set(name, String(value));
    }
  }
  const search = params.toString();
  return search === "" ? "" : `?${search}`;
}

export function admin<D>(http: WhoHttp<D>): Admin<D> {
  return {
    identities: {
      list: (query = {}) =>
        http.call({
          method: "GET",
          path: `/identities${queryString(query)}`,
          read: json<IdentityPage>(),
          reason: (status) => read(status) ?? invalid(status),
        }),
      get: (id) =>
        http.call({
          method: "GET",
          path: identityPath(id),
          read: json<AdminIdentity>(),
          reason: (status) => read(status) ?? (status === 404 ? "identity-not-found" : undefined),
        }),
      setStatus: (id, status) =>
        http.call({
          method: "PATCH",
          path: identityPath(id),
          body: { status },
          read: json<AdminIdentity>(),
          reason: (
            answered,
            code,
          ):
            | AdminChangeFailure
            | IdentityOutranksCaller
            | "identity-not-found"
            | InvalidAdminRequest
            | "self-deactivation"
            | LastAdmin
            | undefined => {
            if (answered === 404) {
              return "identity-not-found";
            }
            if (answered === 409) {
              // who's only conflicts here; one it does not name is unexpected
              return code === "self-deactivation" || code === "last-admin" ? code : undefined;
            }
            return outranked(answered, code) ?? change(answered, code) ?? invalid(answered);
          },
        }),
      addRole: (id, role) =>
        http.call({
          method: "PUT",
          path: rolePath(id, role),
          read: nothing,
          reason: (status, code) =>
            signInRequired(status, code) ??
            browserSessionRequired(status, code) ??
            outranked(status, code) ??
            (status === 403 ? forbidden(code) : roleNotFound(status, code)),
        }),
      removeRole: (id, role) =>
        http.call({
          method: "DELETE",
          path: rolePath(id, role),
          read: nothing,
          reason: (status, code) =>
            outranked(status, code) ??
            change(status, code) ??
            roleNotFound(status, code) ??
            (status === 409 && code === "last-admin" ? code : undefined),
        }),
    },
    roles: {
      list: () => http.call({ method: "GET", path: "/roles", read: json<AdminRole[]>(), reason: read }),
    },
    invite: (email, roles = []) =>
      http.call({
        method: "POST",
        path: "/invitations",
        body: { email, roles },
        read: json<Invitation>(),
        reason: (status, code) => {
          if (status === 400) {
            return code === "invalid-invitation" ? code : "invalid-admin-request";
          }
          if (status === 409) {
            return "email-address-taken";
          }
          return (
            signInRequired(status, code) ??
            browserSessionRequired(status, code) ??
            (status === 403 ? forbidden(code) : undefined)
          );
        },
      }),
  };
}
