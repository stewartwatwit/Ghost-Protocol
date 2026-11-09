# Ghost Protocol

**Know your Guardian.**

Ghost Protocol is a Destiny 2 companion & performance intelligence platform. It connects to the official Bungie API to retrieve a player's Destiny 2 account information, character data, activity history, and available Post Game Carnage Reports (PGCRs), then turns that into a centralized analytics dashboard: activity history browsing, performance trends, and weapon usage analytics.

This is an independent community project. **It is not affiliated with, endorsed by, or sponsored by Bungie.**

## Status

**Planning phase.** No application code has been written yet. The project's architecture, database design, API feasibility, product requirements, deployment plan, roadmap, and open questions are fully documented in [`docs/`](docs/) before any implementation begins. See [`docs/DEVELOPMENT_ROADMAP.md`](docs/DEVELOPMENT_ROADMAP.md) for current progress — the next step is a small proof-of-concept (Milestone M0) validating the Bungie API behaviors this plan depends on.

## Project Phases

- **Phase A — Personal Performance Analytics** *(current focus)*: Bungie OAuth sign-in, linked-account/character display, activity history browsing, individual activity reports, weapon usage analytics, and historical performance trends — all backed by data synced from the official Bungie API and stored for analysis. See [`docs/PRODUCT_REQUIREMENTS.md`](docs/PRODUCT_REQUIREMENTS.md).
- **Phase B — Full Guardian Companion** *(future)*: inventory management, weapon/armor exploration, loadout creation and comparison, collections/triumph tracking — built only after Phase A is complete and stable, on an architecture designed to support it without a rewrite.

## Why

Bungie's own website already shows a lot of this data. Ghost Protocol's value is in what isn't there by default: historical trends across many activities, weapon-usage patterns over time, and a dashboard built specifically to make a player's gameplay history easier to understand — not just a reformatted API browser.

## Technology Stack

**Backend:** Java 21, Spring Boot, Spring Security, Spring Data JPA/Hibernate, Maven, PostgreSQL, Flyway.
**Frontend:** React, TypeScript, Vite, Tailwind CSS, Recharts.
**Infrastructure:** Docker, Docker Compose, GitHub Actions (CI/CD). Deployed on Vercel (frontend), Render (backend), and Neon (managed PostgreSQL) — see [`docs/DEPLOYMENT_PLAN.md`](docs/DEPLOYMENT_PLAN.md).
**Testing:** JUnit 5, Mockito, Spring Boot Test, Testcontainers.

## Architecture

A modular monolith (not microservices) with clear domain boundaries — `auth`, `bungie` (the sole module that talks to Bungie's API), `guardian`, `activity`, `weapon`, `ingestion`, `analytics`, `shared`. Full design in [`docs/SYSTEM_ARCHITECTURE.md`](docs/SYSTEM_ARCHITECTURE.md); database schema in [`docs/DATABASE_DESIGN.md`](docs/DATABASE_DESIGN.md).

## Bungie API

Every feature in this project is scoped to what the **official** Bungie API documentably supports — not to what other third-party Destiny apps happen to display. The full feasibility research, including what's confirmed vs. unverified in Bungie's public documentation, is in [`docs/API_FEASIBILITY.md`](docs/API_FEASIBILITY.md).

## Planning Documents

- [`docs/PRODUCT_REQUIREMENTS.md`](docs/PRODUCT_REQUIREMENTS.md) — Phase A feature scope
- [`docs/API_FEASIBILITY.md`](docs/API_FEASIBILITY.md) — what the Bungie API actually supports, with sources
- [`docs/SYSTEM_ARCHITECTURE.md`](docs/SYSTEM_ARCHITECTURE.md) — module design, auth flow, ingestion pipeline
- [`docs/DATABASE_DESIGN.md`](docs/DATABASE_DESIGN.md) — schema, relationships, migration strategy
- [`docs/DEVELOPMENT_ROADMAP.md`](docs/DEVELOPMENT_ROADMAP.md) — milestones M0–M10
- [`docs/DEPLOYMENT_PLAN.md`](docs/DEPLOYMENT_PLAN.md) — hosting, CI/CD, demo mode, secrets
- [`docs/OPEN_QUESTIONS.md`](docs/OPEN_QUESTIONS.md) — unresolved items requiring input or PoC validation
