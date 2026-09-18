---
name: commit-style
description: Use Scoped Commits when writing commit messages or committing changes in this project.
---

# Commit style

Follow [Scoped Commits](https://scopedcommits.com/): `<scope>: <description>`.

- Inspect the changes being committed. Name the affected subsystem, area, or module as the scope; reuse established names where appropriate.
- Start with the scope directly, without a Conventional Commits type prefix.
- Keep the description concise. For this project, use English imperative phrasing without a final period.
- For multiple areas, use a shared broader scope or comma-separated scopes; use `treewide` for changes across the project.
- Add a body or trailers when useful, for example when the reason or context for the change is difficult to infer from the title and diff alone.
- Reverts, merges, and other special commits may use their natural format.

Example: `renderer: align guides with folded blocks`
