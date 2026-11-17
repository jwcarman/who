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

// a 401 from a call that needs a recent sign-in: signed out altogether, or signed in too long ago
function signInAgain(reason, tooLong) {
  if (reason === 'signed-out') location.assign('/signin');
  else showError(tooLong);
}

$('passkey-form').addEventListener('submit', async e => {
  e.preventDefault();
  const result = await who.me.passkeys.register($('passkey-label').value);
  if (result.ok) location.reload();
  else if (result.reason === 'cancelled') showError('No passkey was created.');
  else if (result.reason === 'already-registered') showError('This device already has a passkey for your account.');
  else if (result.reason === 'rejected') showError('The passkey could not be added.');
  else signInAgain(result.reason, 'You signed in too long ago. Sign out, sign in again, then add the passkey.');
});

// a removal who refused: who keeps an identity's last way in, so it can always sign in
function notRemoved(reason) {
  if (reason === 'last-credential') showError('That is your last way in: add another before removing it.');
  else if (reason === 'not-found') location.reload();
  else signInAgain(reason, 'You signed in too long ago. Sign out, sign in again, then try again.');
}

document.querySelectorAll('[data-remove-passkey]').forEach(button =>
  button.addEventListener('click', async () => {
    const result = await who.me.passkeys.remove(button.dataset.credentialId);
    if (result.ok) location.reload();
    else notRemoved(result.reason);
  }));

document.querySelectorAll('[data-unlink-account]').forEach(button =>
  button.addEventListener('click', async () => {
    const result = await who.me.accounts.unlink(button.dataset.accountId);
    if (result.ok) location.reload();
    else notRemoved(result.reason);
  }));

// --- Email addresses ---

function showNotice(text) {
  $('notice').textContent = text;
  $('notice').hidden = false;
}

function confirmed(result) {
  if (result.ok) location.reload();
  else if (result.reason === 'conflict') showError('That address now belongs to another account.');
  else if (result.reason === 'rejected') showError('That link or code did not work. It may have expired: ask for a new one.');
  else signInAgain(result.reason, 'You signed in too long ago. Sign out, sign in again, then try again.');
}

// a click on the link in the email lands here, with the token in the fragment
async function confirmFromLocation() {
  const result = await who.me.emailAddresses.confirmFromLocation();
  if (result !== null) confirmed(result);
}
confirmFromLocation();
// the link opened in a tab already on this page changes only the fragment
window.addEventListener('hashchange', confirmFromLocation);

document.querySelectorAll('[data-change-address]').forEach(button =>
  button.addEventListener('click', () => {
    $('replaces').value = button.dataset.credentialId;
    $('address-submit').textContent = 'Change to this address';
    $('address').focus();
  }));

$('address-form').addEventListener('submit', async e => {
  e.preventDefault();
  const result = await who.me.emailAddresses.add($('address').value, $('replaces').value || undefined);
  if (result.ok) {
    showNotice('If that address can be added, we sent it a link and a code. Click the link, or enter the code here.');
    $('address-code-form').hidden = false;
  } else if (result.reason === 'not-found') {
    showError('The address could not be requested.');
  } else {
    signInAgain(result.reason, 'You signed in too long ago. Sign out, sign in again, then try again.');
  }
});

$('address-code-form').addEventListener('submit', async e => {
  e.preventDefault();
  confirmed(await who.me.emailAddresses.confirm({ code: $('address-code').value }));
});

// --- Back from a provider (who.oidc.link-url points here) ---

const params = new URLSearchParams(location.search);
if (params.get('error') === 'already-linked') {
  showError('That account is already linked to someone else. Nothing was changed.');
} else if (params.get('pending') === 'link') {
  const result = await who.oidc.pending();
  const pending = result.ok ? result.value : null;
  if (!pending?.canLink) {
    showError('That took too long. Start the link again.');
  } else {
    // who names the provider by its registration id; show the name its Link button shows.
    const name = document.querySelector(`[data-provider="${CSS.escape(pending.provider)}"]`)
      ?.dataset.providerName ?? pending.provider;
    $('pending-link-title').textContent = `Link ${name}?`;
    $('pending-link-text').textContent =
      `Link the ${name} account ${pending.displayName} to your account, so it can sign you in.`;
    $('pending-link').hidden = false;
    $('pending-link-confirm').addEventListener('click', async () => {
      const linked = await who.oidc.link();
      if (linked.ok) location.assign('/profile');
      else if (linked.reason === 'conflict') showError('That account is already linked to someone else.');
      else if (linked.reason === 'none-pending') showError('That took too long. Start the link again.');
      else signInAgain(linked.reason, 'You signed in too long ago. Sign out, sign in again, then link the account.');
    }, { once: true });
  }
}
