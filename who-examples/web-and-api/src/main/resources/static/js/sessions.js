// "Where you're signed in": lists this person's browser sessions from GET /who/sessions and lets
// them sign out of any of them (Sessions guide, "See and end sessions"). It fills a
// <section id="sessions"> holding a <ul class="list"> and a [data-end-others] button. Signing out
// another browser needs a recent sign-in; a section with data-sign-in-again="/signin?again=..."
// sends a stale person there, otherwise they are told to sign in again.
import { endOtherSessions, endSession, listSessions } from '/js/who.js';

const section = document.getElementById('sessions');
const list = section.querySelector('ul');
const endOthers = section.querySelector('[data-end-others]');
const message = section.querySelector('[data-sessions-message]');

// how they signed in, as /who/sessions names it
const METHODS = {
  'email-link': 'an email link',
  'email-code': 'an email code',
  passkey: 'a passkey',
  oidc: 'a provider'
};

const when = iso =>
  new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });

function say(text) {
  message.textContent = text;
  message.hidden = !text;
}

function stale() {
  if (section.dataset.signInAgain) {
    location.assign(section.dataset.signInAgain);
  } else {
    say('You signed in too long ago to sign out other browsers. Sign out, sign in again, then try again.');
  }
}

function row(session) {
  const item = document.createElement('li');
  const text = document.createElement('span');
  text.className = 'grow';
  const device = document.createElement('strong');
  device.textContent = session.device;
  text.append(device);
  if (session.current) text.append(' (this browser)');
  const detail = document.createElement('span');
  detail.className = 'muted';
  detail.textContent =
    `Signed in ${when(session.signedInAt)} with ${METHODS[session.signInMethod] ?? session.signInMethod}`
    + ` · last active ${when(session.lastSeenAt)}`;
  // the full user agent, for someone who wants to know exactly which browser it was
  detail.title = session.userAgent ?? '';
  text.append(document.createElement('br'), detail);

  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'secondary';
  button.textContent = 'Sign out';
  button.addEventListener('click', async () => {
    const status = await endSession(session.id);
    if (status === 204 && session.current) location.assign('/signin');
    else if (status === 401) stale();
    else show();
  });
  item.append(text, button);
  return item;
}

async function show() {
  say('');
  const sessions = await listSessions();
  list.replaceChildren(...sessions.map(row));
  endOthers.hidden = sessions.length < 2;
}

endOthers.addEventListener('click', async () => {
  const status = await endOtherSessions();
  if (status === 401) stale();
  else show();
});

show();
