import { ensureCsrfCookie, requestLink, signInWithCode } from '/js/who.js';

const $ = id => document.getElementById(id);

// Sent here to sign in again (?again=/keys): go back there after, but only to a page of this site.
const next = localPath(new URLSearchParams(location.search).get('again'));

// A path on this site, or '/': '//evil.example' and '/\evil.example' resolve to another origin.
function localPath(path) {
  if (!path) return '/';
  const url = new URL(path, location.origin);
  return url.origin === location.origin ? url.pathname + url.search + url.hash : '/';
}

function showError(text) {
  $('error').textContent = text;
  $('error').hidden = false;
}

await ensureCsrfCookie();

$('email-form').addEventListener('submit', async e => {
  e.preventDefault();
  await requestLink($('email').value);
  try {
    // the link opens on /signin/link, which goes back here after
    localStorage.setItem('who-next', next);
  } catch {
    // no storage: the link goes to the board
  }
  $('code-step').hidden = false;
  $('code').focus();
});

$('code-form').addEventListener('submit', async e => {
  e.preventDefault();
  if (await signInWithCode($('email').value, $('code').value)) {
    location.assign(next);
  } else {
    showError('That code did not work. Check it, or ask for a new email.');
  }
});

// Back from Keycloak with an account who does not know, or a failed sign-in.
if (new URLSearchParams(location.search).has('error') || new URLSearchParams(location.search).has('pending')) {
  showError('Keycloak did not sign you in here. Only alice and bob have accounts on this board.');
}
