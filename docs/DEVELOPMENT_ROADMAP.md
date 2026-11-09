# Ghost Protocol — Development Roadmap

This roadmap follows the milestone shape from the original project brief, adjusted in two places by this session's Bungie API research (`API_FEASIBILITY.md`): M0 is expanded into a concrete validation checklist for every "Unverified" finding, and M1 gains an explicit manual step the user must perform.

## M0 — Research and API Feasibility *(this phase — now complete, pending the PoC)*

- [x] Review official Bungie API documentation and repository.
- [x] Investigate authentication, scopes, rate limits, ToS.
- [x] Investigate player/character retrieval, cross-save handling, privacy behavior.
- [x] Investigate activity history and PGCR structure, especially weapon-data coverage.
- [x] Investigate manifest/weapon definitions and Phase B item/inventory feasibility.
- [x] Document findings in `API_FEASIBILITY.md`, distinguishing Confirmed / Unverified / Assumption.
- [ ] **Build the minimal proof-of-concept** (first actual code written, ahead of full implementation) that:
  1. Completes a real OAuth authorization-code round trip against a registered Bungie application.
  2. Retrieves a linked Destiny account via `GetLinkedProfiles`, including a cross-save-enabled account if available.
  3. Retrieves Guardian characters via `GetProfile`.
  4. Retrieves recent activity history via `GetActivityHistory` and walks pages far enough to observe whether/where it terminates.
  5. Retrieves at least one PGCR per activity type sampled: raid, strike, PvP, and (if accessible) Gambit/Trials/Dungeon.
  6. Parses each PGCR and specifically records whether `extended.weapons` is populated for that mode.
  7. Resolves at least one weapon `referenceId` via the manifest and produces one small sample analytics result (e.g., top weapons across the sampled activities).
- [ ] **Update `API_FEASIBILITY.md` and `PRODUCT_REQUIREMENTS.md`** with the PoC's actual findings before proceeding past this milestone. If any Unverified item turns out more limited than hoped (e.g., weapon data missing in some modes, or history depth capped lower than expected), the Weapon Analytics and Activity Explorer requirements are adjusted here, not discovered mid-build.

**M0 does not proceed to M1 until the user has reviewed the PoC's findings.**

## M1 — Architecture and Development Environment

- [ ] **Manual step (user-performed): register the Bungie application** at `bungie.net/en/Application` — confidential OAuth client, redirect URI pointing at the backend's callback route, scopes `ReadBasicUserProfile` + `ReadDestinyInventoryAndVault`. Claude cannot perform this step; it requires the user's own Bungie.net login.
- [ ] **Manual step (user-performed): register a custom domain** and configure `app.<domain>` (Vercel) + `api.<domain>` (Render) per `DEPLOYMENT_PLAN.md`'s Domain Strategy — required so the session cookie is same-site between frontend and backend. Claude cannot purchase a domain on the user's behalf.
- [ ] Establish the Spring Boot project structure matching the module boundaries in `SYSTEM_ARCHITECTURE.md`.
- [ ] Configure Spring Boot, Spring Security, Spring Data JPA/Hibernate, Maven.
- [ ] Configure the React + TypeScript + Vite frontend project, Tailwind CSS.
- [ ] Configure Neon PostgreSQL connection (dev + prod), with HikariCP settings tolerant of free-tier autosuspend cold-resume (see `DEPLOYMENT_PLAN.md`).
- [ ] Configure Flyway and commit `V1__initial_schema.sql` per `DATABASE_DESIGN.md`.
- [ ] Configure Docker Compose for local development (backend + frontend + local Postgres, so local dev doesn't depend on live Neon).
- [ ] Establish the development workflow: branch strategy, PR review expectations, how `docs/` get updated alongside code changes.

## M2 — Authentication

- [ ] Implement the backend OAuth flow exactly as designed in `SYSTEM_ARCHITECTURE.md` (start/callback endpoints, state-nonce validation, confidential-client token exchange, encrypted token storage).
- [ ] Implement account linking (`User` creation/lookup by `bungie_membership_id`).
- [ ] Implement the scheduled refresh-ahead-of-expiry job and the `needs_reauth` fallback path.
- [ ] Implement backend session issuance (server-side session, `HttpOnly` cookie) and basic session-based auth on API routes.
- [ ] Tests: full OAuth round-trip against mocked Bungie token endpoints; session issuance/validation; refresh-ahead scheduling logic; `needs_reauth` transition.

## M3 — Guardian Profiles

- [ ] Implement `guardian` module: `GetLinkedProfiles` + `GetProfile` integration, cross-save-primary resolution, `LinkedDestinyAccount`/`Character` persistence.
- [ ] Implement graceful handling of absent/privacy-gated components (no assumption that any component is always present).
- [ ] Implement Guardian profile REST endpoints, scoped to the requesting session's own account.
- [ ] Tests: cross-save card resolution against representative fixture responses; absent-component handling; authorization (user A cannot read user B's characters).

## M4 — Activity Ingestion

- [ ] Implement `activity` module's `GetActivityHistory` + `GetPostGameCarnageReport` integration.
- [ ] Implement the idempotent upsert logic (`bungie_instance_id` unique constraint, `ON CONFLICT` handling).
- [ ] Implement `weapon_data_status` tracking (`present`/`absent`/`unknown`).
- [ ] Implement the `ingestion` module's checkpoint + worklist pipeline, including the page-by-page checkpoint commit and the PGCR retry/backoff sweep.
- [ ] Tests: duplicate-activity prevention under concurrent/re-run syncs; checkpoint resume after a simulated crash; retry/backoff behavior against mocked 429/`ThrottleSeconds` responses; partial-PGCR-failure isolation (one bad activity doesn't block the rest of the backlog).

## M5 — Historical Data and Analytics

- [ ] Implement the `weapon` module: manifest download/diff/upsert, hash-to-definition lookups.
- [ ] Implement `analytics` module's on-demand query services: weapon usage over time, by activity type, by character; performance trends; completion history.
- [ ] Ensure every analytics query explicitly distinguishes "no data" from "zero" per `PRODUCT_REQUIREMENTS.md` §5–6.
- [ ] Tests: analytics calculations against known fixture datasets (known inputs → known expected aggregates); missing-data vs. zero-value rendering logic.

## M6 — Frontend Dashboard

- [ ] Implement navigation shell and the six pages from `PRODUCT_REQUIREMENTS.md`: Dashboard, Guardian Profile, Activity Explorer, Activity Report, Weapon Analytics, Account Settings.
- [ ] Implement loading/error/empty states consistently across all pages.
- [ ] Implement charts (Recharts or equivalent) with explicit units, time ranges, and accessible color contrast.
- [ ] Implement the "data unavailable" vs. "confirmed zero" distinction in the UI wherever analytics or profile data can be absent.
- [ ] Tests: component rendering for each state (loading/error/empty/populated); API integration tests against a mocked backend; chart rendering with representative fixture data.

## M7 — Synchronization and Reliability

- [ ] Wire the `ingestion` scheduler to its production trigger (the GitHub Actions free-tier workaround from `DEPLOYMENT_PLAN.md`, since Render's free tier has no native cron/background worker).
- [ ] Add monitoring/visibility into `sync_checkpoint`/`sync_job` state (even a simple admin-only status endpoint for Phase A).
- [ ] Harden error handling across the ingestion pipeline based on real-world behavior observed since M4.
- [ ] Load-test the ingestion pipeline against a larger synthetic history (simulating a long-tenured account) to validate the checkpoint/backoff design under volume, not just unit-test fixtures.

## M8 — Testing and Security

- [ ] Expand unit and integration test coverage across all modules.
- [ ] Introduce Testcontainers-based integration tests against real PostgreSQL (not a developer's local DB, not SQLite-in-memory) for every repository/query that matters.
- [ ] Security review: confirm no token/secret ever appears in logs; confirm every user-data endpoint enforces ownership; confirm account-unlink/delete cascades behave as designed in `DATABASE_DESIGN.md`.
- [ ] Explicitly test failure scenarios: Bungie API outage/timeout, malformed/partial API responses, expired refresh token, database connection failure mid-sync.

## M9 — Deployment

- [ ] Stand up production Neon database, Render backend service, Vercel frontend deployment per `DEPLOYMENT_PLAN.md`.
- [ ] Configure GitHub Actions CI/CD: backend build/test, frontend build/test, Docker image build, deploy on merge to `main`.
- [ ] Implement demo mode with synthetic fixture data, isolated from any real-user code path.
- [ ] Configure health checks, structured logging, and basic error monitoring.
- [ ] Write/finalize user-facing documentation (updated README, any setup docs needed for future contributors).

## M10 — Phase B (not started until Phase A is stable and deployed)

- [ ] Re-run API feasibility research specifically for inventory/item-instance endpoints and loadout-write endpoints before committing to a Phase B scope (this session's research only surveyed these at a high level — see `API_FEASIBILITY.md` §5).
- [ ] Evaluate and prioritize companion functionality: inventory management, loadout creation/comparison, collections/triumphs tracking.
- [ ] Extend the schema/modules per the Phase B extension points already identified in `SYSTEM_ARCHITECTURE.md` and `DATABASE_DESIGN.md` — no speculative tables are built until a specific Phase B feature is approved.

---

**Gate between every milestone:** per the project's own working agreement, each milestone's implementation is reviewed (tests run, code reviewed, docs updated) before moving to the next — this roadmap is a sequence of approval gates, not a schedule to rush through.
