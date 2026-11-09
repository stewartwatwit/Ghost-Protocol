# Ghost Protocol — Deployment Plan (Phase A)

**Constraint confirmed with the user:** $0 / free-tier only budget, with one accepted exception (see Domain Strategy below). Every decision below is chosen to fit that constraint, with the two real architectural consequences (Render's free tier and scheduled jobs; cross-domain session cookies) called out explicitly rather than glossed over.

## Domain Strategy

**Decision (security review, pre-M2):** the frontend (Vercel) and backend (Render) are, by default, different registrable domains. The auth design in `SYSTEM_ARCHITECTURE.md` uses a `SameSite=Lax` session cookie — but `Lax` cookies are only sent on top-level navigations, not on cross-origin `fetch`/XHR calls. Left as-is, the frontend's API calls would never actually carry the session cookie, breaking auth entirely once frontend and backend are on separate default subdomains.

**Resolution:** register one custom domain and put both services under it as siblings — e.g. `app.<domain>` (Vercel) and `api.<domain>` (Render). Same registrable domain means the cookie is same-site, so `SameSite=Lax` works exactly as designed in `SYSTEM_ARCHITECTURE.md`, with no CSRF-token layer needed on top. Both Render and Vercel support custom domains on their free tiers at no platform cost — the only expense is the domain registration itself (typically ~$10–15/yr), which is accepted as the one deliberate exception to the $0 constraint. The alternative (stay on default subdomains, switch to `SameSite=None; Secure`, add explicit CSRF tokens to every state-changing endpoint) was considered and rejected — it keeps the literal $0 target but trades it for ongoing CSRF-token implementation and maintenance burden, which is the worse trade for a personal-scale project.

## Chosen Platforms

| Component | Platform | Tier |
|---|---|---|
| Frontend (React/Vite SPA) | **Vercel** | Hobby (free) |
| Backend (Spring Boot) | **Render** | Free web service |
| Database (PostgreSQL) | **Neon** | Free tier |
| Scheduled sync trigger | **GitHub Actions** | Free scheduled workflow |
| CI/CD | **GitHub Actions** | Free (public/private repo minutes) |

Pricing/limits below were researched directly against each platform's current (November 2026) pricing pages; figures not confirmable on the platform's own page are marked Unverified and corroborated only by third-party sources.

### Vercel (frontend) — Confirmed

Free "Hobby" tier (per Vercel's own docs, confirmed current): 100 GB "Fast Data Transfer," 10 GB "Fast Origin Transfer," 1M CDN requests/month, 4 CPU-hours Active CPU, 1M function invocations, up to 50 custom domains per project, 200 projects. **Restricted to non-commercial, personal use** — consistent with Ghost Protocol's non-commercial scope per the Bungie ToS finding in `API_FEASIBILITY.md` §1. Ample headroom for a personal-scale SPA.

### Neon (database) — Confirmed

Free plan (per Neon's own FAQ, confirmed current): 100 CU-hours/project/month, autoscale up to 2 CU (8GB RAM), 1 GB storage/project (20GB account-wide), **autosuspend after 5 minutes idle** (cannot be disabled; resumes in roughly hundreds of milliseconds), 10 branches/project, 5GB network transfer, no credit card required, no overage billing (writes simply block at the cap rather than auto-charging).

**Implementation requirement:** configure HikariCP with a small `minimum-idle` (or 0) and connection validation on borrow, rather than assuming an always-warm pool — a request after 5+ minutes of inactivity will pay a cold-resume cost on its first query. This is a correctness requirement, not just a performance tweak: a pool that assumes long-lived warm connections will throw on the first query after an autosuspend cycle if not configured for it.

### Render (backend) — Confirmed, with one real gap

Free web service (per Render's own docs, confirmed current): spins down after **15 minutes** of no inbound traffic; wakes on the next request in roughly a minute; 750 free instance-hours/workspace/month (time spent spun-down doesn't count against the hour budget).

**The gap:** Render's free tier has **no free Background Worker and no free Cron Job** — both require a paid instance tier. This directly conflicts with the architecture's need for a recurring ingestion sync (`SYSTEM_ARCHITECTURE.md`'s ingestion flow). An in-process `@Scheduled` job inside the web service simply will not fire reliably while the instance is asleep.

**Zero-cost mitigation (chosen approach):** a GitHub Actions scheduled workflow (`on: schedule`, cron syntax — free for this use on both public and private repos within GitHub's free Actions minutes) calls a protected backend endpoint (e.g. `POST /api/internal/sync/trigger`, guarded by a shared secret stored in GitHub Actions secrets and Render's environment variables) roughly every 20 minutes. This HTTP call:
1. Wakes the sleeping Render instance (the inbound request itself counts as traffic).
2. Hits an endpoint that **enqueues** `sync_job` rows and returns immediately (per the job-queue design in `SYSTEM_ARCHITECTURE.md`) rather than blocking on the full sync — so job execution continues in-process after the triggering HTTP request completes, without needing a separate worker process.

**Endpoint hardening (security review, pre-M2):** the shared secret must be sent only via a request header (never a query string — query strings land in access logs and `Referer` headers), compared using a constant-time comparison (not `String.equals`, to avoid a timing side-channel), and the endpoint must apply basic failure-rate throttling/lockout on repeated bad-secret attempts. The GitHub Actions workflow itself must never echo the secret in a log step (e.g. no `run: curl ... $SECRET` without `::add-mask::` or equivalent) — workflow logs are visible to anyone with read access to the repo.

This keeps the entire stack at $0/month (plus the one accepted domain-registration cost noted above). The tradeoff, made explicitly rather than hidden: sync cadence is bounded by the external ping interval (not a true background cron), and a cold-started instance's first post-wake request is slower — both acceptable for a personal-scale Phase A deployment.

**Alternative considered and rejected for now:** Render's paid Starter tier (~$7/month, figure corroborated by multiple third-party sources but not confirmed on Render's own pricing page — Unverified exact price) would remove the sleep behavior entirely. Revisit if the GitHub Actions workaround proves unreliable in practice; this is noted in `OPEN_QUESTIONS.md`.

### Comparison platforms considered (for the record, not chosen)

- **AWS** (App Runner + RDS): no meaningful free tier for a new account today (pre-July-2025 accounts had a 12-month RDS free tier; new accounts largely don't qualify). Expect real cost (~$15+/month for RDS alone) from day one — rejected given the $0 budget constraint.
- **Railway**: no permanent free plan anymore — a 30-day $5 trial credit, then a Free plan capped at $1/month usage credit; cheapest real paid plan ~$5/month. Rejected for the same reason.
- **Fly.io**: free tier removed; new accounts get a small trial-credit buffer only; cheapest always-on VM ~$2.19/month, managed Postgres ~$38/month. Cheaper raw compute than Render's paid tier but no genuine free always-on option and more ops overhead (metered billing, manual VM management) — rejected in favor of Render + the GitHub Actions workaround, which keeps the stack fully free.

## CI/CD (GitHub Actions)

Pipeline stages, each a separate job/step:
1. Backend compilation (Maven).
2. Backend unit tests (JUnit 5, Mockito).
3. Backend integration tests (Testcontainers + PostgreSQL — never against a developer's local DB).
4. Frontend tests (Vitest/Jest + React Testing Library, or equivalent).
5. Frontend production build (Vite).
6. Docker image build (backend).
7. Dependency vulnerability scanning (Dependabot or Renovate for both Maven and npm dependencies, plus an OWASP dependency-check/`npm audit` CI step) — flags known-vulnerable dependencies automatically rather than relying on manual review.
8. Deployment to Render (backend) and Vercel (frontend) — triggered only on merge to `main`, only after all prior stages pass.

No deployment happens on a failing pipeline. No auto-merge of PRs — merges remain a human decision per the project's working agreement.

## Demo Mode

A publicly reachable demo route/flag seeded with **synthetic fixture data** — not real user data, not a copy of any real user's actual Bungie history. Fixture data is generated/maintained as part of the `ingestion`/`activity` test-fixture set already needed for integration testing (see `DEVELOPMENT_ROADMAP.md` M8), so it doesn't require a separate data-generation effort. Demo mode never executes the real OAuth flow and never touches the production Bungie-linked-account tables.

## Secrets Management

The following never appear in source control, and are injected only via each platform's own secret store (Render environment variables, Vercel environment variables, GitHub Actions encrypted secrets):
- Bungie OAuth client ID and client secret.
- Bungie `X-API-Key`.
- The database connection string (Neon).
- The AES encryption key used for token-at-rest encryption.
- The shared secret guarding the `/api/internal/sync/trigger` endpoint.

## Environment Configuration

Three environments: `local` (Docker Compose, local Postgres — not live Neon, so local development doesn't consume the free-tier quota or risk hitting another developer's data), `staging`/`demo` (seeded with synthetic data only), and `production` (real Neon database, real Render service, real Vercel deployment).

## Health Checks, Logging, Monitoring

- A `/actuator/health`-style endpoint (Spring Boot Actuator) for Render's own health-check polling.
- Structured application logging (JSON logs), with the scrubbing rules defined in `SYSTEM_ARCHITECTURE.md`'s Security Boundaries section strictly enforced in the logging configuration, not left to developer discipline alone.
- Basic error visibility: at minimum, failed `sync_job`/`sync_checkpoint` rows are queryable (even via a simple authenticated admin endpoint) so ingestion failures are visible without needing to tail logs.

## Recovery and Rollback

- Flyway migrations are forward-only in normal operation; a bad migration is fixed with a new migration, not by editing/reverting an already-applied one.
- Render deployments roll back to the previous successful deploy on a failed health check (Render's built-in behavior).
- Neon's branching capability (confirmed on the free tier — 10 branches/project) is available for testing a risky migration against a branched copy of production data before applying it live, though Phase A's migration strategy doesn't require this by default.

## Open deployment risks (carried into `OPEN_QUESTIONS.md`)

- The GitHub Actions ping-based sync trigger is a workaround, not a guarantee — if sync reliability turns out to matter more than the $0 constraint in practice, moving to Render's paid Starter tier is the documented fallback.
- Render's free-tier exact CPU allocation and Neon/Render free-Postgres-specific expiry windows (where applicable) were corroborated by third-party sources but not confirmed on the platforms' own pricing pages at time of writing — re-verify before final launch.
