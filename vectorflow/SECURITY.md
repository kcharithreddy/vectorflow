# Security

This document describes how VectorFlow protects users, data and the OpenAI bill, and how to
run it safely outside your own machine.

## Defence in depth

```
Internet ──► nginx (only public port) ──► Spring Boot gateway ──► Python AI service ──► Qdrant / OpenAI
             headers, CSP, edge rate     JWT, roles, ownership,   internal API key,
             limits, version hidden      lockout, rate limits,    file-signature checks,
                                         validation, audit log    prompt-injection guard
```

| Layer | Control |
|-------|---------|
| **Network** | Only the web app (port 3000) is published; the gateway, AI service and databases listen on `127.0.0.1` / the Docker network |
| **Edge (nginx)** | Per-IP request limits (10 req/s, bursts allowed) → `429`; security headers on every response; server version hidden |
| **Browser** | `Content-Security-Policy` (self-only scripts, no framing), `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Permissions-Policy`, `Cross-Origin-Opener-Policy`; CORS allow-list on the gateway |
| **Authentication** | BCrypt password hashes; JWT (HS256, 24 h) with issuer check and unique id; secret must be ≥ 32 bytes; identical errors and timing for unknown emails |
| **Brute force** | Account lockout after 10 failed logins in 15 minutes (Redis-backed, shared across instances); 10 registrations per IP per hour |
| **Passwords** | 8–72 characters, at least one letter and one number, common passwords rejected, must not contain the email name |
| **Authorization** | Roles (`VIEWER` / `EDITOR` / `ADMIN`) re-read from the database on every request, so demotion is immediate; editors can only delete their own documents; admins can't remove their own admin role |
| **Cost / abuse** | Per-user limits: 30 questions per minute, 60 uploads per hour (`Retry-After` header on `429`) |
| **API hardening** | GraphQL max query depth (10) and complexity (300); introspection can be disabled; input validation on every field; clean error messages (no stack traces) |
| **Uploads** | 20 MB limit; extension allow-list; **content signature check** (a renamed executable is rejected even with a `.pdf` name); text extracted server-side only, never executed |
| **Service-to-service** | Python service requires `X-Internal-API-Key` (constant-time comparison) |
| **LLM** | Prompt-injection hardening: retrieved text is treated as untrusted data; answers only from sources; relevance threshold |
| **Audit** | A dedicated `AUDIT` log: `LOGIN_SUCCESS`, `LOGIN_FAILURE`, `LOGIN_LOCKED`, `REGISTER`, `ROLE_CHANGE`, `DOCUMENT_INGEST`, `DOCUMENT_DELETE`, `RATE_LIMITED` (values sanitised against log injection) |
| **Containers** | Non-root users; pinned image versions; health checks |
| **Supply chain** | Dependabot updates for Maven, pip, npm, Docker and GitHub Actions; CI runs all tests on every push |

Rate limiters **fail open**: if Redis is unavailable, requests are allowed and a warning is logged,
so a cache outage never takes the API down.

## Production checklist

Set these in `.env` before exposing VectorFlow to anyone else:

```bash
SECURITY_STRICT=true                       # refuse to start with default secrets
JWT_SECRET=$(openssl rand -base64 48)
INTERNAL_API_KEY=$(openssl rand -hex 32)
POSTGRES_PASSWORD=$(openssl rand -hex 16)
GRAPHIQL_ENABLED=false
GRAPHQL_INTROSPECTION=false
CORS_ALLOWED_ORIGINS=https://your-domain.example
```

Also:
- Delete or change the seeded `admin@vectorflow.dev` / `editor@vectorflow.dev` accounts.
- Put TLS (HTTPS) in front of nginx, e.g. a cloud load balancer or Caddy/Traefik; the gateway already sends HSTS on HTTPS.
- Keep `OPENAI_API_KEY` in your platform's secret store, and set a monthly budget limit in the OpenAI dashboard.

## Known limitations

- JWTs can't be revoked before they expire (roles are still re-checked on every request). Short-lived access tokens + refresh tokens would close this.
- No OCR: scanned PDFs are rejected rather than processed.
- No antivirus scanning of uploads (files are only parsed for text, never stored or executed).

## Reporting a vulnerability

Please open a private security advisory on GitHub (**Security → Report a vulnerability**) rather than a public issue.
