# Ghost Protocol — Database Design (Phase A)

Target: PostgreSQL (Neon, managed). Migrations via Flyway.

## Conventions

- Every table has an internal surrogate primary key (`BIGSERIAL`), **never** a Bungie-supplied ID as the sole PK — Bungie IDs are numeric/string values we don't control the issuance or uniqueness guarantees of across contexts.
- Every Bungie-external ID is stored in its own column with a `UNIQUE` constraint.
- Every timestamp column is `TIMESTAMPTZ`, stored in UTC (`created_at`/`updated_at` on every table, plus domain timestamps like `period`).
- Static, manifest-derived tables (`weapon_definition`, `manifest_version`) are physically separate from user-specific tables, so a manifest refresh is a bulk operation untangled from user-data lifecycle.
- No table stores raw, unprocessed Bungie JSON payloads indefinitely — only the normalized minimum actually queried. Full PGCR/activity-history JSON is discarded after mapping; it's re-fetchable from Bungie by `bungie_instance_id` on demand (modulo the undocumented PGCR-retention risk noted in `API_FEASIBILITY.md` §4 — mitigated by prompt ingestion, not by raw-payload hoarding).

## Entity Relationship Overview

```
app_user 1───1 bungie_oauth_token
app_user 1───N linked_destiny_account
linked_destiny_account 1───N character
character 1───N activity_participant N───1 activity
activity_participant 1───N activity_weapon_usage N───1 weapon_definition
character 1───1 sync_checkpoint
character 1───N sync_job
manifest_version (standalone, versions weapon_definition as a set)
```

`activity` is shared across all participants — one row per Bungie activity instance, never duplicated per user.

## Tables

### `app_user`
| Column | Type | Notes |
|---|---|---|
| `id` | `BIGSERIAL PK` | |
| `bungie_membership_id` | `VARCHAR UNIQUE NOT NULL` | Bungie.net membership ID — the identity anchor |
| `display_name` | `VARCHAR` | |
| `status` | `VARCHAR` | `active`, `deactivated` |
| `created_at`, `updated_at`, `last_login_at` | `TIMESTAMPTZ` | |

### `bungie_oauth_token`
| Column | Type | Notes |
|---|---|---|
| `id` | `BIGSERIAL PK` | |
| `user_id` | `BIGINT UNIQUE NOT NULL FK -> app_user.id` | 1:1 with `app_user`, kept as its own table so token data can have tighter row-level access/audit controls and be trivially excluded from general `app_user` reads |
| `access_token_encrypted` | `BYTEA NOT NULL` | AES-GCM encrypted |
| `refresh_token_encrypted` | `BYTEA NOT NULL` | AES-GCM encrypted |
| `access_token_expires_at` | `TIMESTAMPTZ NOT NULL` | |
| `refresh_token_expires_at` | `TIMESTAMPTZ` | nullable — docs give an example-only ~90-day figure, not a guarantee |
| `needs_reauth` | `BOOLEAN DEFAULT false` | set when a refresh attempt fails because the refresh token itself is dead |

Index: `access_token_expires_at` (supports the refresh-ahead scheduler's scan query).

### `linked_destiny_account`
| Column | Type | Notes |
|---|---|---|
| `id` | `BIGSERIAL PK` | |
| `user_id` | `BIGINT NOT NULL FK -> app_user.id` | |
| `destiny_membership_id` | `VARCHAR NOT NULL` | Bungie's per-platform membership ID |
| `membership_type` | `SMALLINT NOT NULL` | `BungieMembershipType` enum int (Xbox/PSN/Steam/etc.) |
| `is_cross_save_primary` | `BOOLEAN NOT NULL DEFAULT false` | mirrors `GetLinkedProfiles`' `isCrossSavePrimary` |
| `is_overridden` | `BOOLEAN NOT NULL DEFAULT false` | mirrors `isOverridden` |
| `cross_save_override_type` | `SMALLINT` | nullable, mirrors `crossSaveOverride` |
| `applicable_membership_types` | `SMALLINT[]` | Postgres array — small fixed-shape list, not worth normalizing into its own table |
| `last_profile_sync_at` | `TIMESTAMPTZ` | |

Constraint: `UNIQUE (destiny_membership_id, membership_type)` — the idempotent-upsert key; also prevents two internal users both claiming the same Destiny account.
Index: `(user_id, is_cross_save_primary)` for the "get this user's primary account" query.

**Why store every card, not just the primary:** `PRODUCT_REQUIREMENTS.md` §1 requires surfacing merged/inactive platforms in the UI; storing every `GetLinkedProfiles` card (with the primary flagged) lets the frontend render that without an extra Bungie call.

### `character`
| Column | Type | Notes |
|---|---|---|
| `id` | `BIGSERIAL PK` | |
| `linked_destiny_account_id` | `BIGINT NOT NULL FK -> linked_destiny_account.id` | |
| `destiny_character_id` | `VARCHAR NOT NULL` | Bungie character ID |
| `class_type`, `race_type`, `gender_type` | `SMALLINT` | |
| `light_level` | `INT` | |
| `last_played_at` | `TIMESTAMPTZ` | |
| `minutes_played_total` | `INT` | |
| `emblem_path` | `VARCHAR` | nullable — optional component |
| `profile_minted_at` | `TIMESTAMPTZ` | mirrors `responseMintedTimestamp`, used for the "data as of" staleness display required by `PRODUCT_REQUIREMENTS.md` §2 |

Constraint: `UNIQUE (linked_destiny_account_id, destiny_character_id)`.
Index: `destiny_character_id` (constant ingestion join key).

### `activity`
One row per **Bungie activity instance**, shared across all participants.
| Column | Type | Notes |
|---|---|---|
| `id` | `BIGSERIAL PK` | |
| `bungie_instance_id` | `BIGINT NOT NULL UNIQUE` | the idempotency key for this table |
| `activity_hash` | `BIGINT NOT NULL` | director activity definition hash, manifest-resolvable |
| `mode` | `SMALLINT NOT NULL` | primary `DestinyActivityModeType` |
| `modes` | `SMALLINT[]` | full modes array Bungie returns |
| `period` | `TIMESTAMPTZ NOT NULL` | activity start time, UTC |
| `is_private` | `BOOLEAN NOT NULL DEFAULT false` | |
| `pgcr_status` | `VARCHAR NOT NULL DEFAULT 'summary_only'` | `summary_only`, `complete`, `retry_pending`, `failed` |
| `pgcr_fetch_attempts` | `SMALLINT NOT NULL DEFAULT 0` | |

Indexes: `(mode, period DESC)` for mode-filtered recent-activity queries; `pgcr_status` for the retry sweep job.

### `activity_participant`
Per-player-per-activity stats.
| Column | Type | Notes |
|---|---|---|
| `id` | `BIGSERIAL PK` | |
| `activity_id` | `BIGINT NOT NULL FK -> activity.id` | |
| `character_id` | `BIGINT FK -> character.id` | nullable — PGCRs include other players who may not be Ghost Protocol users |
| `bungie_destiny_user_info` | `JSONB` | minimal denormalized snapshot (display name/platform) for non-tracked participants, so match context ("vs. PlayerX") renders without forcing a full account-link for every opponent — see `OPEN_QUESTIONS.md` #1 |
| `standing` | `SMALLINT` | win/loss/placement; nullable until PGCR fetched |
| `completed` | `BOOLEAN` | |
| `kills`, `deaths`, `assists`, `score` | `INT` | promoted, indexable columns for the common cross-mode stats |
| `values` | `JSONB NOT NULL` | the full per-character stat dict beyond the promoted columns |
| `weapon_data_status` | `VARCHAR NOT NULL DEFAULT 'unknown'` | `present`, `absent`, `unknown` — the explicit "no data vs. zero" flag required by `PRODUCT_REQUIREMENTS.md` §5 |

Constraints: `UNIQUE (activity_id, character_id)` as a partial unique index `WHERE character_id IS NOT NULL`; a second partial unique index on `(activity_id, bungie_destiny_user_info->>'membershipId')` `WHERE character_id IS NULL`, so untracked opponents are still deduplicated.
Index: `character_id` (drives most analytics reads).

**Why JSONB + promoted columns, not a fully normalized stats table:** Bungie's per-character `values` dict is a loosely-typed, mode-dependent bag that has grown over time without a versioned contract (`API_FEASIBILITY.md` §4). Fully normalizing every possible key into columns means a schema migration every time Bungie adds a stat, with most columns null for most modes. JSONB keeps ingestion simple and future-proof; the handful of stats that genuinely need fast/indexable cross-mode queries (kills, deaths, assists, score) are *also* promoted to typed columns, giving the best of both: no migration churn for the long tail, and real indexes for the common dashboard path.

### `weapon_definition`
Manifest-derived, static game data — physically separate from user data.
| Column | Type | Notes |
|---|---|---|
| `id` | `BIGSERIAL PK` | |
| `item_hash` | `BIGINT NOT NULL UNIQUE` | `DestinyInventoryItemDefinition` hash — the natural key |
| `display_name` | `VARCHAR NOT NULL` | |
| `icon_path` | `VARCHAR` | |
| `item_type` | `VARCHAR` | |
| `damage_type` | `SMALLINT` | |
| `tier_type` | `SMALLINT` | |
| `manifest_version` | `VARCHAR NOT NULL` | version this row was last refreshed from |
| `is_active` | `BOOLEAN NOT NULL DEFAULT true` | soft-flag if a hash disappears from a newer manifest — historical `activity_weapon_usage` rows still reference it, so it's never hard-deleted |

### `manifest_version`
| Column | Type | Notes |
|---|---|---|
| `id` | `BIGSERIAL PK` | |
| `version` | `VARCHAR UNIQUE NOT NULL` | |
| `locale` | `VARCHAR NOT NULL DEFAULT 'en'` | |
| `synced_at` | `TIMESTAMPTZ NOT NULL` | |

Tracks which manifest version is currently applied, so the manifest sync job can diff "current Bungie version" vs. "our stored version" before re-downloading (manifest size/cadence is undocumented per `API_FEASIBILITY.md` §5, so avoiding unnecessary re-downloads matters).

### `activity_weapon_usage`
Per-participant per-weapon stats.
| Column | Type | Notes |
|---|---|---|
| `id` | `BIGSERIAL PK` | |
| `activity_participant_id` | `BIGINT NOT NULL FK -> activity_participant.id` | |
| `weapon_definition_id` | `BIGINT NOT NULL FK -> weapon_definition.id` | |
| `kills`, `precision_kills` | `INT` | promoted columns |
| `values` | `JSONB` | the full per-weapon stat dict beyond the promoted columns, same rationale as `activity_participant.values` |

Constraint: `UNIQUE (activity_participant_id, weapon_definition_id)` — idempotency key for this table.
Index: `weapon_definition_id` (drives the "usage of weapon X over time" analytics query — the core Weapon Analytics feature).

### `sync_checkpoint`
| Column | Type | Notes |
|---|---|---|
| `id` | `BIGSERIAL PK` | |
| `character_id` | `BIGINT NOT NULL UNIQUE FK -> character.id` | |
| `status` | `VARCHAR NOT NULL DEFAULT 'idle'` | `idle`, `in_progress`, `error` |
| `last_synced_at` | `TIMESTAMPTZ` | |
| `newest_period_seen` | `TIMESTAMPTZ` | the resume cursor |
| `furthest_page_scanned` | `INT NOT NULL DEFAULT 0` | |
| `last_error` | `TEXT` | |
| `consecutive_error_count` | `SMALLINT NOT NULL DEFAULT 0` | |

This is the exact per-character cursor/state the ingestion pipeline (`SYSTEM_ARCHITECTURE.md`, step 4) reads/writes to resume after a restart.

### `sync_job`
| Column | Type | Notes |
|---|---|---|
| `id` | `BIGSERIAL PK` | |
| `character_id` | `BIGINT NOT NULL FK -> character.id` | |
| `job_type` | `VARCHAR NOT NULL` | `activity_sync`, `pgcr_retry_sweep`, ... |
| `status` | `VARCHAR NOT NULL DEFAULT 'queued'` | `queued`, `running`, `succeeded`, `failed` |
| `enqueued_at`, `started_at`, `finished_at` | `TIMESTAMPTZ` | |

Index: `(status, enqueued_at)` for the worker's "next job to pick up" query.

## Migration Strategy

- Flyway, versioned SQL migrations under `src/main/resources/db/migration/`.
- `V1__initial_schema.sql` creates all tables above in dependency order.
- Manifest-derived tables (`weapon_definition`, `manifest_version`) are seeded by the application's manifest-sync job at runtime, not by migration scripts — migrations only create structure, never game-content data.
- Every future schema change is a new versioned migration; no editing of already-applied migrations.

## Security & Lifecycle

- **Authorization:** enforced at the service layer per `SYSTEM_ARCHITECTURE.md`'s Security Boundaries section — every query touching `character`/`activity_participant`/`activity_weapon_usage` is scoped to the requesting session's own `linked_destiny_account`.
- **Unlink cascade:** `ON DELETE CASCADE` from `linked_destiny_account` → `character` → `activity_participant`/`sync_checkpoint` → `activity_weapon_usage`. `activity` rows are **not** cascade-deleted (other users' participants may still reference them); an explicit cleanup job removes an `activity` row only once it has zero remaining `activity_participant` rows.
- **Full account deletion:** cascades `app_user` → `bungie_oauth_token`, `linked_destiny_account` → ... down the same chain, and revokes the active session.
- **What's never persisted:** Bungie passwords (never collected at all), raw unprocessed API JSON payloads (discarded after mapping), and plaintext OAuth tokens (always encrypted at rest).
