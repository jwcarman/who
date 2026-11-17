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
import { useNavigate, useSearchParams } from 'react-router';
import type { PendingSignUp, SignInProvider } from '@jwcarman/who-client';
import { who } from '../who-client';

// Where who.oidc.failure-url lands: ?error= for what failed, ?pending=sign-up for an account who
// does not know yet.
const ERRORS: Record<string, string> = {
  'already-linked': 'That account is already linked to someone else. Nothing was changed.',
  'signed-in': 'You are already signed in. To link that account, use Link on your profile; to use it to sign in, sign out first.',
  'signin-failed': 'Signing in with the provider did not work. Try again, or sign in another way.'
};

export function SignIn() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [providers, setProviders] = useState<SignInProvider[]>([]);
  const [email, setEmail] = useState('');
  const [codeStep, setCodeStep] = useState(false);
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(ERRORS[params.get('error') ?? ''] ?? null);
  const [pending, setPending] = useState<PendingSignUp | null>(null);
  const asked = useRef(false);

  useEffect(() => {
    void who.signInOptions().then(result => setProviders(result.ok ? result.value.providers : []));
  }, []);

  useEffect(() => {
    if (params.get('pending') !== 'sign-up' || asked.current) return;
    asked.current = true;
    void who.oidc.pending().then(result => {
      if (!result.ok) setError('That took too long. Sign in with the provider again.');
      else if (result.value.canSignUp) setPending(result.value);
      else setError(`No account uses the ${result.value.provider} account ${result.value.displayName}.`);
    });
  }, [params]);

  const providerName = (id: string) => providers.find(p => p.id === id)?.name ?? id;

  async function sendLink(e: FormEvent) {
    e.preventDefault();
    await who.email.send(email);
    setCodeStep(true);
  }

  async function submitCode(e: FormEvent) {
    e.preventDefault();
    if ((await who.email.signIn({ email, code })).ok) navigate('/');
    else setError('That code did not work. Check it, or ask for a new email.');
  }

  async function passkey() {
    const result = await who.passkeys.signIn();
    if (result.ok) navigate('/');
    else if (result.reason === 'cancelled') setError('No passkey was used.');
    else setError('That passkey did not sign you in.');
  }

  async function signUp() {
    const result = await who.oidc.signUp();
    if (result.ok) navigate('/');
    else if (result.reason === 'refused') setError(`Sign-up with ${providerName(pending!.provider)} is not open. Ask an admin to invite you, then link it from your profile.`);
    else setError('That took too long. Sign in with the provider again.');
    setPending(null);
  }

  return (
    <main>
      <h1>Sign in to Who Tasks</h1>
      {error && <div className="message warn">{error}</div>}

      {pending && (
        <section>
          <h2>New here?</h2>
          <p className="muted">
            No account uses the {providerName(pending.provider)} account {pending.displayName} yet.
            Create one?
          </p>
          <div className="inline">
            <button type="button" onClick={signUp}>Create my account</button>
            <button type="button" className="secondary" onClick={() => setPending(null)}>Cancel</button>
          </div>
        </section>
      )}

      <section className="stack">
        <h2>Email</h2>
        <form className="inline" onSubmit={sendLink}>
          <input type="email" value={email} onChange={e => setEmail(e.target.value)}
                 placeholder="name@example.com" required autoComplete="email" />
          <button type="submit">Email me a link</button>
        </form>
        {codeStep && (
          <div className="stack">
            <p className="muted">
              Check your inbox (on this machine, <a href="http://localhost:8025" target="_blank">Mailpit</a>)
              and open the link, or type the code from the email.
            </p>
            <form className="inline" onSubmit={submitCode}>
              <input type="text" value={code} onChange={e => setCode(e.target.value)}
                     placeholder="K7F-2QM" required autoComplete="one-time-code" />
              <button type="submit">Sign in</button>
            </form>
          </div>
        )}
      </section>

      <section className="stack">
        <h2>Passkey</h2>
        <button type="button" onClick={passkey}>Sign in with a passkey</button>
      </section>

      {providers.length > 0 && (
        <section className="stack">
          <h2>Or with</h2>
          <div className="inline">
            {/* a full page load to the provider, not fetch: its sign-in page has to open */}
            {providers.map(p => (
              <a key={p.id} className="button secondary" href={who.oidc.authorizeUrl(p.id)}>{p.name}</a>
            ))}
          </div>
        </section>
      )}
    </main>
  );
}
