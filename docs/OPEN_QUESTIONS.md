# Ghost Protocol — Open Questions

Items that require the user's input, or that the proof-of-concept (Roadmap M0) must resolve, before Phase A's scope or implementation can be considered final. Each links back to where it originates.

## Product / Legal

1. ~~**Storing other players' data.**~~ **Resolved.** PGCRs include teammates/opponents who never linked a Ghost Protocol account. **Decision:** storing display name and platform for non-linked participants (the current `activity_participant.bungie_destiny_user_info` design in `DATABASE_DESIGN.md`) is acceptable for Phase A as scoped — no further minimization needed.
2. **No deletion path for non-linked participants.** Open Question #1's resolution keeps a minimal display-name/platform snapshot for PGCR participants who never linked a Ghost Protocol account. Because they have no account with us, there's no self-service way for them to request its removal — unlike a linked user, who can disconnect/delete at will. **Accepted as out of scope for Phase A** (consistent with other third-party Destiny tools handling the same public API data) rather than left silently unaddressed; revisit if a takedown request is ever actually received.
3. **Monetization.** Bungie's ToS (`API_FEASIBILITY.md` §1) permits non-commercial use only without prior written permission. Phase A is free/non-commercial as scoped. **Needs confirmation:** no monetization (ads, subscriptions, paid tiers) is planned without first securing that permission — flag this explicitly if plans change later.

## API Behavior (resolved by the Phase A proof-of-concept, M0)

4. **Activity history depth/retention.** No documented maximum exists for `GetActivityHistory`. The ingestion design (`SYSTEM_ARCHITECTURE.md`) handles this safely either way (walk-until-known-boundary), but product copy should not claim "full history" until the PoC empirically determines whether a practical ceiling exists.
5. **Weapon-data coverage by activity mode.** Unconfirmed whether `extended.weapons` in a PGCR is populated consistently across PvP, PvE, raids, and dungeons. This is the single biggest risk to the Weapon Analytics feature as scoped in `PRODUCT_REQUIREMENTS.md` §5. The PoC must sample across modes before this feature ships with confidence.
6. **PGCR retention window.** No official statement on how long a PGCR remains fetchable after the fact. Motivates the "ingest promptly, don't rely on backfill" design already baked into `SYSTEM_ARCHITECTURE.md`'s ingestion flow — flagged here so it isn't forgotten if ingestion is ever paused for an extended period (e.g., during a long maintenance window).
7. **Exact per-key stat names inside Bungie's generic `values` dictionaries** (both `activity_participant.values` and `activity_weapon_usage.values`). The schema confirms the dictionary *container* exists; the specific key strings (e.g., exact spelling of a "precision kills" key) weren't enumerated in the official schema pages retrieved. Needs direct inspection of real API responses during the PoC rather than guessing key names in code.

## Infrastructure (re-verify at implementation time)

8. **Render free-tier exact CPU allocation** — corroborated by third-party sources (~0.1 CPU / 512MB RAM) but not confirmed on Render's own pricing page.
9. **Neon/Render free-Postgres-specific limits beyond what's documented** — Neon's own limits are confirmed (`DEPLOYMENT_PLAN.md`); re-check for any change between this research and actual implementation, since free-tier terms can shift.
10. **GitHub Actions sync-trigger reliability in practice.** The chosen $0 workaround for Render's lack of free background jobs (`DEPLOYMENT_PLAN.md`) is a reasonable design but untested in production. If sync reliability proves to matter more than the $0 constraint once real usage begins, the documented fallback is Render's paid Starter tier (~$7/month, price itself unconfirmed on Render's own page).

## Bungie Application Registration (resolved during Roadmap M1)

11. **OAuth client-type selection UI.** The confidential-vs-public client toggle's exact location on the `bungie.net/en/Application` registration form couldn't be inspected without logging in. Confirm during the user's manual registration step (M1) that a confidential client is actually selected — this is required for refresh tokens to be issued at all.

## Phase B (deferred, not blocking Phase A)

12. **Inventory/item-instance and loadout-write endpoint feasibility** was only surveyed at a high level this session (`API_FEASIBILITY.md` §5). A dedicated feasibility pass — mirroring the depth of this session's Phase A research — is needed before Phase B scope is finalized (already captured as a Roadmap M10 task).
13. **Triumphs/Collections API support** was not researched this session at all. Needs its own feasibility pass before being promised in any Phase B plan.
