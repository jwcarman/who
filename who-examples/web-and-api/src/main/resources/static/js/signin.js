import { ensureCsrfCookie, requestLink, signInWithCode } from '/js/who.js';

const $ = id => document.getElementById(id);

function showError(text) {
  $('error').textContent = text;
  $('error').hidden = false;
}

await ensureCsrfCookie();

$('email-form').addEventListener('submit', async e => {
  e.preventDefault();
  await requestLink($('email').value);
  $('code-step').hidden = false;
  $('code').focus();
});

$('code-form').addEventListener('submit', async e => {
  e.preventDefault();
  if (await signInWithCode($('email').value, $('code').value)) {
    location.assign('/');
  } else {
    showError('That code did not work. Check it, or ask for a new email.');
  }
});

// Back from Keycloak with an account who does not know, or a failed sign-in.
if (new URLSearchParams(location.search).has('error') || new URLSearchParams(location.search).has('pending')) {
  showError('Keycloak did not sign you in here. Only alice and bob have accounts on this board.');
}
