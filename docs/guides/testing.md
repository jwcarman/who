# Testing

The `who-test` module helps you test an app built on who with `MockMvc`. It signs a user in without an email link, and it behaves like a single-page app in a browser: it keeps the session and cookies and sends the CSRF header.

Import `who-bom` (see [Getting Started](getting-started.md)), then add `who-test` with test scope and no version:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.jwcarman.who</groupId>
            <artifactId>who-bom</artifactId>
            <version>0.11.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependency>
    <groupId>org.jwcarman.who</groupId>
    <artifactId>who-test</artifactId>
    <scope>test</scope>
</dependency>
```

It depends on `who-spring-security`, `spring-test` and `spring-security-test`. It adds no runtime code to your app.

## Signed-in sessions

`WhoTestSessions.signedIn(identityId, credentialId)` returns a `MockHttpSession` that holds a `WhoSession` signed in now:

```java
Identity identity = whoService.createIdentity();
EmailCredential credential = emailCredentialService.register(identity, "ada@example.com");

mockMvc.perform(get("/api/notes")
                .session(WhoTestSessions.signedIn(identity.id(), credential.id())))
        .andExpect(status().isOk());
```

Who stores ids only and resolves them from the database on every request, as in production. So the identity must exist and be active, and the credential must exist, in the database the test uses. A session for an identity that is not there is rejected.

## A browser for MockMvc

A bare `MockMvc` forgets the session and cookies between requests. `WhoMockMvcBrowser` wraps a `MockMvc` and remembers them:

```java
WhoMockMvcBrowser browser = new WhoMockMvcBrowser(mockMvc)
        .signedInAs(identity.id(), credential.id());

browser.perform(post("/api/notes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"hello\"}"))
        .andExpect(status().isCreated());

browser.perform(post("/logout")).andExpect(status().isNoContent());
browser.perform(get("/who/me")).andExpect(status().isUnauthorized());
```

On every request the browser owns:

- **The session.** One session is kept. It is replaced by the one the app creates, and by a fresh one after the app invalidates it, for example on logout.
- **The cookies.** It keeps the cookies from every response and drops those the app expires. If a cookie on your request builder has the same name as one the browser holds, the browser's cookie is used.
- **The CSRF header.** On `POST`, `PUT`, `PATCH` and `DELETE` it sets `X-XSRF-TOKEN` to the value of the `XSRF-TOKEN` cookie, as an SPA does with `csrf.spa()`. Spring Security Test's `csrf().asHeader()` sends a masked token, which `csrf.spa()` rejects, so do not use it here.

The CSRF cookie comes from `GET /who/me`. When the browser holds no such cookie, on its first request and after logout, it sends that `GET` first. This assumes a chain built with `WhoWebSecurity.passwordless()`.

`browser.cookie(name)` returns a held cookie, and `browser.session()` returns the current session. Without `signedInAs`, the browser starts signed out.

Build the `MockMvc` with Spring Security applied, for example with `@AutoConfigureMockMvc` or `MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())`. An instance is not thread-safe, so use one per test.

## What it does not do

`who-test` has no emulated authenticator, so it cannot run a passkey registration or sign-in. Test those ceremonies with a browser-level test, or sign the user in with `signedInAs` and test what the user does next.
