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
import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router';
import type {
  BrowserSessionRequired,
  ConfirmAddressFailure,
  ConfirmedAddress,
  EmailAddress,
  LastCredential,
  LinkedAccount,
  Passkey,
  PendingSignUp,
  SignInProvider,
  SignInRequired,
  WhoResult
} from '@jwcarman/who-client';
import { signInAgain, who } from '../who-client';
import { Sessions } from './Sessions';

const TOO_LONG = 'You signed in too long ago. Sign out, sign in again, then try again.';

// Why removing a way in failed: who keeps the last one, so the person can always sign in.
function notRemoved(reason: SignInRequired | BrowserSessionRequired | 'not-found' | LastCredential): string {
  if (reason === 'last-credential') return 'That is your last way in: add another before removing it.';
  if (reason === 'not-found') return 'That is already gone.';
  return signInAgain(reason, TOO_LONG);
}

// Where who.oidc.link-url and who.email-link.address-confirmation-url land.
export function Profile() {
  const [params, setParams] = useSearchParams();
  const [providers, setProviders] = useState<SignInProvider[]>([]);
  const [emails, setEmails] = useState<EmailAddress[]>([]);
  const [passkeys, setPasskeys] = useState<Passkey[]>([]);
  const [accounts, setAccounts] = useState<LinkedAccount[]>([]);
  const [error, setError] = useState<string | null>(
    params.get('error') === 'already-linked'
      ? 'That account is already linked to someone else. Nothing was changed.'
      : null);
  const [notice, setNotice] = useState<string | null>(null);
  const [pendingLink, setPendingLink] = useState<PendingSignUp | null>(null);
  const [address, setAddress] = useState('');
  const [replaces, setReplaces] = useState<string | null>(null);
  const [codeStep, setCodeStep] = useState(false);
  const [code, setCode] = useState('');
  const [label, setLabel] = useState('');
  const landed = useRef(false); // a token works once: StrictMode runs effects twice

  // the ways in, each listed by who itself under /who/me
  const refresh = useCallback(async () => {
    const [e, p, a] = await Promise.all([
      who.me.emailAddresses.list(), who.me.passkeys.list(), who.me.accounts.list()
    ]);
    setEmails(e.ok ? e.value : []);
    setPasskeys(p.ok ? p.value : []);
    setAccounts(a.ok ? a.value : []);
  }, []);

  useEffect(() => { void refresh(); }, [refresh]);
  useEffect(() => {
    void who.signInOptions().then(result => setProviders(result.ok ? result.value.providers : []));
  }, []);
  const providerName = (id: string) => providers.find(p => p.id === id)?.name ?? id;

  // Back from the email (#confirm-address=...) or from the provider (?pending=link).
  useEffect(() => {
    if (landed.current) return;
    landed.current = true;
    void who.me.emailAddresses.confirmFromLocation().then(result => {
      if (result !== null) void addressConfirmed(result);
    });
    if (params.get('pending') === 'link') {
      void who.oidc.pending().then(result => (result.ok && result.value.canLink
        ? setPendingLink(result.value)
        : setError('That took too long. Start the link again.')));
    }
  });

  // the email's link opened in a tab already on this page changes only the fragment
  useEffect(() => {
    const onHash = () => void who.me.emailAddresses.confirmFromLocation().then(result => {
      if (result !== null) void addressConfirmed(result);
    });
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  });

  async function addressConfirmed(result: WhoResult<ConfirmedAddress, ConfirmAddressFailure, unknown>) {
    if (result.ok) {
      setNotice('Your email address was updated.');
      setCodeStep(false);
      await refresh();
    } else if (result.reason === 'conflict') setError('That address now belongs to another account.');
    else if (result.reason === 'rejected') setError('That link or code did not work. It may have expired: ask for a new one.');
    else setError(signInAgain(result.reason, TOO_LONG));
  }

  async function askForAddress(e: FormEvent) {
    e.preventDefault();
    const result = await who.me.emailAddresses.add(address, replaces ?? undefined);
    if (result.ok) {
      setNotice('If that address can be added, we sent it a link and a code. Click the link, or enter the code here.');
      setCodeStep(true);
    } else if (result.reason === 'not-found') setError('The address could not be requested.');
    else setError(signInAgain(result.reason, TOO_LONG));
  }

  async function link() {
    const result = await who.oidc.link();
    setPendingLink(null);
    setParams({}, { replace: true });
    if (result.ok) await refresh();
    else if (result.reason === 'conflict') setError('That account is already linked to someone else.');
    else if (result.reason === 'none-pending') setError('That took too long. Start the link again.');
    else setError(signInAgain(result.reason, TOO_LONG));
  }

  async function addPasskey(e: FormEvent) {
    e.preventDefault();
    const result = await who.me.passkeys.register(label);
    if (result.ok) {
      setLabel('');
      await refresh();
    } else if (result.reason === 'cancelled') setError('No passkey was created.');
    else if (result.reason === 'already-registered') setError('This device already has a passkey for your account.');
    else if (result.reason === 'rejected') setError('The passkey could not be added.');
    else setError(signInAgain(result.reason, TOO_LONG));
  }

  async function unlink(id: string) {
    const result = await who.me.accounts.unlink(id);
    if (!result.ok) setError(notRemoved(result.reason));
    await refresh();
  }

  async function removeAddress(id: string) {
    const result = await who.me.emailAddresses.remove(id);
    if (!result.ok) setError(notRemoved(result.reason));
    await refresh();
  }

  async function removePasskey(credentialId: string) {
    const result = await who.me.passkeys.remove(credentialId);
    if (!result.ok) setError(notRemoved(result.reason));
    await refresh();
  }

  return (
    <>
      <h1>Your ways in</h1>
      {error && <div className="message warn">{error}</div>}
      {notice && <div className="message">{notice}</div>}

      {pendingLink && (
        <section>
          <h2>Link {providerName(pendingLink.provider)}?</h2>
          <p className="muted">
            Link the {providerName(pendingLink.provider)} account {pendingLink.displayName} to your
            account, so it can sign you in.
          </p>
          {/* never link without asking: the click is what stops someone else's account being attached */}
          <div className="inline">
            <button type="button" onClick={link}>Link it</button>
            <button type="button" className="secondary" onClick={() => { setPendingLink(null); setParams({}, { replace: true }); }}>Cancel</button>
          </div>
        </section>
      )}

      <section className="stack">
        <h2>Email</h2>
        {emails.length === 0 && <p className="muted">No email address. You signed up with a provider.</p>}
        <ul className="list">
          {emails.map(e => (
            <li key={e.id}>
              <span className="grow">{e.address}</span>
              <button type="button" className="secondary" onClick={() => setReplaces(e.id)}>Change</button>
              <button type="button" className="secondary" onClick={() => removeAddress(e.id)}>Remove</button>
            </li>
          ))}
        </ul>
        {/* nothing changes until the new address is confirmed with the link or the code sent to it */}
        <form className="inline" onSubmit={askForAddress}>
          <input type="email" value={address} onChange={e => setAddress(e.target.value)}
                 placeholder="new@example.com" maxLength={320} required />
          <button type="submit">{replaces ? 'Change to this address' : 'Add an address'}</button>
        </form>
        {codeStep && (
          <form className="inline" onSubmit={async e => { e.preventDefault(); await addressConfirmed(await who.me.emailAddresses.confirm({ code })); }}>
            <input type="text" value={code} onChange={e => setCode(e.target.value)}
                   placeholder="Code from the email" autoComplete="one-time-code" required />
            <button type="submit">Confirm</button>
          </form>
        )}
        <p className="muted">We send the address a link and a code. Within 10 minutes of signing in.</p>
      </section>

      <section className="stack">
        <h2>Passkeys</h2>
        {passkeys.length === 0 && <p className="muted">No passkeys yet.</p>}
        <ul className="list">
          {passkeys.map(p => (
            <li key={p.credentialId}>
              <span className="grow">
                {p.label}<br />
                <span className="muted">Added {new Date(p.createdAt).toLocaleDateString()}</span>
              </span>
              <button type="button" className="secondary" onClick={() => removePasskey(p.credentialId)}>Remove</button>
            </li>
          ))}
        </ul>
        <form className="inline" onSubmit={addPasskey}>
          <input type="text" value={label} onChange={e => setLabel(e.target.value)}
                 placeholder="Name it, such as Laptop" maxLength={100} required />
          <button type="submit">Add a passkey</button>
        </form>
        <p className="muted">You can add one within 10 minutes of signing in.</p>
      </section>

      <section className="stack">
        <h2>Linked accounts</h2>
        {accounts.length === 0 && <p className="muted">No provider accounts linked.</p>}
        <ul className="list">
          {accounts.map(a => (
            <li key={a.id}>
              <span className="grow">
                {providerName(a.provider)}<br />
                <span className="muted">Linked {new Date(a.linkedAt).toLocaleDateString()}</span>
              </span>
              <button type="button" className="secondary" onClick={() => unlink(a.id)}>Unlink</button>
            </li>
          ))}
        </ul>
        <div className="inline">
          {providers.map(p => (
            <button key={p.id} type="button" className="secondary" onClick={async () => {
              const result = await who.me.accounts.link(p.id);
              // on success the page has gone to the provider
              if (result.ok) return;
              if (result.reason === 'not-found') setError('The link could not be started.');
              else setError(signInAgain(result.reason, TOO_LONG));
            }}>Link {p.name}</button>
          ))}
        </div>
        <p className="muted">The provider asks you to sign in again, or to choose the account. Link within 10 minutes of signing in here.</p>
      </section>

      <Sessions />
    </>
  );
}
