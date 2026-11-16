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
import { who } from '/js/client.js';

const $ = id => document.getElementById(id);

function showError(text) {
  $('error').textContent = text;
  $('error').hidden = false;
}

// --- Email link and code ---
$('email-form').addEventListener('submit', async e => {
  e.preventDefault();
  await who.email.send($('email').value);
  $('code-step').hidden = false;
  $('code').focus();
});

$('code-form').addEventListener('submit', async e => {
  e.preventDefault();
  if ((await who.email.signIn({ email: $('email').value, code: $('code').value })).ok) {
    location.assign('/');
  } else {
    showError('That code did not work. Check it, or ask for a new email.');
  }
});

// --- Passkey ---
$('passkey').addEventListener('click', async () => {
  const result = await who.passkeys.signIn();
  if (result.ok) location.assign('/');
  else if (result.reason === 'cancelled') showError('No passkey was used.');
  else showError('That passkey did not sign you in.');
});

// --- Coming back from a provider ---
const error = new URLSearchParams(location.search).get('error');
if (error === 'already-linked') {
  showError('That account is already linked to someone else. Nothing was changed.');
} else if (error === 'signed-in') {
  showError('You are already signed in. To link that account, use Link on your profile; to use it to sign in, sign out first.');
} else if (error === 'signin-failed') {
  showError('Signing in with the provider did not work. Try again, or sign in another way.');
}

// --- A provider account who does not know: offer to create an account with it ---
if (new URLSearchParams(location.search).get('pending') === 'sign-up') {
  const result = await who.oidc.pending();
  const pending = result.ok ? result.value : null;
  if (pending) {
    // who names the provider by its registration id; show the name its button shows.
    pending.provider = document.querySelector(`[data-provider="${CSS.escape(pending.provider)}"]`)
      ?.dataset.providerName ?? pending.provider;
  }
  if (!pending) {
    showError('That took too long. Sign in with the provider again.');
  } else if (pending.canSignUp) {
    offer(`New here?`,
      `No account uses the ${pending.provider} account ${pending.displayName} yet. Create one?`,
      'Create my account', async () => {
        const signedUp = await who.oidc.signUp();
        if (signedUp.ok) location.assign('/');
        else if (signedUp.reason === 'refused') showError(`Sign-up with ${pending.provider} is not open. Ask an admin to invite you, then link it from your profile.`);
        else showError('That took too long. Sign in with the provider again.');
      });
  } else {
    showError(`No account uses the ${pending.provider} account ${pending.displayName}.`);
  }
}

function offer(title, text, action, onConfirm) {
  $('pending-title').textContent = title;
  $('pending-text').textContent = text;
  $('pending-confirm').textContent = action;
  $('pending-confirm').addEventListener('click', onConfirm, { once: true });
  $('pending').hidden = false;
}
