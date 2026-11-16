# @jwcarman/who-client

A typed TypeScript client for the browser endpoints of [who](https://github.com/jwcarman/who),
the Spring Boot security library: sign-in by email link or code, passkeys, provider sign-in,
sessions and sign-out. It handles who's CSRF cookie and the WebAuthn encoding for you, and each
call answers a typed result instead of a status code.

```bash
npm install @jwcarman/who-client
```

```ts
import { createWhoClient } from "@jwcarman/who-client";

const who = createWhoClient(); // who at /who on this page's origin

const result = await who.passkeys.signIn();
if (result.ok) {
  location.assign("/");
} else if (result.reason === "cancelled") {
  show("No passkey was used.");
} else {
  show("That passkey did not sign you in.");
}
```

Every call, its results and the options are in the
[TypeScript Client guide](https://jwcarman.github.io/who/guides/client/). Each release has the same
version as who's.

## Building

```bash
npm ci && (cd lint-tools && npm ci)
npm test
npm run build
```

The Maven build (`./mvnw install` from the repository root) does all of this too.

## License

Apache-2.0
