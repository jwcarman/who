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
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router';
import { api, providers as loadProviders, type Provider } from '../api';
import { useMe } from '../App';
import {
  confirmAddressCode, confirmAddressFromLocation, confirmLink, pendingSignUp, registerPasskey,
  removePasskey, requestAddress, startLink, type PendingSignUp
} from '../who';

const TOO_LONG = 'You signed in too long ago. Sign out, sign in again, then try again.';

// Where who.oidc.link-url and who.email-link.address-confirmation-url land.
export function Profile() {
  const { me, refresh } = useMe();
  const [params, setParams] = useSearchParams();
  const [providers, setProviders] = useState<Provider[]>([]);
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

  useEffect(() => { void loadProviders().then(setProviders); }, []);
  const providerName = (id: string) => providers.find(p => p.id === id)?.name ?? id;

  // Back from the email (#confirm-address=...) or from the provider (?pending=link).
  useEffect(() => {
    if (landed.current) return;
    landed.current = true;
    void confirmAddressFromLocation().then(status => {
      if (status !== null) void addressConfirmed(status);
    });
    if (params.get('pending') === 'link') {
      void pendingSignUp().then(p => (p?.canLink ? setPendingLink(p) : setError('That took too long. Start the link again.')));
    }
  });

  // the email's link opened in a tab already on this page changes only the fragment
  useEffect(() => {
    const onHash = () => void confirmAddressFromLocation().then(status => {
      if (status !== null) void addressConfirmed(status);
    });
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  });

  async function addressConfirmed(status: number) {
    if (status === 200) {
      setNotice('Your email address was updated.');
      setCodeStep(false);
      await refresh();
    } else if (status === 401) setError(TOO_LONG);
    else if (status === 409) setError('That address now belongs to another account.');
    else setError('That link or code did not work. It may have expired: ask for a new one.');
  }

  async function askForAddress(e: FormEvent) {
    e.preventDefault();
    const status = await requestAddress(address, replaces ?? undefined);
    if (status === 202) {
      setNotice('If that address can be added, we sent it a link and a code. Click the link, or enter the code here.');
      setCodeStep(true);
    } else if (status === 401) setError(TOO_LONG);
    else setError('The address could not be requested.');
  }

  async function link() {
    const status = await confirmLink();
    setPendingLink(null);
    setParams({}, { replace: true });
    if (status === 200) await refresh();
    else if (status === 401) setError(TOO_LONG);
    else if (status === 409) setError('That account is already linked to someone else.');
    else setError('That took too long. Start the link again.');
  }

  async function addPasskey(e: FormEvent) {
    e.preventDefault();
    try {
      const status = await registerPasskey(label);
      if (status === 200) {
        setLabel('');
        await refresh();
      } else if (status === 401) setError(TOO_LONG);
      else setError('The passkey could not be added.');
    } catch {
      setError('No passkey was created.');
    }
  }

  async function unlink(id: string) {
    await api('DELETE', `/api/me/accounts/${id}`);
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
        {me.emails.length === 0 && <p className="muted">No email address. You signed up with a provider.</p>}
        <ul className="list">
          {me.emails.map(e => (
            <li key={e.id}>
              <span className="grow">{e.address}</span>
              <button type="button" className="secondary" onClick={() => setReplaces(e.id)}>Change</button>
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
          <form className="inline" onSubmit={async e => { e.preventDefault(); await addressConfirmed(await confirmAddressCode(code)); }}>
            <input type="text" value={code} onChange={e => setCode(e.target.value)}
                   placeholder="Code from the email" autoComplete="one-time-code" required />
            <button type="submit">Confirm</button>
          </form>
        )}
        <p className="muted">We send the address a link and a code. Within 10 minutes of signing in.</p>
      </section>

      <section className="stack">
        <h2>Passkeys</h2>
        {me.passkeys.length === 0 && <p className="muted">No passkeys yet.</p>}
        <ul className="list">
          {me.passkeys.map(p => (
            <li key={p.id}>
              <span className="grow">
                {p.label}<br />
                <span className="muted">Added {new Date(p.created).toLocaleDateString()}</span>
              </span>
              <button type="button" className="secondary" onClick={async () => {
                if (await removePasskey(p.credentialId)) await refresh();
                else setError('The passkey could not be removed.');
              }}>Remove</button>
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
        {me.accounts.length === 0 && <p className="muted">No provider accounts linked.</p>}
        <ul className="list">
          {me.accounts.map(a => (
            <li key={a.id}>
              <span className="grow">{a.issuer}<br /><span className="muted">{a.subject}</span></span>
              <button type="button" className="secondary" onClick={() => unlink(a.id)}>Unlink</button>
            </li>
          ))}
        </ul>
        <div className="inline">
          {providers.map(p => (
            <button key={p.id} type="button" className="secondary" onClick={async () => {
              const status = await startLink(p.id);
              if (status === 401) setError(TOO_LONG);
              else if (status !== 200) setError('The link could not be started.');
            }}>Link {p.name}</button>
          ))}
        </div>
        <p className="muted">The provider asks you to sign in again, or to choose the account. Link within 10 minutes of signing in here.</p>
      </section>
    </>
  );
}
