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
import {
  confirmAddressCode, confirmAddressFromLocation, confirmLink, pendingSignUp, registerPasskey,
  removePasskey, requestAddress
} from '/js/who.js';

const $ = id => document.getElementById(id);

function showError(text) {
  $('error').textContent = text;
  $('error').hidden = false;
}

$('passkey-form').addEventListener('submit', async e => {
  e.preventDefault();
  let status;
  try {
    status = await registerPasskey($('passkey-label').value);
  } catch {
    showError('No passkey was created.');
    return;
  }
  if (status === 200) location.reload();
  else if (status === 401) showError('You signed in too long ago. Sign out, sign in again, then add the passkey.');
  else showError('The passkey could not be added.');
});

document.querySelectorAll('[data-remove-passkey]').forEach(button =>
  button.addEventListener('click', async () => {
    if (await removePasskey(button.dataset.credentialId)) location.reload();
    else showError('The passkey could not be removed.');
  }));

// --- Email addresses ---

function showNotice(text) {
  $('notice').textContent = text;
  $('notice').hidden = false;
}

function confirmed(status) {
  if (status === 200) location.reload();
  else if (status === 401) showError('You signed in too long ago. Sign out, sign in again, then try again.');
  else if (status === 409) showError('That address now belongs to another account.');
  else showError('That link or code did not work. It may have expired: ask for a new one.');
}

// a click on the link in the email lands here, with the token in the fragment
async function confirmFromLocation() {
  const status = await confirmAddressFromLocation();
  if (status !== null) confirmed(status);
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
  const status = await requestAddress($('address').value, $('replaces').value || undefined);
  if (status === 202) {
    showNotice('If that address can be added, we sent it a link and a code. Click the link, or enter the code here.');
    $('address-code-form').hidden = false;
  } else if (status === 401) {
    showError('You signed in too long ago. Sign out, sign in again, then try again.');
  } else {
    showError('The address could not be requested.');
  }
});

$('address-code-form').addEventListener('submit', async e => {
  e.preventDefault();
  confirmed(await confirmAddressCode($('address-code').value));
});

// --- Back from a provider (who.oidc.link-url points here) ---

const params = new URLSearchParams(location.search);
if (params.get('error') === 'already-linked') {
  showError('That account is already linked to someone else. Nothing was changed.');
} else if (params.get('pending') === 'link') {
  const pending = await pendingSignUp();
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
      const status = await confirmLink();
      if (status === 200) location.assign('/profile');
      else if (status === 401) showError('You signed in too long ago. Sign out, sign in again, then link the account.');
      else if (status === 409) showError('That account is already linked to someone else.');
      else showError('That took too long. Start the link again.');
    }, { once: true });
  }
}
