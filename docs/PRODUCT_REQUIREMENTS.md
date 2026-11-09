# Ghost Protocol — Product Requirements (Phase A)

**Tagline:** Know your Guardian.
**Description:** Destiny 2 Companion & Performance Intelligence Platform.
**Scope:** Phase A — Personal Gameplay Performance Analytics. Phase B (inventory/loadouts/companion features) is explicitly out of scope for this document; see `DEVELOPMENT_ROADMAP.md` M10.

This is an independent community project with no official affiliation with Bungie. Every requirement below is scoped to what `API_FEASIBILITY.md` confirms the official Bungie API can actually deliver — no feature here assumes a capability that document marks Unverified without calling that out.

---

## 1. Authentication & Account Linking

Users can:
- Sign in via Bungie OAuth (authorization-code flow, confidential client, backend-only token handling — see `SYSTEM_ARCHITECTURE.md`).
- Link their Destiny account(s), including correctly resolving cross-save: the app shows the player's cross-save-primary platform as their "main" account and lists other linked (merged/inactive) platforms for transparency.
- View their linked account information (platforms, display names, last-sync time).
- Disconnect their account, which stops all future syncing and removes their stored Destiny data (see data-deletion behavior in `DATABASE_DESIGN.md`).
- Access only their own application data — no user can view another user's linked-account management screens or trigger another user's sync.

**Never requested or stored:** Bungie account passwords. Only OAuth tokens (encrypted at rest, backend-only) and the data documented below.

## 2. Guardian Dashboard

Displays, per the confirmed-feasible fields in `API_FEASIBILITY.md` §2:
- Linked Bungie account summary (cross-save-aware).
- Guardian characters: class, race, light level, playtime, last-played date, emblem.
- Recent activity feed (from stored `GetActivityHistory` + ingested PGCR data).
- Activity statistics for the stats actually present in Bungie's response (no invented metrics).
- Historical gameplay trends (computed from locally stored data, not live-recomputed from Bungie on every page load).
- Weapon usage analytics **where PGCR data has actually been ingested and confirmed to contain it** — see the mode-coverage caveat in §4 below.

**Explicit requirement:** any dashboard section whose underlying data component came back absent/privacy-gated from Bungie (per `API_FEASIBILITY.md` §2's "silent omission" behavior) must render a clear "data unavailable" state — never a blank space that looks like broken UI, and never a zero that implies "confirmed zero."

## 3. Activity Explorer

Users can:
- Browse historical activities (backed by locally stored `Activity`/`ActivityParticipant` rows, synced from Bungie — not a live per-request Bungie call).
- Filter by activity type/mode (Raid, Strike, PvP, Gambit, Dungeon, etc. — the modes Bungie's `DestinyActivityModeType` enum actually supports).
- Filter by character.
- Filter by date range.
- View completion status where available (`standing`/`completed` fields, populated once a PGCR has been ingested for that activity — activities still in `summary_only` state show as "details pending").
- Open an individual activity's full report.

**Explicit requirement:** because `GetActivityHistory`'s documented depth/retention limit is unknown (`API_FEASIBILITY.md` §3), the Activity Explorer must not claim to show "complete" history — it shows everything successfully synced, with a visible "oldest synced activity" boundary rather than an implied guarantee of completeness.

## 4. Individual Activity Reports

For any activity whose PGCR has been ingested (`pgcr_status = complete`), display:
- Activity name and type (resolved via manifest lookup from the activity reference hash).
- Completion status / standing (win-loss-placement).
- Duration (from `period`/available timing fields).
- The viewing player's performance: kills, deaths, assists, and whatever additional stats are present in Bungie's per-player `values` dict for that activity (only confirmed-present fields are rendered — see `API_FEASIBILITY.md` §4's note that the exact stat-key set is a runtime dictionary, not a fixed schema).
- Weapon usage for that activity, **only when `weapon_data_status = present`** for that participant row. When it's `absent`, the UI shows "no weapon data recorded for this activity" rather than omitting the section silently or showing zeroes.
- Other participants in the match (teammates/opponents), using the minimal denormalized snapshot stored for non-linked players (privacy consideration resolved in `OPEN_QUESTIONS.md` #1).

**Explicit non-requirement:** subclass/super-used display is **not** included in Phase A — `API_FEASIBILITY.md` §4 found no confirmed PGCR field for it. Revisit only if the PoC or later research finds a reliable source.

## 5. Weapon Analytics

Explore weapon usage data aggregated from ingested PGCRs:
- Most frequently used weapons (by kill count, across all ingested activities).
- Weapon usage by activity type.
- Weapon usage over time (trend charts, computed from `activity_weapon_usage` rows grouped by period).
- Weapon usage by character.
- Weapon usage comparisons (e.g., two weapons' kill counts side-by-side over the same date range).

**Hard requirement, directly from the API_FEASIBILITY.md §4 critical finding:** this entire feature's reliability depends on whether `extended.weapons` is populated consistently across activity modes, which is **unconfirmed**. The PoC (Roadmap M0) must sample PGCRs across raid/strike/PvP/gambit before this feature's scope is locked. If some modes turn out not to carry weapon data, the UI must label analytics as scoped to the modes where data is confirmed present (e.g., "Weapon analytics currently available for: Raids, Strikes, PvP"), not silently under-report.

**Never:** fabricate a weapon statistic, or assume a weapon's absence from a given PGCR's `extended.weapons` array means "zero kills" rather than "no data" — the schema's `weapon_data_status` distinction (see `DATABASE_DESIGN.md`) must be surfaced, not collapsed, in the UI.

## 6. Historical Analytics

Visualizations for:
- Activity frequency over time.
- Activity completion history (win/loss or completion rate trends).
- Performance trends: K/D (kills/deaths) and KAD (kills+assists/deaths) over time, computed from stored `kills`/`deaths`/`assists`, never stored as separate columns — see `SYSTEM_ARCHITECTURE.md`'s `analytics` module.
- Performance by activity type.
- Weapon usage over time (shared with §5).
- Character-specific activity statistics.

All metrics are calculated from locally stored historical data (Phase A does not recompute from live Bungie calls on every dashboard load — see the ingestion design in `SYSTEM_ARCHITECTURE.md`). **Missing data and zero are always visually distinguishable** — a character with no ingested activities for a given mode shows "no data yet," not a zero-value bar on a chart.

## 7. Account Settings

- Linked account management (view/relink/disconnect).
- Synchronization status: last successful sync time, current `sync_checkpoint` state, and a visible indicator if a user's refresh token has expired and re-authentication is needed (`needs_reauth`, per `SYSTEM_ARCHITECTURE.md`'s auth design).
- Account disconnection, with a clear explanation of what gets deleted (see `DATABASE_DESIGN.md`'s cascade behavior).

## UX Requirements (apply across all pages)

- Responsive design (usable on both desktop and mobile viewport widths).
- Consistent navigation across Dashboard / Guardian Profile / Activity Explorer / Activity Report / Weapon Analytics / Account Settings.
- Loading states for every async data fetch.
- Error states distinguishable from empty states (a failed Bungie sync looks different from "you have no raids yet").
- Empty states with clear next-action guidance (e.g., "Link your Destiny account to get started").
- Clear, discoverable filter controls in the Activity Explorer.
- Accessible color contrast (WCAG AA minimum) throughout, including chart colors.
- Chart tooltips and axis labels that state units and time ranges explicitly.
- A visual identity distinct from Bungie's own UI — dark, polished, futuristic, but not a copy of Destiny 2's actual interface or unlicensed artwork.

## Demo Mode

A publicly accessible demo using **synthetic, fixture-based data** (not real user data) so visitors can explore the dashboard without linking a Bungie account. Demo mode must be clearly labeled as such in the UI and must not share any code path that could leak real user data into a public demo session. Full design in `DEPLOYMENT_PLAN.md`.

## Explicitly Out of Scope for Phase A

(Mirrors `API_FEASIBILITY.md`'s "Explicitly out of scope" section — repeated here as a product constraint, not just a technical one):

- No real-time/live combat tracking or live weapon-damage display.
- No claim of "complete" or "unlimited" activity history.
- No guarantee that every historical activity will have a PGCR available (ingest promptly; some may remain `summary_only` or `failed`).
- No inventory, loadout, or item-management features (Phase B).
- No monetization of any kind without first securing Bungie's written permission for commercial use (per the ToS finding in `API_FEASIBILITY.md` §1).
