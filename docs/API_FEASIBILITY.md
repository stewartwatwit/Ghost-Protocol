# Bungie API Feasibility Assessment

This document records what the **official** Bungie.net API actually supports, based on direct research against:

- [bungie-net.github.io](https://bungie-net.github.io/) — the official generated API reference (built from Bungie's own OpenAPI spec)
- [github.com/Bungie-net/api](https://github.com/Bungie-net/api) — the official API schema repository (OpenAPI/Swagger definitions, README, wiki)
- [bungie.net/7/en-us/Legal/terms](https://www.bungie.net/7/en-us/Legal/terms) — Bungie's own Terms of Use, including the "BUNGIE.NET API TERMS AND CONDITIONS" section

**No claim below is based on what other third-party Destiny apps do.** Every row distinguishes **Confirmed** (found directly in an official source, cited), **Unverified** (not settled by any official source found; third-party/community claims are explicitly labeled as such and never treated as fact), and **Assumption** (a reasonable engineering default chosen *because* official docs are silent, which the Phase A proof-of-concept must validate).

---

## 1. Authentication

| Question | Finding | Confidence |
|---|---|---|
| Grant type | OAuth 2.0 Authorization Code grant only. No other grant type is documented. | **Confirmed** — [OAuth Documentation wiki](https://github.com/Bungie-net/api/wiki/OAuth-Documentation), OpenAPI `securitySchemes.oauth2.flows.authorizationCode` |
| Authorize URL | `https://www.bungie.net/en/OAuth/Authorize` | **Confirmed** |
| Token URL | `https://www.bungie.net/platform/app/oauth/token/` (lowercase, per the OAuth wiki's own example; same URL for refresh, with `grant_type=refresh_token`) | **Confirmed** |
| Token exchange request format | `application/x-www-form-urlencoded` POST body with `grant_type=authorization_code&code=...` (+ `redirect_uri` optionally). For a **confidential client**, the wiki's own example authenticates via `Authorization: Basic base64(client_id:client_secret)` rather than a `client_secret` body param (the body param is documented only as the alternative when not using the header). `scope` must be omitted from this request too. | **Confirmed** — [OAuth Documentation wiki](https://github.com/Bungie-net/api/wiki/OAuth-Documentation) (page dated 2021-01-06; re-verify if the PoC's actual exchange fails) |
| `X-API-Key` on the token-exchange request specifically | The wiki's token-exchange example doesn't show this header (only a separate example `GET /resource` call does). The broader "required on every request" finding above is sourced from the OpenAPI spec and GitHub README, not this specific endpoint's example. **The PoC must test both ways** — this is a real gap between two official sources, not resolved by either alone. | **Unverified (partial)** |
| `scope` request param | Must be **omitted** — scopes are fixed per registered application in the Bungie portal, not chosen per-request. Including it causes a request failure. | **Confirmed** |
| `redirect_uri` | One URL registered per application; must match exactly (including case) if sent at all. Cannot be set dynamically per request. **The registration form rejects `http` outright** ("must be a fully qualified URL using any scheme except http") — undocumented in the official API reference, discovered during actual registration. Local development therefore needs an HTTPS callback listener (e.g. via `mkcert`), not plain HTTP, even on localhost. | **Confirmed** |
| PKCE support | Not mentioned anywhere in official docs. | **Unverified** — treat as unsupported |
| Embedded webviews | Bungie recommends the system browser (SFSafariViewController / Chrome Custom Tabs), not in-app webviews/iframes, for the OAuth redirect. | **Confirmed** |
| API key requirement | A separate `X-API-Key` header (from app registration) is required on **every** request, OAuth or not. Keys and OAuth client IDs are tied together — the key used to request a token must be the same key used on subsequent calls with that token. | **Confirmed** — OpenAPI `securitySchemes.apiKey`, GitHub README FAQ |
| Confidential vs. public client | RFC 6749 §2.1 distinction. **Only confidential clients receive refresh tokens.** Public clients get neither a client secret nor a refresh token. | **Confirmed** — OAuth wiki |
| Access token lifetime | Example in docs: 3,600s (1 hour). Presented as an example response value, not a contractual guarantee. | **Assumption** (plan for it, verify empirically) |
| Refresh token lifetime | Example in docs: 7,776,000s (90 days). Same caveat. | **Assumption** |
| Token revocation | Users can revoke an app's access from their Bungie.net account; this takes effect on the app's next refresh attempt. Expired/invalid tokens return HTTP 401. | **Confirmed** |
| Exact confidential/public toggle location on the registration form | Resolved: the form does offer a Confidential option alongside Public; the user selected Confidential. | **Confirmed** (2026-11-09) |

### Scopes

| Scope | Purpose | Needed for Phase A? |
|---|---|---|
| `ReadBasicUserProfile` | Handle, avatar, basic Bungie.net profile — needed for `GetMembershipDataForCurrentUser` | Yes |
| `ReadDestinyInventoryAndVault` | Read private inventory/vault/currency/vendors/milestones/progression — **the one scope a Destiny 2 analytics app mainly needs** | Yes |
| `ReadUserData` | Notifications, group memberships, recent activity | No |
| `MoveEquipDestinyItems` | Move/equip items (write access) | No (Phase B only) |
| `AdvancedWriteActions` | In-app-confirmed write actions (e.g. socket insertion) | No (Phase B only) |
| Public components (`GetActivityHistory`, `GetPostGameCarnageReport`, public profile/character fields) | No OAuth scope required at all — API key only | N/A |

**Confirmed directly in the OpenAPI spec:** `GetPostGameCarnageReport`'s operation definition has no `security` requirement set — it is public, API-key-only data. Write endpoints (`TransferItem`, `EquipItem`, `EquipLoadout`, etc.) explicitly require `oauth2: [MoveEquipDestinyItems]` or `AdvancedWriteActions` — none of which Phase A needs.

### Rate limits

**No official, documented numeric rate limit exists anywhere** — not in the generated reference, not in the OpenAPI spec, not in the GitHub wiki/README, not in the Terms of Use. The Terms only state Bungie may restrict "excessive or abusive use" at its "sole judgment and discretion," with no number attached. This is a genuine documentation gap, confirmed by three independent research passes.

What **is** officially documented:
- Every response envelope includes a `ThrottleSeconds` field — the one real, confirmed throttle signal. Clients should back off at least this long after a throttled response.
- Specific **write** endpoints (irrelevant to Phase A) have documented per-action throttles, e.g. item move/equip/lock-state actions at 0.1s between actions per user, loadout actions at 1s, `InsertSocketPlugFree` at 2 actions/sec.

**Unverified / community-only:** commonly-cited figures like "~250 requests per 10 seconds" appear only in third-party wrapper library docs and forum posts, never in an official Bungie source. **Do not hard-code these numbers.** Build generic exponential backoff keyed off HTTP 429 and `ThrottleSeconds` instead.

### Terms of Service — relevant constraints

Directly quoted/paraphrased from the live Terms of Use ("BUNGIE.NET API TERMS AND CONDITIONS" section, last updated April 1, 2024, retrieved live this session):

- **Non-commercial only** by default — commercial use (ads, paid tiers, selling access) requires Bungie's prior written permission.
- **Caching permitted**: "You may retain copies of API Data obtained through the Bungie.net API solely for uses permitted under this license" — storing/caching API responses in our own database is allowed for non-commercial use.
- **No reverse engineering / no reproducing or redistributing** API Data beyond the license scope.
- **No reproducing Bungie's sign-in flow** or using extracted session cookies instead of real OAuth.
- **API key confidentiality required.**
- **Brand Feature License** restricts how the Bungie/Destiny name and logos may be displayed — no implying endorsement or affiliation, size/prominence limits.
- Bungie may change, suspend, or rate-limit the API "at any time… without prior notice."

**Planning implication:** Ghost Protocol as scoped (free, personal companion app, no monetization) fits within the non-commercial clause. Any future monetization would require Bungie's written permission — flagged in Open Questions.

---

## 2. Player & Character Information

| Capability | Endpoint | Scope | Confidence |
|---|---|---|---|
| Linked Bungie.net account + cross-save primary | `User.GetMembershipDataForCurrentUser` (`GET /User/GetMembershipsForCurrentUser/`) | `ReadBasicUserProfile` | **Confirmed** |
| Cross-save-aware per-platform accounts | `Destiny2.GetLinkedProfiles` (`GET /Destiny2/{membershipType}/Profile/{membershipId}/LinkedProfiles/`) | API key only | **Confirmed** |
| Character list + class/race/gender/light/playtime/emblem | `Destiny2.GetProfile` with `components=200` (`Characters`) | API key for public fields; `ReadDestinyInventoryAndVault` for some gated fields | **Confirmed** |
| Equipped gear | `components=205` (`CharacterEquipment`) — public | API key only | **Confirmed** |
| Faction/XP/level progression | `components=202` (`CharacterProgressions`) — **requires auth/permission** | OAuth + privacy-dependent | **Confirmed** |

**Cross-save representation:** each `GetLinkedProfiles` card carries `isCrossSavePrimary`, `isOverridden`, `crossSaveOverride`, and `applicableMembershipTypes`. A non-cross-saved account lists only its own type; the cross-save **primary** account lists all overridden types plus its own; an **overridden** (non-primary) account gets an empty `applicableMembershipTypes` list. **Design decision:** use the `isCrossSavePrimary: true` card as the source of truth for `GetProfile` calls; surface the others in the UI as merged/inactive platforms.

**Private-profile / missing-scope behavior:** there is **no account-level 403 or single "is this profile private" flag**. Every returned component is individually wrapped with its own `privacy`/`disabled` flags (`Components.ComponentResponse`), or simply silently omitted from an otherwise-200 response. The official GitHub README states plainly: *"You will not be denied results if you don't have particular scopes, but components will be denied to you,"* and instructs that apps *"should always assume and be able to handle the situation where an expected Component is not returned."* **Design implication:** every field sourced from an optional component must be nullable in the domain model; the UI must render "data unavailable" states per-section, never assume a monolithic all-or-nothing profile response.

**Staleness:** `DestinyProfileResponse` carries `responseMintedTimestamp` / `secondaryComponentsMintedTimestamp` — Bungie's own changelog instructs developers to use these to detect stale cached responses, confirming Bungie itself caches `GetProfile` server-side. Ghost Protocol should surface "as of [timestamp]" rather than implying real-time data.

**Unverified:** whether a separate Bungie.net account-level "hide my data from third-party apps" toggle exists distinct from per-component privacy — not confirmed from the pages retrieved.

---

## 3. Activity History

| Question | Finding | Confidence |
|---|---|---|
| Endpoint | `Destiny2.GetActivityHistory` — `GET /Destiny2/{membershipType}/Account/{destinyMembershipId}/Character/{characterId}/Stats/Activities/` | **Confirmed** |
| Scope | API key only (public) | **Confirmed** (operation has no `security` requirement) |
| Params | `count`, `mode` (DestinyActivityModeType — Raid, Strike, AllPvP, Gambit, Dungeon, TrialsOfOsiris, etc., ~90 enum values), `page` (0-indexed) | **Confirmed** |
| Ordering | `activities[]` is explicitly documented as "most recent activity first" | **Confirmed** |
| **Maximum history depth / retention window** | **No maximum `count`, page cap, or retention window is stated anywhere in official docs.** | **Unverified** — do not promise "full history" in product copy until the PoC empirically tests this (walk pages until an empty page or a known boundary is hit) |
| Per-activity data returned | Lightweight summary only: `period`, activity reference/director hashes, `instanceId`, `mode`/`modes[]`, `isPrivate`, membership type, and a generic per-character `values` stats dictionary. **No weapon data. No other participants. No win/loss standing.** | **Confirmed** (`DestinyHistoricalStatsPeriodGroup` schema has no `extended` field) |

**Architectural consequence:** `GetActivityHistory` alone can drive "list of past activities" and whatever stats live in its generic `values` dict, but **cannot** power weapon analytics, win/loss display, or full-match context — those require a PGCR per activity (Section 4).

---

## 4. Post Game Carnage Reports (PGCR)

| Question | Finding | Confidence |
|---|---|---|
| Endpoint | `Destiny2.GetPostGameCarnageReport(activityId)` — `GET /Destiny2/Stats/PostGameCarnageReport/{activityId}/` | **Confirmed** |
| Auth | Operation has no `security` requirement set in the OpenAPI spec — API key only, no OAuth token needed. (Direct spec inspection; also consistent with, though stronger than, community consensus.) | **Confirmed** |
| Bulk/batch fetch | **Does not exist.** One `activityId` per HTTP call, no alternative. | **Confirmed** |
| Retention / purge policy | No statement anywhere in official docs about how long PGCRs remain fetchable, or whether Bungie purges old ones. | **Unverified** — design for prompt ingestion rather than assuming arbitrary backfill is always possible |
| Per-player fields | `DestinyPostGameCarnageReportEntry`: `standing` (win/loss/placement — its own top-level field), `score`, `player` (identity/class/race/light/clan/emblem), `characterId`, `values` (generic stat dict), `extended` (weapon/scoreboard data) | **Confirmed** (schema structure); exact stat-key strings inside the generic `values` dict are **not** enumerated on the schema pages — **Unverified (partial)** |
| **Weapon usage data** | `extended.weapons[]` → array of `DestinyHistoricalWeaponStats { referenceId, values }`. `referenceId` maps to `DestinyInventoryItemDefinition` for the weapon. Container structure is officially confirmed. | **Confirmed** (structure); exact `values` key names inside it — **Unverified (partial)** |
| **Weapon data coverage across activity types** | No official statement confirms or denies whether `extended.weapons` is populated identically across PvP, PvE, raids, and dungeons. | **Unverified — critical open risk.** Must be empirically sampled across modes during the PoC (Roadmap M0) before Weapon Analytics scope is finalized |
| Subclass / super used | **Not present in any PGCR schema checked** (`DestinyPlayer`, entry, extended data) — only class/race/gender/light/clan/emblem. Ability-kill counts (grenade/melee/super kills) *might* exist inside the generic `values` dict, but the specific keys weren't enumerated in official schema docs. | **Unverified** |

### GetActivityHistory vs. PGCR — what's "free" vs. what costs an extra call

| Available from `GetActivityHistory` (one paginated call per character) | Requires a separate `GetPostGameCarnageReport` call per activity |
|---|---|
| Per-activity summary stats (whatever keys are in `values`) | Full per-player breakdown for **every** participant, not just the queried character |
| Activity reference/director hashes (for manifest lookup) | `standing` (win/loss/placement) |
| Instance ID, mode/modes, isPrivate, period | **Weapon usage** (`extended.weapons`) |
| | `scoreboardValues`, full participant identity/class/light for everyone in the match |

**This is the single most important feasibility finding for the product:** weapon-usage analytics — a headline Phase A feature — is **only** obtainable by fetching and persisting one PGCR per historical activity instance. There is no cheaper official path, and no documented ceiling on how many activities might exist to fetch.

---

## 5. Weapon & Item Information

| Question | Finding | Confidence |
|---|---|---|
| Manifest endpoint | `Destiny2.GetDestinyManifest` → `DestinyManifest` object | **Confirmed** |
| Stable manifest path | `jsonWorldContentPaths` — one full JSON file per locale. Safe to depend on. | **Confirmed** |
| Volatile manifest path | `jsonWorldComponentContentPaths` — split per definition type. Bungie's own docs explicitly warn: **"unsafe and subject to change — do not depend on data in these files staying around long-term."** | **Confirmed** |
| `mobileWorldContentPaths` | No official description found. The common belief it's a zipped SQLite file is **not confirmed** by official docs retrieved. | **Unverified** |
| Manifest update cadence / file size | Not documented anywhere official. | **Unverified** |
| Weapon definition fields | `DestinyInventoryItemDefinition` provides name, icon, weapon type/category, damage type, intrinsic perks, slot/sockets. | **Confirmed** |
| **Aggregate weapon-usage endpoint** | `Destiny2.GetUniqueWeaponHistory` exists and is official, but its own description promises coverage only of **exotic weapons** — not a general substitute for full weapon-usage stats. | **Confirmed (scope-limited)** |
| **Implication** | Full per-weapon analytics across all weapon tiers (not just exotics) has **no cheaper official path** than aggregating PGCRs ourselves over time, as described in Section 4. | — |
| Item instance / inventory components (Phase B) | `ItemInstances`, `ItemSockets`, `CharacterEquipment`, `ProfileInventories` components on `GetProfile` expose owned items, equipped gear, and socketed perks/mods. Sufficient foundation for a future inventory/loadout manager. | **Confirmed** (survey-level; full design deferred to Phase B) |

---

## 6. Feature Feasibility Matrix

| Phase A feature | Official support? | Primary endpoint(s) | Scopes | App-side storage required? | Confidence |
|---|---|---|---|---|---|
| Bungie OAuth sign-in | Yes | OAuth Authorize/Token endpoints | — | Yes (tokens, encrypted) | Confirmed |
| Link Destiny account, show linked platforms incl. cross-save | Yes | `GetMembershipDataForCurrentUser`, `GetLinkedProfiles` | `ReadBasicUserProfile` | Yes | Confirmed |
| Guardian/character summary (class, race, light, playtime, emblem) | Yes | `GetProfile` (`Characters`, `CharacterEquipment`) | API key (+ scope for gated components) | Yes | Confirmed |
| Activity list / history browsing | Yes, with unknown depth | `GetActivityHistory` | API key | Yes | Confirmed structure / Unverified depth |
| Individual activity report (per-player stats, win/loss, weapon usage) | Yes, one call per activity, no bulk | `GetPostGameCarnageReport` | API key | Yes | Confirmed structure / Unverified mode-coverage of weapons |
| Weapon usage analytics (most-used weapons, trends, by activity/character) | Yes, but **self-aggregated from PGCRs only** — no general aggregate endpoint exists | `GetPostGameCarnageReport` + manifest | API key | Yes (the core analytics dataset) | Confirmed, with the mode-coverage caveat above |
| Historical performance trends | Yes, computed from our own stored data | (derived from the above) | — | Yes | Confirmed |
| Automatic sync of new activity data | Yes, via our own scheduler | `GetActivityHistory` + `GetPostGameCarnageReport` | API key | Yes | Confirmed, subject to the undocumented rate-limit gap (build generic backoff) |

| Phase B feature (not built now) | Official support? | Notes |
|---|---|---|
| Inventory viewing | Yes | `GetProfile` with `ProfileInventories`/`CharacterInventories`/`ItemInstances` |
| Equipped loadout viewing | Yes | `CharacterEquipment`, `ItemSockets` |
| Loadout change (write) | Yes | `EquipItem(s)`, `EquipLoadout`, `MoveEquipDestinyItems` scope |
| Full exotic/weapon collection tracking | Partially | `GetUniqueWeaponHistory` (exotics only) + manifest; non-exotic coverage still self-aggregated |
| Triumphs/Collections | Likely, not researched in depth this session | Flagged for Phase B research pass |

## Explicitly out of scope / not assumed available

Per the original brief's constraints, and confirmed by this research finding no evidence otherwise:

- Real-time combat monitoring or any live in-game state.
- Live per-weapon damage tracking (all weapon data is post-activity, via PGCR, after the match ends).
- Unlimited historical activity information (depth is undocumented, not guaranteed unlimited).
- PGCR access for literally every activity instance that ever occurred (no retention guarantee).
- Any statistic not demonstrably present in the schemas cited above.

## Proof-of-concept requirements (must pass before Phase A scope is finalized)

1. Successful OAuth authorization-code round trip (authorize → callback → token exchange → stored, encrypted tokens).
2. `GetMembershipDataForCurrentUser` + `GetLinkedProfiles` against a real, cross-save-enabled account.
3. `GetProfile` character retrieval, including observing at least one component that comes back absent/privacy-gated, to confirm the "silent omission" behavior.
4. `GetActivityHistory` paginated walk for a character with substantial history, to empirically observe whether/where it terminates (confirms or refutes Section 3's "unverified depth" risk).
5. `GetPostGameCarnageReport` fetched for **at least one activity of each of:** a raid, a strike, a PvP match, and (if accessible) Gambit/Trials/Dungeon — specifically to compare `extended.weapons` population across modes (resolves Section 4's critical open risk).
6. Manifest download and successful resolution of at least one `referenceId` from a fetched PGCR into a human-readable weapon name via `DestinyInventoryItemDefinition`.
7. A small sample analytics result computed from the above (e.g., "top 3 weapons used across these N activities") to validate the whole pipeline end-to-end.

Any PoC finding that contradicts an "Unverified" entry above must update this document and, if it changes feasibility, the Product Requirements and Roadmap documents before Phase A implementation proceeds past M0.
