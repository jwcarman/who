# @jwcarman/who-client

A typed TypeScript client for the browser endpoints of [who](https://github.com/jwcarman/who), the
Spring Boot security library: sign-in by email link or code, passkeys, provider sign-in, sessions
and sign-out. It handles who's CSRF cookie and the WebAuthn encoding for you, and answers typed
results instead of status codes.

```ts
import { createWhoClient } from "@jwcarman/who-client";

const who = createWhoClient(); // who at /who on this page's origin

const me = await who.me();
if (me.ok) {
  console.log(me.value.identityId);
}
```

Each release has the same version as who's.

## Building

```bash
npm ci && (cd lint-tools && npm ci)
npm test
npm run build
```

The Maven build (`./mvnw install` from the repository root) does all of this too.

## License

Apache-2.0
