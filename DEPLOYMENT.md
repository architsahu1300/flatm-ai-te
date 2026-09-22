# Deploying Flatm'AI'te

Three hosts, because the stack has three genuinely different runtime needs:

```
  browser
     │
     ▼
  Vercel  ──── Next.js 15 (frontend/)
     │         rewrites /api/* and /uploads/* to BACKEND_URL
     ▼
  Railway ──── Spring Boot 3.5 / Java 17 (backend/)
     │         + a persistent volume for uploads
     ▼
  Railway ──── Postgres 16 + pgvector
```

**The Next.js proxy is load-bearing, not an optimisation.** The backend has no CORS
configuration at all, and the `fm_token` session cookie is `httpOnly; SameSite=Lax`.
Both only work because the browser talks exclusively to the Vercel origin and Next
forwards to Railway server-side. Do not point browser code at the Railway domain.

---

## 1. Database — Postgres + pgvector

Flyway's [`V1__extensions.sql`](backend/src/main/resources/db/migration/V1__extensions.sql)
runs `CREATE EXTENSION vector` and `pgcrypto` on first boot, so the extension files
must exist on the server.

In Railway, add a database from the **pgvector** template rather than the plain
Postgres one (the plain image may not ship the extension). If `vector` is missing
you will know immediately: the backend fails at startup with a Flyway migration
error naming the extension, not a subtle runtime bug.

[Neon](https://neon.tech) works equally well as an alternative — it ships `vector`,
`pgcrypto`, and also `cube`/`earthdistance`, which WS6's distance ladder will want.
If you use Neon, append `?sslmode=require` to the JDBC URL below.

Note both connection strings the provider gives you:

- the **internal / private** host — what the backend uses in production
- the **public** host — what you use from your laptop for one-off jobs (seeding, psql)

## 2. Backend — Railway

Create a service from the GitHub repo `architsahu1300/flatm-ai-te`, then:

| Setting | Value |
| --- | --- |
| Root directory | `backend` |
| Builder | Dockerfile (picked up from [`backend/railway.json`](backend/railway.json)) |
| Health check path | `/actuator/health` (permitted unauthenticated in `SecurityConfig`) |
| Volume mount path | `/data` |

The volume is not optional. `LocalDiskStorageProvider` and `AgreementPdfService`
both write to the local filesystem, so without it every listing photo and signed
agreement PDF disappears on the next deploy.

### Backend environment

Required:

| Variable | Value | Why |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}` | JDBC form — Railway's own `DATABASE_URL` is `postgres://`, which the driver rejects |
| `DB_USER` | `${{Postgres.PGUSER}}` | |
| `DB_PASSWORD` | `${{Postgres.PGPASSWORD}}` | |
| `JWT_SECRET` | 32+ random bytes — `openssl rand -base64 48` | HS256 minimum; the dev default is public in git |
| `JWT_SECURE_COOKIE` | `true` | HTTPS-only session cookie |
| `FRONTEND_URL` | `https://<your-vercel-domain>` | where Google OAuth redirects after sign-in (`GoogleOAuthConfig`) |
| `UPLOAD_DIR` | `/data/uploads` | must sit inside the mounted volume |

Leave `PORT` alone — Railway injects it and `application.yml` now reads `${PORT:8080}`.

Optional, all with working defaults in `application.yml`:

| Variable | Default | Notes |
| --- | --- | --- |
| `FM_AI_PROVIDER` | `openai` | `google-genai` for the free Gemini tier |
| `OPENAI_API_KEY` / `GEMINI_API_KEY` | unset | with neither key set, the deterministic mock provider serves search |
| `AI_DAILY_SEARCH_LIMIT` | `50` | per signed-in user |
| `AI_ANON_DAILY_SEARCH_LIMIT` | `10` | per anonymous key |
| `AI_DAILY_COST_LIMIT_USD` | `0.50` | hard stop on model spend |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | unset | Google sign-in stays hidden while unset |
| `SPRINGDOC_API_DOCS_ENABLED` | `true` | **set `false`** — `SecurityConfig` permits `/v3/api-docs/**` unauthenticated |
| `SPRINGDOC_SWAGGER_UI_ENABLED` | `true` | **set `false`** — same, for `/swagger-ui` |
| `MAX_FILE_SIZE` / `MAX_REQUEST_SIZE` | `10MB` / `25MB` | Spring's multipart ceiling; see the upload limit below |
| `SEARCH_NEARBY_RADIUS_MINUTES` | `25` | locality widening |
| `SEARCH_MIN_RESULTS` / `SEARCH_RESCUE_RADIUS_MINUTES` | `6` / `45` | WS4 rescue ladder |

If you enable Google sign-in, add `https://<railway-domain>/login/oauth2/code/google`
to the authorised redirect URIs in the Google Cloud console.

## 3. Frontend — Vercel

Import the same repo as a separate project:

| Setting | Value |
| --- | --- |
| Root directory | `frontend` |
| Framework preset | Next.js (auto-detected) |
| Build / install commands | leave as detected |

| Variable | Value |
| --- | --- |
| `BACKEND_URL` | `https://<railway-domain>` — **no trailing slash** |
| `NEXT_PUBLIC_SITE_URL` | `https://<your-vercel-domain>` — used by `robots.ts` and `sitemap.ts` |

`BACKEND_URL` is read at build time by `next.config.ts` for the rewrite rules, so
changing it requires a redeploy, not just a restart.

Deploy order: Railway first (you need its domain for `BACKEND_URL`), then Vercel,
then come back and set `FRONTEND_URL` on Railway to the Vercel domain.

## 4. Seed data (optional)

Production can start empty — real listings arrive through the app. But if this is a
demo deploy and you want the 80 listings / 35 flatmates / 38 localities:

**Run the seed profile on Railway, not from your laptop.** `SeedRunner` writes 15
sample SVGs into `${UPLOAD_DIR}/seed/`, so seeding from a local machine puts rows in
the remote database pointing at files that only exist on your disk — every seed
listing image would 404.

1. Set `SPRING_PROFILES_ACTIVE=seed` on the Railway service and redeploy.
2. The seed runner populates the database, writes the SVGs to the volume, and exits.
   Railway will show the deployment as stopped — that is expected, the seed profile
   is a one-shot job.
3. Remove `SPRING_PROFILES_ACTIVE` and redeploy to get the normal service back.

## 5. Post-deploy checks

```bash
curl -s https://<railway-domain>/actuator/health
curl -s https://<railway-domain>/api/v1/localities | head -c 200
curl -s https://<vercel-domain>/api/v1/localities | head -c 200   # proves the proxy
curl -s https://<vercel-domain>/sitemap.xml | head -c 300
```

The third one is the one that matters: it proves Vercel is reaching Railway. If it
returns HTML instead of JSON, `BACKEND_URL` is wrong or was set after the build.

Then sign in through the deployed frontend and reload — if the session survives,
the cookie is flowing through the proxy correctly.

## Known limits

- **Uploads larger than ~4.5 MB fail.** Vercel caps proxied request bodies at about
  4.5 MB, well under Spring's 10 MB `max-file-size`. A modern phone photo can exceed
  it, and the failure surfaces as a Vercel-level error rather than an API error.
  Raising `MAX_FILE_SIZE` does not help — the request never reaches Spring. Lifting
  this properly means uploading directly to object storage instead of through the
  proxy, which is the media workstream's job.
- **No video support.** `LocalDiskStorageProvider` allows `jpg, jpeg, png, webp, svg`
  only, and `listing_images` is image-shaped (`width`/`height`/`is_cover`).
- **No compression.** `file.transferTo(target)` stores original bytes, so the volume
  fills at whatever size users upload.
- **Uploads are served by Spring, through the Vercel proxy**, with no CDN in front.
  Fine at demo traffic; the first thing to move if image loads get slow.
- **The API docs are public unless you disable them.** `SecurityConfig` permits
  `/v3/api-docs/**` and `/swagger-ui/**` without authentication, and SpringDoc warns
  about exactly this on every boot. Set both `SPRINGDOC_*` variables above to `false`.
- **Tests do not run in the image build.** The suite needs Testcontainers, i.e. a
  Docker daemon inside the build, so `./mvnw verify` stays a local/CI gate. Run it
  before you push.

## Rollback

Railway keeps previous deployments — redeploy an earlier one from the service's
deployment list. Vercel does the same via "Instant Rollback" on a previous
deployment. Neither reverts database migrations, so a rollback across a Flyway
migration needs the down-path handled by hand.
