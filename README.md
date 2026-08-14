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
export OPENAI_API_KEY=sk-...  # optional — omit to run with the deterministic mock AI provider
./mvnw spring-boot:run

# 3. Seed data (once; idempotent — safe to re-run)
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
| `OPENAI_API_KEY` | _unset_ → mock AI | Real LLM intent extraction + explanations |
| `DB_URL` | `jdbc:postgresql://localhost:5433/flatmaite` | |
| `JWT_SECRET` | dev-only value | HS256 signing key (≥32 bytes) |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | _unset_ → Google login hidden | OAuth |
| `AI_MOCK` | `auto` | `true`/`false` to force provider mode |
| `AI_EXPLANATIONS_ENABLED` | `true` | Kill-switch → score-breakdown-only UI |

Frontend: `BACKEND_URL` (default `http://localhost:8080`).

## Tests & checks

```bash
cd backend && ./mvnw verify              # unit + Testcontainers integration tests
cd frontend && npm run lint && npx tsc --noEmit && npm run build
```
