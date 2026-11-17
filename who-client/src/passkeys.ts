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
import { decode, encode } from "./base64url.js";
import type { SignedIn } from "./email.js";
import { json, nothing, removeCredential, signedOut, signInRequired, type WhoHttp } from "./http.js";
import type {
  AlreadyRegistered,
  Cancelled,
  LastCredential,
  PromptFailure,
  SignInRequired,
  WhoResult,
} from "./result.js";

/** Signing in with a passkey. Your own passkeys are under `client.me.passkeys`. */
export interface Passkeys<D> {
  /** Whether this browser can use passkeys at all. */
  supported(): boolean;
  /** Signs in with a passkey this browser can find. */
  signIn(): Promise<WhoResult<SignedIn, "rejected", D> | Cancelled>;
}

/** One of your passkeys, named by its WebAuthn credential id; who's own id is never shown. */
export interface Passkey {
  credentialId: string;
  label: string;
  createdAt: string;
  /** When it last signed in, or null if never. */
  lastUsedAt: string | null;
}

/** Your passkeys: listing, registering and removing them (`client.me.passkeys`). */
export interface MyPasskeys<D> {
  /** Your passkeys, oldest first. */
  list(): Promise<WhoResult<Passkey[], "signed-out", D>>;
  /**
   * Registers a new passkey under `label`. Needs a recent sign-in; who answers that before the
   * browser is asked anything.
   */
  register(label: string): Promise<WhoResult<void, SignInRequired | "rejected", D> | Cancelled | AlreadyRegistered>;
  /**
   * Removes one of your passkeys by its credential id. Needs a recent sign-in; another identity's
   * passkey is `"not-found"`, and your last sign-in credential is `"last-credential"`.
   */
  remove(credentialId: string): Promise<WhoResult<void, SignInRequired | "not-found" | LastCredential, D>>;
}

/** A credential id as WebAuthn's JSON carries it. */
interface Descriptor {
  id: string;
  type: PublicKeyCredentialType;
  transports?: AuthenticatorTransport[];
}

/** The parts of Spring's creation options that hold bytes; the rest passes through. */
interface CreationOptionsJson {
  challenge: string;
  user: { id: string; name: string; displayName: string };
  excludeCredentials?: Descriptor[];
}

/** The parts of Spring's request options that hold bytes; the rest passes through. */
interface RequestOptionsJson {
  challenge: string;
  allowCredentials?: Descriptor[];
}

function promptFailure<R extends string>(reason: R): PromptFailure<R> {
  return { ok: false, reason, status: 0, detail: undefined };
}

// a DOMException the person can act on, by name; any other is a fault and is thrown
const DISMISSED = { NotAllowedError: "cancelled" } as const;
const EXCLUDED = { ...DISMISSED, InvalidStateError: "already-registered" } as const;

function descriptors(list: Descriptor[] | undefined): PublicKeyCredentialDescriptor[] {
  return (list ?? []).map((d) => ({ ...d, id: decode(d.id) }));
}

/**
 * What the browser's prompt gave, or the failure `known` names for its DOMException. Any other
 * failure (a wrong origin, an unsupported algorithm) is a fault, not a choice, and is thrown.
 */
async function prompt<R extends string>(
  ask: () => Promise<Credential | null>,
  known: Readonly<Record<string, R>>,
): Promise<PublicKeyCredential | PromptFailure<R>> {
  try {
    return (await ask()) as PublicKeyCredential;
  } catch (e) {
    const reason = e instanceof DOMException ? known[e.name] : undefined;
    if (reason === undefined) {
      throw e;
    }
    return promptFailure(reason);
  }
}

export function passkeys<D>(http: WhoHttp<D>): Passkeys<D> {
  return {
    supported: () => typeof window !== "undefined" && "PublicKeyCredential" in window,

    signIn: async () => {
      const options = await http.call<RequestOptionsJson, never>({
        method: "POST",
        path: "/passkey/sign-in/options",
        read: json<RequestOptionsJson>(),
        reason: () => undefined,
      });
      if (!options.ok) {
        // no reason can arise: the call throws for every failure
        return options;
      }
      const json_ = options.value;
      const credential = await prompt(
        () =>
          navigator.credentials.get({
            publicKey: {
              ...(json_ as unknown as PublicKeyCredentialRequestOptions),
              challenge: decode(json_.challenge),
              allowCredentials: descriptors(json_.allowCredentials),
            },
          }),
        DISMISSED,
      );
      if ("ok" in credential) {
        return credential;
      }
      const response = credential.response as AuthenticatorAssertionResponse;
      return http.call({
        method: "POST",
        path: "/passkey/sign-in",
        body: {
          id: credential.id,
          rawId: encode(credential.rawId),
          type: credential.type,
          response: {
            authenticatorData: encode(response.authenticatorData),
            clientDataJSON: encode(response.clientDataJSON),
            signature: encode(response.signature),
            userHandle: response.userHandle ? encode(response.userHandle) : null,
          },
          clientExtensionResults: credential.getClientExtensionResults(),
          authenticatorAttachment: credential.authenticatorAttachment,
        },
        read: json<SignedIn>(),
        reason: (status) => (status === 401 ? "rejected" : undefined),
      });
    },
  };
}

export function myPasskeys<D>(http: WhoHttp<D>): MyPasskeys<D> {
  return {
    list: () => http.call({ method: "GET", path: "/me/passkeys", read: json<Passkey[]>(), reason: signedOut }),

    register: async (label) => {
      const options = await http.call({
        method: "POST",
        path: "/me/passkeys/options",
        read: json<CreationOptionsJson>(),
        reason: signInRequired,
      });
      if (!options.ok) {
        return options;
      }
      const json_ = options.value;
      const credential = await prompt(
        () =>
          navigator.credentials.create({
            publicKey: {
              ...(json_ as unknown as PublicKeyCredentialCreationOptions),
              challenge: decode(json_.challenge),
              user: { ...json_.user, id: decode(json_.user.id) },
              excludeCredentials: descriptors(json_.excludeCredentials),
            },
          }),
        EXCLUDED,
      );
      if ("ok" in credential) {
        return credential;
      }
      const response = credential.response as AuthenticatorAttestationResponse;
      return http.call({
        method: "POST",
        path: "/me/passkeys",
        body: {
          publicKey: {
            credential: {
              id: credential.id,
              rawId: encode(credential.rawId),
              type: credential.type,
              response: {
                attestationObject: encode(response.attestationObject),
                clientDataJSON: encode(response.clientDataJSON),
                transports: response.getTransports?.() ?? [],
              },
              clientExtensionResults: credential.getClientExtensionResults(),
              authenticatorAttachment: credential.authenticatorAttachment,
            },
            label,
          },
        },
        read: nothing,
        reason: (status, code): SignInRequired | "rejected" | undefined =>
          signInRequired(status, code) ?? (status === 400 ? "rejected" : undefined),
      });
    },

    remove: (credentialId) =>
      http.call({
        method: "DELETE",
        path: `/me/passkeys/${encodeURIComponent(credentialId)}`,
        read: nothing,
        reason: removeCredential,
      }),
  };
}
