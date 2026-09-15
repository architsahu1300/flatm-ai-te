# Flatm'AI'te

AI-first flatmate & rental marketplace. Describe what you're looking for in plain words —
the AI extracts your requirements, searches homes **and** people, ranks matches with a
transparent Match Score, and explains every recommendation.

## Stack

| Layer     | Tech |
|-----------|------|
| Backend   | Java 17 · Spring Boot 3.5 · Spring Security (JWT) · Spring Data JPA · Flyway · Spring AI (OpenAI) |
| Frontend  | Next.js 15 · React 19 · TypeScript · Tailwind CSS v4 · zustand · nuqs |
| Database  | PostgreSQL 16 + pgvector (Docker) |
| AI        | gpt-4o-mini (intent + explanations) · text-embedding-3-small (1536d) · deterministic mock fallback |

## Run locally

```bash
# 1. Database
docker compose up -d          # Postgres 16 + pgvector on localhost:5433

# 2. Backend (http://localhost:8080, Swagger at /swagger-ui)
cd backend
# Optional — omit both to run with the deterministic mock AI provider.
export OPENAI_API_KEY=sk-...                # paid: OpenAI (production default)
# ...or the free Gemini tier for testing (key from https://aistudio.google.com/apikey):
# export FM_AI_PROVIDER=google-genai GEMINI_API_KEY=AIza...
./mvnw spring-boot:run

# 3. Seed data (once; idempotent — safe to re-run)
# NOTE: the seed profile runs with no web server and exits when finished —
# it is not a way to start the app. Use step 2 for that.
./mvnw spring-boot:run -Dspring-boot.run.profiles=seed

# 4. Frontend (http://localhost:3000)
cd ../frontend
npm install
npm run dev
```

The frontend proxies `/api/*` and `/uploads/*` to the backend (`next.config.ts` rewrites),
so the browser only ever talks to `localhost:3000`.

### Seed logins

| User | Password |
|------|----------|
| `seed-user-001@flatmaite.test` … | `password123` |
| `admin@flatmaite.test` | `password123` |

## Environment variables

Backend (all optional in dev — sane defaults in `application.yml`):

| Var | Default | Purpose |
|-----|---------|---------|
| `FM_AI_PROVIDER` | `openai` | AI provider: `openai` or `google-genai` (free Gemini tier) |
| `OPENAI_API_KEY` | _unset_ → mock AI | Real LLM intent extraction + explanations (openai mode) |
| `GEMINI_API_KEY` | _unset_ → mock AI | Same, via Gemini free tier (google-genai mode) |
| `DB_URL` | `jdbc:postgresql://localhost:5433/flatmaite` | |
| `JWT_SECRET` | dev-only value | HS256 signing key (≥32 bytes) |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | _unset_ → Google login hidden | OAuth |
| `AI_MOCK` | `auto` | `true`/`false` to force provider mode |
| `AI_EXPLANATIONS_ENABLED` | `true` | Kill-switch → score-breakdown-only UI |
| `SEARCH_NEARBY_RADIUS_MINUTES` | `25` | A named home locality also admits every locality within this many estimated minutes |
| `SEARCH_MIN_RESULTS` | `6` | Below this many homes, nearby and near-miss options are added automatically |
| `SEARCH_RESCUE_RADIUS_MINUTES` | `45` | The wider ring the top-up reaches for before it drops any filter |
| `EVAL_PACE_MS` | `4500` | Eval profile only — delay between provider calls |
| `EVAL_TAGS` / `EVAL_LIMIT` | all / `0` | Eval profile only — run a subset of the golden set |
| `EVAL_ALLOW_MOCK` | `false` | Eval profile only — allow the mock provider (runner smoke test) |

Frontend: `BACKEND_URL` (default `http://localhost:8080`).

## Tests & checks

```bash
cd backend && ./mvnw verify              # unit + Testcontainers integration tests
cd frontend && npm run lint && npx tsc --noEmit && npm run build
```

### Intent eval

Every `./mvnw verify` runs `IntentGoldenTest`: the keyword parser and the new-vs-refine arbiter
against `backend/src/main/resources/eval/intent-golden.json` (≈ 75 real-shaped queries, including
multi-turn follow-ups). The build fails if any must-pass case fails, the case pass rate drops below
0.85, or `locations` / `budgetMax` / `roomType` slot accuracy drops below 0.90. Cases tagged
`known-gap` are reported but not counted. Read `docs/eval/README.md` before interpreting a
live run — two known key artefacts are listed there.

Confidence gating keeps a slot the user actually stated (`"2bhk in Powai"`) a hard SQL filter,
while a slot the reader only inferred (a guessed `roomType`, a defaulted commute radius) becomes a
ranking preference instead — it never deletes a listing, it only ranks matching ones higher. The
UI marks the difference: a soft chip's value is prefixed with `≈`. Two slots are never softened no
matter how low their confidence — `excludeLocations` and `verifiedOnly` — because excluding an area
or promising verified-only listings has to hold exactly as stated. When the page still comes back
thin, an automatic rescue tops it up with nearby and near-miss results, each one marked and labelled
with the reason it's there rather than left for the user to discover.

Run the same set through the real model (never gates; paced for Gemini's free tier):

The eval resolves localities from the database, so refresh it first: from the repo root
`docker compose down -v && docker compose up -d`, then
`cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=seed`. An older local seed
silently distorts the locality slots.

```bash
cd backend && FM_AI_PROVIDER=google-genai GEMINI_API_KEY=… ./mvnw spring-boot:run -Dspring-boot.run.profiles=eval
```

The report prints as a table and lands in `backend/target/eval/<provider>-<model>-<timestamp>.json`.
Knobs: `EVAL_PACE_MS` (default 4500), `EVAL_TAGS=budget,commute`, `EVAL_LIMIT=10`,
`EVAL_ALLOW_MOCK=true` (smoke-test the runner with the keyword parser). Adding cases:
`docs/eval/README.md`.
