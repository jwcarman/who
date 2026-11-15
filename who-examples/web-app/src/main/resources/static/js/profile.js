import { registerPasskey, removePasskey } from '/js/who.js';

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
