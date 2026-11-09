# Ghost Protocol — System Architecture (Phase A)

## Architecture Overview

Ghost Protocol Phase A is a **modular monolith**: a single Spring Boot (Java 21) deployable unit, organized into Java packages that act as modules with an enforced, one-directional dependency graph, paired with a React/TypeScript/Vite frontend. One database (PostgreSQL via Neon). No microservices, no message broker — both are explicitly avoided per the project's own constraints, since nothing about Phase A's scale justifies that complexity yet.

The architecture exists to solve one recurring risk surfaced throughout `API_FEASIBILITY.md`: Bungie's API shapes are loosely-typed, change over time, and are only partially documented. Every module boundary below is designed so that a Bungie API surprise is a one-module fix, not a system-wide one.

## Module Boundaries

```
        shared
          ^
          |
   +------+------+------+-----------+
   |      |      |      |           |
 auth  bungie  guardian activity  weapon
          ^        ^       ^         ^
          |        |       |         |
          +--------+-------+---------+
                       |
                  ingestion
                       |
                   analytics
```

A module may depend only on a module below/beside it in this diagram, never one that depends back on it.

### `shared`
Cross-cutting value types (`BungieMembershipType` enum, UTC `Instant` wrappers), common exceptions, pagination/result wrappers, the `ExternalId` pattern used by every entity that mirrors a Bungie ID. Contains **no** domain entities, **no** HTTP client code, **no** business logic. Depends on nothing internal.

### `bungie`
The **only** module permitted to speak HTTP to `bungie.net`. Owns:
- OAuth token exchange/refresh HTTP calls.
- `X-API-Key` header injection on every outbound request.
- All raw request/response DTOs (Jackson-annotated, mirroring Bungie's JSON exactly — including every nullable/optional component field).
- Generic retry/backoff logic keyed off HTTP 429 and the `ThrottleSeconds` envelope field.
- Manifest file download/parsing.

Exposes a small set of **mapper-producing service methods** (e.g. `BungieProfileClient.fetchProfile(destinyMembershipId, components) -> ProfileSnapshot`) that return internal-shaped, already-translated records — never the raw DTO. Every Bungie DTO class stays package-private to `bungie.dto`. This is the single most important seam in the system: a Bungie schema change is a one-module fix, and our persisted schema is never dictated by Bungie's JSON shape.

Must not know about our `User` entity, our database, or domain concepts like "Character" vs. "Guardian" — it only knows Bungie's vocabulary. Depends on `shared` only.

### `auth`
Spring Security configuration, the `User` entity, our own session/cookie issuance, OAuth authorization-code exchange *orchestration* (decides when to call `bungie`'s token-exchange method), encrypted-at-rest storage of the Bungie token pair, the refresh-ahead-of-expiry scheduler, and the Bungie-identity-to-internal-`User` mapping. Must not contain activity/weapon/analytics logic or expose raw Bungie tokens to any controller response. Depends on `bungie`, `shared`.

### `guardian`
The account/character domain: `LinkedDestinyAccount` (cross-save-aware platform cards) and `Character` entities, plus the service that calls `bungie` for `GetLinkedProfiles`/`GetProfile` and maps snapshots into these entities — including "component may be absent" handling (every optional-component field is nullable in the domain model, never assumed present, per `API_FEASIBILITY.md` §2). Must not fetch/store activity or weapon data. Depends on `auth` (to know which `User` owns the account), `bungie`, `shared`.

### `activity`
`Activity` and `ActivityParticipant` entities; the service that calls `bungie` for `GetActivityHistory`/`GetPostGameCarnageReport`; per-activity idempotent upsert logic; `ActivityWeaponUsage` persistence (resolving a weapon hash to a display name is delegated to `weapon`). Must not compute trends/rollups (that's `analytics`) or parse manifest files itself. Depends on `guardian` (FK to `Character`), `weapon` (hash resolution — a one-directional read dependency), `bungie`, `shared`.

### `weapon`
`WeaponDefinition` (manifest-cached item definitions) and the manifest sync job (download `jsonWorldContentPaths`, diff version, upsert definitions — per `API_FEASIBILITY.md` §5's finding that this path is the stable one, unlike `jsonWorldComponentContentPaths`). Exposes lookup-by-hash and a weapon-usage-aggregation read API that `analytics` consumes. Must not fetch activity history or depend on `activity`'s write path — `activity` reads from `weapon`, never the reverse. Depends on `bungie`, `shared`.

### `ingestion`
Scheduling (job-queue model, not a bare `@Scheduled` call straight into business logic), per-character sync orchestration, the `SyncCheckpoint` entity and resume logic, job-level retry/backoff, and the call sequence: refresh characters → pull new activity-history pages → fetch PGCRs for new instance IDs → ensure weapon definitions exist. This is the one module allowed to call across `guardian`, `activity`, and `weapon` as an orchestrator — it contains no domain logic of its own. Depends on `guardian`, `activity`, `weapon`, `bungie`, `shared`.

### `analytics`
Derived/rollup read models (weapon-usage-over-time, win-rate trends, K/D and KAD — (kills + assists) / deaths — by mode), computed on-demand via queries against `activity`/`weapon` tables. K/D and KAD are never stored columns — they're calculated from the promoted `kills`/`deaths`/`assists` columns on `activity_participant` at query time, per `DATABASE_DESIGN.md`. **Never writes** to `activity` or `weapon` tables. Depends on `activity`, `weapon`, `shared`.

**Phase A decision:** analytics are pure on-demand queries — no precomputed/materialized rollup tables yet. Premature caching of derived stats would add invalidation complexity before real query patterns are known; defer materialized rollups to Phase B.

## Authentication Flow

**Decision: scheduled refresh-ahead-of-expiry, not lazy-on-401.** Bungie's documented ~1-hour access-token lifetime means a background scheduler refreshing at ~80% of the stored expiry keeps the unattended `ingestion` scheduler from ever hitting a stale token mid-run, when there's no user present to retry. Lazy-on-401 is kept only as a defensive fallback (one forced-refresh retry) — never the primary mechanism.

1. **Frontend initiates:** the browser is redirected to `https://www.bungie.net/en/OAuth/Authorize?client_id=...&response_type=code&state=<csrf-nonce>&redirect_uri=...`. The `state` nonce is generated and stored server-side by a backend endpoint the frontend calls first (`POST /api/auth/oauth/start`) — never generated in JS, so it can't be tampered with.
2. **Bungie redirects back** to a **backend** callback endpoint (`GET /api/auth/oauth/callback?code=...&state=...`) — never a frontend route, so the authorization code is never exposed to browser JS.
3. **Backend validates** `state`, then exchanges the code at `https://www.bungie.net/Platform/App/OAuth/token/` using `client_id` + `client_secret` (confidential client) + `X-API-Key`. This HTTP call lives in `bungie`; `auth` calls it.
4. **Backend receives** `access_token`, `refresh_token`, `expires_in`. Both tokens are encrypted (AES-GCM) and stored in a backend-only table, keyed to the internal `User`. **Tokens never leave the backend process boundary** — not in a cookie, not in a response body, not in a JWT claim.
5. **Identity mapping:** backend calls `GetMembershipDataForCurrentUser` via `bungie` to get the Bungie membership ID + `primaryMembershipId`. `auth` looks up (or creates) the internal `User` by `bungie_membership_id` — our `User.id` is always our own surrogate key.
6. **Backend issues its own session:** a server-side session (opaque session ID, `HttpOnly`/`Secure`/`SameSite=Lax` cookie) — chosen over a stateless JWT because a server-side session can be revoked immediately on logout/unlink, whereas a JWT would need its own server-side blocklist anyway, reintroducing the same state. The cookie carries zero Bungie data.
7. **Backend redirects** the browser to the frontend app, which never sees a Bungie token — only its own session cookie, used against our own REST API.
8. **Scheduled refresh:** a background job scans the token table for rows nearing expiry and refreshes each via the refresh-token grant. If a refresh fails because the refresh token itself is expired/revoked, the account is flagged `needs_reauth = true` and surfaced to the frontend (per `PRODUCT_REQUIREMENTS.md` §7) — it never silently retries forever.
9. **Every outbound Bungie call** attaches the current valid access token (Bearer) **and** the `X-API-Key` header unconditionally — the API key is required regardless of endpoint auth requirements.

## Data Ingestion / Sync Flow

1. **Trigger:** the `ingestion` scheduler enqueues a `sync_job` row per character on a tunable interval — not a direct call from the cron trigger into business logic, so job execution is observable and retryable independently of the trigger itself. (On the chosen free-tier deployment, this trigger is an external GitHub Actions ping — see `DEPLOYMENT_PLAN.md`.)
2. **Checkpoint load:** the worker loads (or creates) the `SyncCheckpoint` row for that character — last successfully processed `period`, last page scanned, and a `status` (`idle`/`in_progress`/`error`).
3. **Fetch new activity pages:** walk `GetActivityHistory` from `page=0` forward until hitting an already-known `instanceId` **or** the checkpoint's last-seen `period`, whichever comes first — bounding the walk without assuming a fixed depth, since `API_FEASIBILITY.md` §3 found none documented. *(Flagged sensitive to the PoC's findings: if the API has a hard depth cap, "walk until seen" still works, but initial backfill for long-tenured accounts may simply never reach older activities — that's a product-copy constraint, not a code bug.)*
4. **Checkpoint update per page, before PGCR fetch:** committed immediately after each page read, so a crash mid-run resumes from the last durably-recorded page rather than page 0.
5. **Enqueue PGCR fetch per new instanceId:** every newly discovered activity is inserted immediately as a lightweight `Activity` row (`pgcr_status = summary_only`) before any PGCR call — so a downstream PGCR failure never causes re-walking already-captured history pages.
6. **PGCR fetch loop (one call per instanceId — no batch endpoint exists):** on success, upsert the full `Activity` row and all `ActivityParticipant`/`ActivityWeaponUsage` rows for **every** participant in that match (not just our user — this is shared data; other Ghost Protocol users in the same match benefit, and it's required context for displaying who won). Mark `pgcr_status = complete`.
7. **Partial/missing weapon data:** if `extended.weapons` is absent or empty for a participant, record `ActivityParticipant.weapon_data_status = 'absent'` — explicitly distinct from inserting zero-kill `ActivityWeaponUsage` rows. This directly operationalizes the "no data vs. zero" requirement from `PRODUCT_REQUIREMENTS.md` §5, and exists because `API_FEASIBILITY.md` §4 found the cross-mode coverage of this field unconfirmed.
8. **Idempotency:** `Activity.bungie_instance_id` is unique and is the natural key — re-running a sync or racing workers upserts rather than duplicates. `ActivityParticipant` is unique on `(activity_id, character_id)`; `ActivityWeaponUsage` on `(activity_participant_id, weapon_definition_id)`.
9. **Retry/backoff:** the `bungie` HTTP client wraps every call with exponential backoff + jitter, triggered by HTTP 429 or a non-zero `ThrottleSeconds`, with the actual delay being `max(computed_backoff, ThrottleSeconds)`. At the `ingestion` level, a PGCR fetch that exhausts low-level retries marks that one activity `pgcr_status = retry_pending` (with an attempt counter) and moves on — one bad activity never blocks the rest of a character's backlog. A separate sweep job retries `retry_pending` activities on its own schedule, capping at a max-attempts threshold after which it's marked `failed`.
10. **Analytics:** no synchronous rollup call at the end of a job — `analytics` queries `activity`/`weapon` tables on demand (see module description above).
11. **Checkpoint completion:** on worklist drain, `status = 'idle'`, `last_synced_at = now()` (UTC). On unhandled exception, `status = 'error'` with the error recorded, but the already-committed page/period cursor is left intact so the next run resumes correctly.

**Phase B extensibility:** this checkpoint + worklist + idempotent-upsert pattern generalizes directly to inventory/loadout polling (a different endpoint, same shape) without restructuring `ingestion`'s job model.

## Database Interactions

Full schema in `DATABASE_DESIGN.md`. In summary: every Bungie-external ID is stored in its own `UNIQUE`-constrained column, never as the sole primary key; all timestamps are `TIMESTAMPTZ` in UTC; static manifest-derived tables are physically separate from user-specific tables so a manifest refresh never touches user data.

## External Dependencies

- **Bungie.net API** — the sole external data source. Treated as unreliable-by-design: outages, undocumented rate limits, and schema drift are all expected and handled via the `bungie` module's isolation + retry logic, never assumed away.
- **Neon (PostgreSQL)** — primary datastore. Free-tier autosuspend behavior (5 min idle) requires connection-pool configuration tolerant of cold-resume latency (see `DEPLOYMENT_PLAN.md`).
- **Render** — backend hosting. Free-tier sleep-on-idle behavior directly shapes the ingestion trigger design (external ping, not a pure in-process cron — see `DEPLOYMENT_PLAN.md`).

## Security Boundaries

- **Authorization:** every controller endpoint under `/api/characters/**`, `/api/activities/**`, `/api/weapons/usage/**` resolves the acting `User` from the session (never from a client-supplied ID) and verifies ownership (`character.linked_destiny_account.user_id == session.userId`, checked transitively for nested resources) in a shared service-layer guard — not only at the controller, so it can't be bypassed by a future internal caller. Other participants' rows (teammates/opponents present because they were in the same match) are only ever readable in that limited "who else was in this activity" context, never as a route into browsing a stranger's full history.
- **`Activity` is a shared resource, not an owned one.** Unlike `Character`, an `Activity` row is referenced by every participant's match, including users who never linked a Ghost Protocol account. The authorization guard for `/api/activities/{id}` must specifically check "does the requesting user have an `ActivityParticipant` row for this activity" (existence of that join row), **not** "does this activity exist" — an existence-only check is an easy accidental IDOR (any authenticated user could read any other user's activity report by guessing/incrementing an ID). An unauthorized or nonexistent ID must return the same response (404), so the endpoint never discloses which IDs are valid to a user who doesn't own them.
- **Never logged:** Bungie access/refresh tokens (even their encrypted form), the `X-API-Key`, session cookie values, and encryption key material. Structured logging scrubs field names matching `*token*`/`*secret*`/`*apiKey*`; full request/response bodies for `bungie`-module calls are never logged above TRACE, and TRACE is disabled outside local development.
- **Unlink/delete cascade:** unlinking a `LinkedDestinyAccount` cascades to its `Character` rows, which cascades to their `ActivityParticipant`/`ActivityWeaponUsage`/`SyncCheckpoint` rows. The shared `Activity` row itself is only removed once it has zero remaining participants (an explicit cleanup step, not a blind cascade, since other users' legitimate references must survive). Full user deletion cascades the same way down the chain and revokes the session.
- **CORS:** explicit origin allow-list containing only the known frontend domain (see `DEPLOYMENT_PLAN.md`'s Domain Strategy) with credentials enabled — never a wildcard (`*`) origin, which would be incompatible with credentialed requests anyway.
- **State-changing endpoints use non-`GET` HTTP verbs**, always — this is what lets `SameSite=Lax` actually block cross-site forgery: `Lax`'s top-level-navigation exception only ever applies to safe (`GET`) requests, so an account-disconnect or manual-sync-trigger action exposed as `GET` would defeat that protection entirely.
- **Spring Boot Actuator** exposes only `/actuator/health` publicly. Every other Actuator endpoint (`/env`, `/beans`, `/heapdump`, `/mappings`, `/configprops`, etc.) is disabled or requires admin-level authentication — left at defaults, Actuator is a well-known source of accidental secret/environment-variable disclosure.
- **No internal error detail in responses:** Spring Boot's default error page/stack-trace behavior is disabled in production (`server.error.include-stacktrace=never`, a custom error-body mapper) so an unhandled exception never leaks class names, SQL, or file paths to the client.
- **Baseline response headers** (`Content-Security-Policy`, `X-Content-Type-Options: nosniff`, `Referrer-Policy`, `Strict-Transport-Security`) are set on every backend response and at the Vercel edge for the frontend — not left to framework defaults.
- **Untrusted display-name rendering:** Bungie-sourced display names — both the user's own and other players' from PGCR data — are untrusted strings. The frontend renders them only through normal JSX text interpolation (which auto-escapes); `dangerouslySetInnerHTML` or any raw-HTML path is never used for this data.
- **Manifest download hardening:** the `weapon` module's manifest fetch from Bungie's CDN (`jsonWorldContentPaths`) enforces HTTPS, a byte-size cap, and a request timeout — it's an unauthenticated bulk download, and nothing today bounds an unexpectedly large or slow response.

## Phase B Extension Points

- **`weapon` module:** generalizes to a broader `item_definition` table (armor, mods, ornaments) using the same hash-as-external-key pattern; a future `ItemInstance` table would FK into it exactly as `ActivityWeaponUsage` FKs into `WeaponDefinition` today.
- **`guardian` module:** `Character` already anchors a future loadout table; Phase B inventory sync reuses the same `ingestion` checkpoint/worklist pattern as a new job type.
- **`activity` module:** the `pgcr_status` state machine and retry-sweep pattern generalizes to any other "one-call-per-instance, no-batch-endpoint" Bungie resource Phase B might add.
- **`analytics` module:** Phase B materialized-rollup tables would live entirely inside `analytics`, reading from `activity_weapon_usage`/`weapon_definition` without requiring any change to how those tables are written.
