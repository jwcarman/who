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
// The calls the who guides show, gathered in one place, with no React in it: the components call
// these. The same calls as the web-app example's who.js, typed. Each section names its guide.

// --- CSRF (Email Links guide, "The sign-in flow") ---------------------------------------------
// who protects the chain with Spring's csrf.spa(): read the XSRF-TOKEN cookie and send it back in
// the X-XSRF-TOKEN header on every POST. Read it at each call, never cache it: it changes when
// someone signs in or out.

function xsrf(): string | undefined {
  return document.cookie.split('; ')
    .find(c => c.startsWith('XSRF-TOKEN='))?.split('=')[1];
}

export function send(method: string, path: string, body?: unknown): Promise<Response> {
  return fetch(path, {
    method,
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      'X-XSRF-TOKEN': decodeURIComponent(xsrf() ?? '')
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
}

export function post(path: string, body?: unknown): Promise<Response> {
  return send('POST', path, body);
}

// The cookie exists only after a response from the server, so make a GET before the first POST.
// GET /who/me answers 401 when signed out, but sets the cookie either way.
export async function ensureCsrfCookie(): Promise<void> {
  if (!xsrf()) {
    await fetch('/who/me', { credentials: 'same-origin' });
  }
}

// --- Email links (Email Links guide, "The sign-in flow") --------------------------------------

// Always 202, whether or not the address is known, so the answer tells an attacker nothing.
export async function requestLink(email: string): Promise<void> {
  await ensureCsrfCookie();
  await post('/ott/generate', { email });
}

// The token is in the URL fragment, never sent to a server. Take it out of the address bar.
export async function redeemLinkFromLocation(): Promise<boolean> {
  const token = new URLSearchParams(location.hash.slice(1)).get('token');
  history.replaceState(null, '', location.pathname);
  if (!token) return false;
  await ensureCsrfCookie();
  return (await post('/login/ott', { token })).ok;
}

export async function signInWithCode(email: string, code: string): Promise<boolean> {
  await ensureCsrfCookie();
  return (await post('/login/ott', { email, code })).ok;
}

// --- Email addresses (Email Links guide, "Adding or changing an address") ---------------------

// Always 202 when signed in recently, whether or not the address is free, so the answer tells
// nothing about it. replaces, if given, is the id of one of your addresses. 401 signed out or
// signed in too long ago, 404 a replaces that is not yours.
export async function requestAddress(address: string, replaces?: string): Promise<number> {
  return (await post('/who/email/addresses', replaces ? { address, replaces } : { address })).status;
}

// The link puts the token in the fragment, as #confirm-address=..., on a signed-in page. Take it
// out of the address bar. Answers the status, or null when there is no token.
export async function confirmAddressFromLocation(): Promise<number | null> {
  const token = new URLSearchParams(location.hash.slice(1)).get('confirm-address');
  if (!token) return null;
  history.replaceState(null, '', location.pathname + location.search);
  return (await post('/who/email/addresses/confirm', { token })).status;
}

// 200 added, 400 no match, 409 taken since, 401 signed out or signed in too long ago.
export async function confirmAddressCode(code: string): Promise<number> {
  return (await post('/who/email/addresses/confirm', { code })).status;
}

// --- Passkeys (Passkeys guide, "The browser flow") --------------------------------------------

// Spring's options are JSON with base64url strings. The browser wants bytes.
const b64u = {
  decode: (s: string): Uint8Array<ArrayBuffer> =>
    Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/')), c => c.charCodeAt(0)),
  encode: (b: ArrayBuffer): string => btoa(String.fromCharCode(...new Uint8Array(b)))
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
};

type Descriptor = { id: string; type: PublicKeyCredentialType; transports?: AuthenticatorTransport[] };

function descriptors(list?: Descriptor[]): PublicKeyCredentialDescriptor[] {
  return (list ?? []).map(c => ({ ...c, id: b64u.decode(c.id) }));
}

// Needs a recent sign-in (who.security.recent-sign-in). Answers the status: 200 registered,
// 401 signed out or signed in too long ago, 400 failed verification.
export async function registerPasskey(label: string): Promise<number> {
  const optionsRes = await post('/webauthn/register/options');
  if (!optionsRes.ok) return optionsRes.status;
  const options = await optionsRes.json();
  const credential = await navigator.credentials.create({
    publicKey: {
      ...options,
      challenge: b64u.decode(options.challenge),
      user: { ...options.user, id: b64u.decode(options.user.id) },
      excludeCredentials: descriptors(options.excludeCredentials)
    }
  }) as PublicKeyCredential;
  const response = credential.response as AuthenticatorAttestationResponse;
  const res = await post('/webauthn/register', {
    publicKey: {
      credential: {
        id: credential.id,
        rawId: b64u.encode(credential.rawId),
        type: credential.type,
        response: {
          attestationObject: b64u.encode(response.attestationObject),
          clientDataJSON: b64u.encode(response.clientDataJSON),
          transports: response.getTransports?.() ?? []
        },
        clientExtensionResults: credential.getClientExtensionResults(),
        authenticatorAttachment: credential.authenticatorAttachment
      },
      label
    }
  });
  return res.status;
}

export async function signInWithPasskey(): Promise<boolean> {
  await ensureCsrfCookie();
  const options = await (await post('/webauthn/authenticate/options')).json();
  const credential = await navigator.credentials.get({
    publicKey: {
      ...options,
      challenge: b64u.decode(options.challenge),
      allowCredentials: descriptors(options.allowCredentials)
    }
  }) as PublicKeyCredential;
  const response = credential.response as AuthenticatorAssertionResponse;
  const res = await post('/login/webauthn', {
    id: credential.id,
    rawId: b64u.encode(credential.rawId),
    type: credential.type,
    response: {
      authenticatorData: b64u.encode(response.authenticatorData),
      clientDataJSON: b64u.encode(response.clientDataJSON),
      signature: b64u.encode(response.signature),
      userHandle: response.userHandle ? b64u.encode(response.userHandle) : null
    },
    clientExtensionResults: credential.getClientExtensionResults(),
    authenticatorAttachment: credential.authenticatorAttachment
  });
  return res.ok;
}

// The {id} is the passkey's WebAuthn credential id (PasskeySummary.credentialId), not who's UUID.
// Spring checks that it belongs to the caller. 204 removed, 403 not yours.
export async function removePasskey(credentialId: string): Promise<boolean> {
  return (await send('DELETE', '/webauthn/register/' + encodeURIComponent(credentialId))).ok;
}

// --- Provider sign-in (Social Login guide, "Sign up" and "Link an account") -------------------

// A provider's button is a plain link to /oauth2/authorization/{id}: no CSRF, a full page load.
// Both a sign-in and a link come back to who.oidc.failure-url?pending=sign-up or
// link-url?pending=link.

export type PendingSignUp = {
  provider: string;
  displayName: string;
  canSignUp: boolean;
  canLink: boolean;
};

// After ?pending=sign-up or ?pending=link, who keeps the provider account in the session for a
// few minutes, or null when there is none.
export async function pendingSignUp(): Promise<PendingSignUp | null> {
  const res = await fetch('/who/oidc/pending', { credentials: 'same-origin' });
  return res.ok ? res.json() : null;
}

// Asks the sign-up policy. 200 signed in, 403 refused, 404 no pending sign-up.
export async function confirmSignUp(): Promise<number> {
  return (await post('/who/oidc/sign-up')).status;
}

// Links the pending account to the signed-in identity. Never call it without asking: the
// confirmation is what stops someone planting their own account on yours.
// 200 linked, 401 signed in too long ago, 409 linked to someone else, 404 no pending account.
export async function confirmLink(): Promise<number> {
  return (await post('/who/oidc/link')).status;
}

// Starts a link. Asked for JSON, who answers where the provider is instead of redirecting there,
// since fetch cannot follow a redirect to another site; the page then goes there itself.
// 401 signed out or signed in too long ago, 404 an unknown provider.
export async function startLink(registrationId: string): Promise<number> {
  const res = await fetch('/who/oidc/link/' + encodeURIComponent(registrationId), {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Accept': 'application/json', 'X-XSRF-TOKEN': decodeURIComponent(xsrf() ?? '') }
  });
  if (res.ok) {
    location.assign((await res.json()).redirectUrl);
  }
  return res.status;
}

// --- Sign out (Email Links guide, "The sign-in flow") -----------------------------------------

export async function signOut(): Promise<void> {
  await post('/logout');
  await fetch('/who/me', { credentials: 'same-origin' }); // sets a new XSRF-TOKEN cookie
}

// --- Sessions (Sessions guide, "See and end sessions") ----------------------------------------

// One browser this person is signed in on. The id is who's own, never the session cookie, so the
// list can be shown without exposing a session.
export type Session = {
  id: string;
  device: string;
  userAgent: string | null;
  signInMethod: string;
  signedInAt: string;
  lastSeenAt: string;
  current: boolean;
};

// Where this person is signed in, newest first.
export async function listSessions(): Promise<Session[]> {
  const res = await fetch('/who/sessions', { credentials: 'same-origin' });
  return res.ok ? res.json() : [];
}

// Signs one browser out. This browser's own session ends at once; any other needs a recent
// sign-in. 204 ended, 401 signed in too long ago, 404 not one of yours.
export async function endSession(id: string): Promise<number> {
  return (await send('DELETE', '/who/sessions/' + encodeURIComponent(id))).status;
}

// Signs out every browser but this one. 200 { ended }, 401 signed in too long ago.
export async function endOtherSessions(): Promise<number> {
  return (await send('DELETE', '/who/sessions?others=true')).status;
}
