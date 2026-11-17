# @jwcarman/who-client

A typed TypeScript client for the browser endpoints of [who](https://github.com/jwcarman/who),
the Spring Boot security library: sign-in by email link or code, passkeys and providers; managing
your own account; and who's admin endpoints. It handles who's CSRF cookie and the WebAuthn
encoding for you, and each call answers a typed result instead of a status code.

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

The calls follow who's paths:

| Namespace               | Calls                                                     | who's endpoints                        |
| ----------------------- | --------------------------------------------------------- | -------------------------------------- |
| `who`                   | `signInOptions()`, `signOut()`                            | `GET /who/sign-in`, `POST /who/logout` |
| `who.email`             | `send`, `signIn`, `signInFromLocation`                    | `/who/email/*`                         |
| `who.passkeys`          | `supported`, `signIn`                                     | `/who/passkey/sign-in*`                |
| `who.oidc`              | `authorizeUrl`, `pending`, `signUp`, `link`               | `/who/oidc/*`                          |
| `who.me`                | `get()`                                                   | `GET /who/me`                          |
| `who.me.sessions`       | `list`, `end`, `endOthers`                                | `/who/me/sessions`                     |
| `who.me.passkeys`       | `list`, `register`, `remove`                              | `/who/me/passkeys`                     |
| `who.me.emailAddresses` | `list`, `add`, `confirm`, `confirmFromLocation`, `remove` | `/who/me/email-addresses`              |
| `who.me.accounts`       | `list`, `link`, `unlink`                                  | `/who/me/accounts`                     |
| `who.me.apiKeys`        | `list`, `create`, `rotate`, `revoke`, `current`           | `/who/me/api-keys`                     |
| `who.admin.identities`  | `list`, `get`, `setStatus`, `addRole`, `removeRole`       | `/who/identities`                      |
| `who.admin.roles`       | `list`                                                    | `GET /who/roles`                       |
| `who.admin`             | `invite`                                                  | `POST /who/invitations`                |

Removing your last way in (a passkey, an email address or a linked account) fails with
`"last-credential"`, so an identity can always sign in.

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
