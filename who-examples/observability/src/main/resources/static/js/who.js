// The calls the who guides show, from the web-and-api example, cut to what this page uses: the CSRF
// header. Nothing here is specific to this app. Each section names the guide it comes from.

// --- CSRF (Email Links guide, "The sign-in flow") ---------------------------------------------
// who protects the chain with Spring's csrf.spa(): read the XSRF-TOKEN cookie and send it back in
// the X-XSRF-TOKEN header on every POST. Read it at each call, never cache it: it changes when
// someone signs in or out.

function xsrf() {
  return document.cookie.split('; ')
    .find(c => c.startsWith('XSRF-TOKEN='))?.split('=')[1];
}

export async function post(path, body) {
  return send('POST', path, body);
}

export async function send(method, path, body) {
  return fetch(path, {
    method,
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      'X-XSRF-TOKEN': decodeURIComponent(xsrf() ?? '')
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
}

// The cookie exists only after a response from the server, so make a GET before the first POST.
// GET /who/me answers 401 when signed out, but sets the cookie either way.
export async function ensureCsrfCookie() {
  if (!xsrf()) {
    await fetch('/who/me', { credentials: 'same-origin' });
  }
}

// --- Sessions (Sessions guide, "See and end sessions") ----------------------------------------

// Where this person is signed in, newest first:
// [{ id, device, userAgent, signInMethod, signedInAt, lastSeenAt, current }]. The id is who's
// own, never the session cookie, so the list can be shown without exposing a session.
export async function listSessions() {
  const res = await fetch('/who/sessions', { credentials: 'same-origin' });
  return res.ok ? res.json() : [];
}

// Signs one browser out. This browser's own session ends at once; any other needs a recent
// sign-in. 204 ended, 401 signed in too long ago, 404 not one of yours.
export async function endSession(id) {
  return (await send('DELETE', '/who/sessions/' + encodeURIComponent(id))).status;
}

// Signs out every browser but this one. 200 { ended }, 401 signed in too long ago.
export async function endOtherSessions() {
  return (await send('DELETE', '/who/sessions?others=true')).status;
}
