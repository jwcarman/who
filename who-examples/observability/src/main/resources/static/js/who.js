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
