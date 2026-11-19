# Ghost Protocol — Open Questions

Items that require the user's input, or that the proof-of-concept (Roadmap M0) must resolve, before Phase A's scope or implementation can be considered final. Each links back to where it originates.

## Product / Legal

1. ~~**Storing other players' data.**~~ **Resolved.** PGCRs include teammates/opponents who never linked a Ghost Protocol account. **Decision:** storing display name and platform for non-linked participants (the current `activity_participant.bungie_destiny_user_info` design in `DATABASE_DESIGN.md`) is acceptable for Phase A as scoped — no further minimization needed.
2. **No deletion path for non-linked participants.** Open Question #1's resolution keeps a minimal display-name/platform snapshot for PGCR participants who never linked a Ghost Protocol account. Because they have no account with us, there's no self-service way for them to request its removal — unlike a linked user, who can disconnect/delete at will. **Accepted as out of scope for Phase A** (consistent with other third-party Destiny tools handling the same public API data) rather than left silently unaddressed; revisit if a takedown request is ever actually received.
3. **Monetization.** Bungie's ToS (`API_FEASIBILITY.md` §1) permits non-commercial use only without prior written permission. Phase A is free/non-commercial as scoped. **Needs confirmation:** no monetization (ads, subscriptions, paid tiers) is planned without first securing that permission — flag this explicitly if plans change later.

## API Behavior (resolved by the Phase A proof-of-concept, M0)

4. ~~**Activity history depth/retention.**~~ **Resolved (M0 PoC, 2026-11-14).** No numeric cap exists. `GetActivityHistory` terminates naturally when an otherwise-200 response's `activities` key is entirely absent (not an empty array) — confirmed by walking a real account (92,676 minutes played) back to its full history: 23 page fetches, 5,453 activities retrieved, date range 2017-09-10 to 2026-11-01 (essentially since Destiny 2 launched), zero 429s. One-account sample; the walk-until-boundary ingestion design in `SYSTEM_ARCHITECTURE.md` remains correct regardless of other accounts' depth.
5. ~~**Weapon-data coverage by activity mode.**~~ **Resolved (M0 PoC, 2026-11-17).** Sampled one PGCR each of Raid, Strike, PvP, Gambit, Dungeon, and Trials of Osiris from a real account/character and inspected `extended.weapons` for our own entry specifically: **all six categories came back populated**, none absent, none empty. One-account/character sample — the `weapon_data_status` (`present`/`absent`/`unknown`) tracking in `DATABASE_DESIGN.md` stays in the design as a safeguard for accounts or modes not yet observed.
6. **PGCR retention window.** No official statement on how long a PGCR remains fetchable after the fact. Motivates the "ingest promptly, don't rely on backfill" design already baked into `SYSTEM_ARCHITECTURE.md`'s ingestion flow — flagged here so it isn't forgotten if ingestion is ever paused for an extended period (e.g., during a long maintenance window). Not tested by the M0 PoC — still open.
7. **Exact per-key stat names inside Bungie's generic `values` dictionaries** (both `activity_participant.values` and `activity_weapon_usage.values`). **Partially resolved (M0 PoC, 2026-11-17):** `activity_weapon_usage.values` keys confirmed from real PGCRs across all six sampled modes — `uniqueWeaponKills`, `uniqueWeaponPrecisionKills`, `uniqueWeaponKillsPrecisionKills` — consistent across every sampled weapon/mode. `activity_participant.values` (the per-activity/per-player summary dict) was not enumerated by the PoC and remains open.

## Infrastructure (re-verify at implementation time)

8. **Render free-tier exact CPU allocation** — corroborated by third-party sources (~0.1 CPU / 512MB RAM) but not confirmed on Render's own pricing page.
9. **Neon/Render free-Postgres-specific limits beyond what's documented** — Neon's own limits are confirmed (`DEPLOYMENT_PLAN.md`); re-check for any change between this research and actual implementation, since free-tier terms can shift.
10. **GitHub Actions sync-trigger reliability in practice.** The chosen $0 workaround for Render's lack of free background jobs (`DEPLOYMENT_PLAN.md`) is a reasonable design but untested in production. If sync reliability proves to matter more than the $0 constraint once real usage begins, the documented fallback is Render's paid Starter tier (~$7/month, price itself unconfirmed on Render's own page).

## Bungie Application Registration (resolved during Roadmap M1)

11. ~~**OAuth client-type selection UI.**~~ **Resolved (2026-11-09).** The registration form does offer a Confidential option alongside Public, and the user selected Confidential — refresh tokens should be issuable as designed.

## Phase B (deferred, not blocking Phase A)

12. **Inventory/item-instance and loadout-write endpoint feasibility** was only surveyed at a high level this session (`API_FEASIBILITY.md` §5). A dedicated feasibility pass — mirroring the depth of this session's Phase A research — is needed before Phase B scope is finalized (already captured as a Roadmap M10 task).
13. **Triumphs/Collections API support** was not researched this session at all. Needs its own feasibility pass before being promised in any Phase B plan.
