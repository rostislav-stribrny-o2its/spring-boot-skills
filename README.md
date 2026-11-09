# Spring Boot skills — Elysium / Opereta selection

Fork of [rrezartprebreza/spring-boot-skills](https://github.com/rrezartprebreza/spring-boot-skills),
used as a **storage of agent skills** (Claude format) for the Elysium Java base
(Spring Boot 4, JDK 25, PostgreSQL) and for the Opereta application.

## Branches

| Branch | Content |
|---|---|
| `main` | exact mirror of the upstream `main`; never committed to |
| `elysium-stack` | skills selected and adapted for the Elysium Java base |
| `opereta-stack` | `elysium-stack` plus what is specific to Opereta |

Changes flow one way: upstream → `main` → `elysium-stack` → `opereta-stack` (by merge).
Fixes that are useful for everyone go to a topic branch from `main` and upstream as a pull request.

## Layout

- `.claude/skills/<skill>/` — the skills in use (`SKILL.md`, examples, templates).
- `_unused/` — everything else from upstream, moved (not deleted) so that upstream changes keep merging:
  `_unused/skills/` (skills not selected), `_unused/agents/` (Codex metadata),
  `_unused/root/` (upstream site, plugins, tooling, original README), `_unused/elysium-only/` (on `opereta-stack` only).

Moves are always separate commits without content changes; content changes follow in their own commits.

## License

MIT, see [LICENSE](LICENSE). Original work © Rrezart Prebreza; the original README is kept in `_unused/root/README.md`.
