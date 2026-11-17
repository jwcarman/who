# Agent Skill

who ships an [Agent Skill](https://docs.claude.com/en/docs/agents-and-tools/agent-skills/overview)
for coding agents such as Claude Code. With it, an agent asked to add sign-in, passkeys, API keys or
permissions to a who app reads who's own guides before writing code, instead of guessing from
general Spring knowledge.

## Install

In Claude Code, add who's repository as a plugin marketplace and install the plugin:

```text
/plugin marketplace add jwcarman/who
/plugin install who@who
```

For Codex, Cursor, Gemini CLI, GitHub Copilot and the other agents that read
[Agent Skills](https://agentskills.io), install it with Vercel's
[`skills`](https://github.com/vercel-labs/skills) command, which finds the agents you have and puts
the skill where each one looks:

```bash
npx skills add https://github.com/jwcarman/who/tree/main/plugins/who/skills/who
```

Add `-g` to install it for every project instead of the current one. Or copy
[`plugins/who/skills/who`](https://github.com/jwcarman/who/tree/main/plugins/who/skills/who) into
your agent's skills directory yourself.

## What it knows

- Which module to add for what the user wants, and to import `who-bom` rather than pin each module.
- The settings each module needs before it starts, and where secrets belong.
- When who's default security chain is enough, and when an app needs chains of its own (a
  website and an API, say).
- The rules that keep an app safe: identities through `WhoService`, permissions with
  `@RequiresPermission`, which endpoints a new credential can reach, rate limits at the ingress,
  Turnstile's test keys only in development.
- Every guide on this site and the changelog, copied into the skill so it matches the version it
  describes.

The skill follows `main`, which is usually ahead of the latest release, so its guides can describe
modules and settings a released who does not have yet. It carries the changelog too, and tells the
agent to check the project's who version against it and not use anything newer; when a project's
who is older than the guides, it reads [Upgrading](guides/upgrading.md) and points out what
differs.

## How it is tested

The skill is tested by giving an agent small Spring Boot apps and tasks (email sign-in, passkeys
with Turnstile, an API key for CI, a website with an API), with and without the skill, each run in
an isolated container that holds who's library jars and nothing else from this repository. In
those runs, the agent with the skill imported `who-bom`, set every required setting, kept secrets
out of the config and wired the chains these guides describe.
