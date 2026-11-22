# Planning Docs Changelog

Tracks how `docs/` has changed over time and why, distinct from `git log`'s
commit-level detail. Each entry below is a milestone-level summary, written
when a phase of work that touched the docs wraps up — not a line-by-line
diff (see `git log -- docs/` for that).

## Original docs (Phase A planning, before any code)

The seven planning documents (`API_FEASIBILITY.md`, `PRODUCT_REQUIREMENTS.md`,
`SYSTEM_ARCHITECTURE.md`, `DATABASE_DESIGN.md`, `DEVELOPMENT_ROADMAP.md`,
`DEPLOYMENT_PLAN.md`, `OPEN_QUESTIONS.md`) were added in one pass, grounded in
direct research against Bungie's official API docs/schema/ToS — no
third-party-app assumptions. Two follow-up passes refined them before any
code existed:

- Security hardening: added a custom-domain requirement (the `SameSite=Lax`
  session cookie wouldn't survive cross-site between Vercel and Render's
  default subdomains), plus explicit IDOR/CORS/CSRF/XSS/manifest-download
  guidance.
- Moved that custom-domain step from Roadmap milestone M1 to M9, since
  local development (M1–M8) runs entirely on `localhost`, where the
  cross-site cookie problem doesn't apply.

Every claim in `API_FEASIBILITY.md` was tagged **Confirmed** (found in an
official source), **Unverified** (no official source either way), or
**Assumption** (an engineering default pending validation) — and the M0
proof-of-concept's whole job was to convert the PoC-relevant Unverified/
Assumption items into Confirmed ones with real data.

## Step 1 (OAuth round trip) — first PoC-driven updates

Two small, real corrections landed once actual code started hitting the
real Bungie API:

- Bungie's app-registration form rejects `http` redirect URIs outright,
  even for `localhost` — resolved `OPEN_QUESTIONS.md` #11 and sent the PoC's
  local callback listener over HTTPS via `mkcert` instead of plain HTTP.
- A real token exchange returned `expires_in=3600` /
  `refresh_expires_in=7776000`, upgrading both from Assumption to Confirmed
  in `API_FEASIBILITY.md`.

## Steps 2–7 (cross-save, profile, history, PGCR sampling, weapon coverage,
## manifest resolution) — docs fell behind the code

Every one of these steps ran successfully against a real account and
produced genuine empirical findings (recorded in their own PR descriptions:
`stewartwatwit/Ghost-Protocol#1`–`#5`), but **none of those findings were
folded back into `docs/`** until the M0 closeout below. For roughly ten
days, the planning docs still read as if the single biggest open risk —
whether PGCR weapon data is populated across activity modes — was
unconfirmed, even though step 6 had already measured it directly.

## M0 closeout (`stewartwatwit/Ghost-Protocol#6`) — current docs

This pass folded every M0-relevant empirical finding from steps 2–7 back
into the planning docs, per the roadmap's own gate ("M0 does not proceed to
M1 until the user has reviewed the PoC's findings"):

- **`OPEN_QUESTIONS.md`**
  - #4 (activity history depth/retention) — **resolved.** No numeric cap
    exists; `GetActivityHistory` terminates when `activities` is entirely
    absent from an otherwise-200 response. A 92,676-minute-playtime account
    walked to its full history: 23 page fetches, 5,453 activities,
    2017-09-10 to 2026-11-01, zero throttling.
  - #5 (weapon-data coverage by activity mode) — **resolved.** `extended.weapons`
    came back populated in all six required categories (Raid, Strike, PvP,
    Gambit, Dungeon, Trials of Osiris) on a real account — the single
    biggest risk flagged against Weapon Analytics.
  - #7 (generic `values` dictionary key names) — **partially resolved.**
    `activity_weapon_usage.values` keys confirmed (`uniqueWeaponKills`,
    `uniqueWeaponPrecisionKills`, `uniqueWeaponKillsPrecisionKills`);
    `activity_participant.values` keys remain unenumerated.
  - #6 (PGCR retention window) — still open; nothing in the PoC tested it.

- **`API_FEASIBILITY.md`**
  - History-depth and weapon-mode-coverage table rows upgraded from
    **Unverified** to **Confirmed (PoC-observed)**.
  - Added a PoC-observed note that `CharacterProgressions` (202) came back
    present, not privacy-gated, for the test account.
  - Added a manifest file-size lower bound (~230MB streamed to resolve 11
    target weapon hashes) — update cadence is still unobserved.
  - The "Proof-of-concept requirements" checklist at the bottom is now
    marked complete with results, instead of a plain unchecked list.

- **`PRODUCT_REQUIREMENTS.md`** §5 — the Weapon Analytics "hard requirement"
  note changed from "unconfirmed, PoC must sample" to "resolved by the
  PoC," while keeping the `weapon_data_status` safeguard in the design for
  accounts/modes not yet observed.

- **`DEVELOPMENT_ROADMAP.md`** — M0's full checklist, including all seven
  PoC sub-items, checked off; the milestone gate note changed from
  "pending the PoC" to "M0 findings reviewed; proceeding to M1."

No finding came back more limited than hoped — no activity mode was
missing weapon data, no history cap was hit, manifest resolution worked —
so Weapon Analytics and Activity Explorer scope are unchanged from the
original plan. This was a documentation catch-up, not a scope change.

## Step 8 (gap closure, post-M0 review, pre-M1) — PGCR retention and participant-values enumeration

Before starting M1, a review of the M0 PoC and planning docs together
(not a new roadmap milestone — a gap-closure pass) found two
`OPEN_QUESTIONS.md` items still genuinely open despite the M0 closeout:
#6 (PGCR retention window, never tested by any of the original seven PoC
steps) and #7 (only the weapon-usage half of the generic `values` dict
keys had been enumerated — the per-player half hadn't). The review also
surfaced a previously-undocumented gap: `API_FEASIBILITY.md`'s
`DestinyActivityModeType` mode-code integers (Strike=3, Raid=4, etc., used
throughout steps 5-8) were only ever cross-confirmed from two third-party
community mirrors, never from an official Bungie source — a real attempt
to verify this against Bungie's own generated API reference and its
GitHub schema repo couldn't reach the enum's member list within a
reasonable search effort, so the caveat is now explicit in
`API_FEASIBILITY.md` and tracked as `OPEN_QUESTIONS.md` #14 instead of
only living in a PoC code comment.

Two new standalone PoC files (`ActivityRetentionPoc`, `ParticipantValuesPoc`)
ran against the same real account as the original PoC:

- **PGCR retention (`OPEN_QUESTIONS.md` #6):** fetched the PGCR for the
  single oldest activity in this account's full history (2017-09-10,
  essentially Destiny 2's launch — the same depth boundary step 4 found) —
  succeeded (HTTP 200). Resolved as a ~9-year lower bound, one account;
  doesn't prove there's no purge window, only that this account hasn't hit
  one.
- **`activity_participant.values` keys (`OPEN_QUESTIONS.md` #7, remaining
  half):** enumerated across the same six required categories as steps
  5-7. Confirmed `kills`/`deaths`/`assists`/`score`/`completed`
  (`DATABASE_DESIGN.md`'s promoted columns) all live inside this dict, not
  as top-level entry fields, and — the one new mapping gotcha this step
  surfaced — every value in the dict comes back as a JSON floating-point
  number even for int/bool-semantic stats (`kills: 475.0`,
  `completed: 1.0`), which the `activity` module's DTO mapping now needs
  to cast/round explicitly rather than assume. `standing` was confirmed as
  the one field that genuinely is top-level (present and defaulting to `0`
  in every sampled mode, including solo activities with no opponent).

Findings folded into `OPEN_QUESTIONS.md` #6/#7/#14, `API_FEASIBILITY.md`
sections 3 (mode-code caveat) and 4 (retention, values dict),
`DATABASE_DESIGN.md` (field sourcing + float-cast note), and
`SYSTEM_ARCHITECTURE.md` (`bungie` module DTO-mapping note).

## Step 1 (M1) — Vault findings folded into docs

Two confirmed API-behavior findings from the Obsidian vault (local-only,
gitignored, never previously written into tracked docs) folded in before
M1 scaffolding began:
- `API_FEASIBILITY.md` §4 — PGCR's HTTPS→HTTP redirect from `www.bungie.net`
  to `stats.bungie.net`.
- `SYSTEM_ARCHITECTURE.md` (`bungie` module) — same redirect, as an
  implementation directive.
- `DATABASE_DESIGN.md` (`activity` table) — Trials of Osiris dual
  `mode=5`/`mode=84` tagging and the filtering-semantics guidance that
  follows from it.

Context note: M0's PGCR sampling (steps 5-8) deliberately used the account's
highest-playtime character, since it was known to cover all six sampled
activity types. Not a gap — multi-character ingestion is already handled by
`SYSTEM_ARCHITECTURE.md`'s per-character `sync_checkpoint` design.
