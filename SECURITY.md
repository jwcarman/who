# Security policy

who signs people in and decides what they may do, so a flaw in it is a flaw in every application
that uses it. Thank you for taking the time to report one privately.

## Reporting a vulnerability

Report it through GitHub's private vulnerability reporting:
[Report a vulnerability](https://github.com/jwcarman/who/security/advisories/new).

Please do not open a public issue, discussion or pull request for a vulnerability.

A useful report says:

- which version and module are affected;
- what an attacker can do, and what they need first (an account, a stolen session, network
  position);
- how to reproduce it, ideally as a failing test or a minimal application.

## What happens next

- You get an acknowledgement within 3 working days.
- Within 10 working days you hear whether it is confirmed, and what fix and release are planned.
- The fix ships in a new release, with a GitHub security advisory that credits you unless you
  prefer otherwise. A CVE is requested for confirmed vulnerabilities.
- Please keep the details private until the advisory is published. If a fix takes longer than
  90 days, we agree on a disclosure date with you.

who is maintained by one person, so these are commitments made in good faith, not a contract.

## Supported versions

Until 1.0, only the latest minor release gets security fixes, as a patch release (for example
0.15.1). Upgrading to the latest minor is the supported way to get a fix; the
[upgrading guide](https://jwcarman.github.io/who/guides/upgrading/) covers each step.

| Version        | Security fixes |
|----------------|----------------|
| latest 0.x     | Yes            |
| older 0.x      | No             |

## Scope

In scope: the who modules published to Maven Central (`org.jwcarman.who`), their default
configuration and their documented use.

Out of scope:

- `who-example`, which is a demonstration application and not published;
- weaknesses that need a non-default, documented-as-unsafe configuration;
- problems in Spring Security, Spring Boot or other dependencies, which should go to those
  projects (tell us too if who uses them in a way that makes it worse);
- missing defenses that who documents as the application's job, such as rate limits at the
  ingress and TLS. See the [security model](https://jwcarman.github.io/who/guides/security-model/).

## Advisories

Published advisories: [github.com/jwcarman/who/security/advisories](https://github.com/jwcarman/who/security/advisories).

## Verifying a release

Every artifact on Maven Central is signed with GPG and ships a CycloneDX SBOM
(`-cyclonedx.json`). The jars and their signatures are also attached to each
[GitHub Release](https://github.com/jwcarman/who/releases).

**Signature.** Releases are signed with this key:

```
6268 5DD0 87F8 4A30 B6E7  7BF2 0DE1 9C9D 88B8 9E43
```

It is published on [keyserver.ubuntu.com](https://keyserver.ubuntu.com/pks/lookup?op=index&search=0x62685DD087F84A30B6E77BF20DE19C9D88B89E43).
Fetch it, then check a jar against its `.asc` file:

```bash
gpg --keyserver keyserver.ubuntu.com --recv-keys 62685DD087F84A30B6E77BF20DE19C9D88B89E43
gpg --verify who-core-0.16.0.jar.asc who-core-0.16.0.jar
```

`gpg` should report a good signature from that fingerprint. If a release is ever signed with a
different key, this section will say so first.

**Provenance.** Release builds also carry a GitHub build attestation, which ties a jar to the
workflow run and commit that built it:

```bash
gh attestation verify who-core-0.16.0.jar --repo jwcarman/who
```

**Rebuilding.** Builds are reproducible from 0.16.0 on: building a release tag with the same
JDK major version gives byte-for-byte the jars on Maven Central.

```bash
git checkout 0.16.0
./mvnw -B versions:set -DnewVersion=0.16.0 -DgenerateBackupPoms=false
./mvnw -B package -DskipTests
```
