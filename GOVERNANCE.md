# Governance

who is a small project with a single maintainer. This page says who decides what, and how.

## Roles

**Maintainer:** [James Carman](https://github.com/jwcarman). The maintainer:

- sets the direction in [ROADMAP.md](ROADMAP.md);
- reviews and merges pull requests, and decides on features and breaking changes;
- cuts releases and holds the release credentials (Maven Central and the signing key);
- handles vulnerability reports as described in [SECURITY.md](SECURITY.md);
- enforces the [code of conduct](CODE_OF_CONDUCT.md).

**Contributors:** anyone who opens an issue, discussion or pull request. Contributors propose
changes and review each other's work; [CONTRIBUTING.md](CONTRIBUTING.md) says what a pull request
needs.

## How decisions are made

- Bugs and small changes are decided in their issue or pull request.
- Features and behavior changes start as an issue describing the problem. Larger ones get a
  design document in `docs/superpowers/specs/` before any code, and the discussion happens
  there.
- Where people disagree, the maintainer decides and writes down why in the issue or design
  document.
- Security decisions favor the safer default, even at some cost to convenience; see the
  [security model](https://jwcarman.github.io/who/guides/security-model/).

## Becoming a maintainer

Someone who has contributed regularly and carefully can be invited to become a maintainer, with
merge rights and, later, release access. A second maintainer is welcome: it would let the project
carry on if the current one became unavailable.

## Changes to this document

Changes to governance are made by pull request and decided by the maintainer.
