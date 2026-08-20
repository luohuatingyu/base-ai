# Base AI

[English](README.md) | [简体中文](README.zh-CN.md)

Base AI is an extensible administration and AI integration platform. It combines a Vue management console, a Spring Boot system service, and a Python LLM worker behind a Docker Compose deployment.

The platform provides identity and access management, OpenAI-compatible model routing, API key access, executable visual workflows, task tracing, audit logs, and scheduled HTTP automation. MySQL stores platform data and workflows, PostgreSQL stores automation data, and Redis stores disposable session state.

## Features

- Role-based access control with users, roles, menus, departments, positions, and data scopes.
- English and Simplified Chinese interfaces and localized API messages.
- Model provider, model, and capability-route management from the administration console.
- Text and multimodal chat through OpenAI-compatible providers, with candidate failover, concurrency limits, and token accounting.
- HttpOnly-cookie browser sessions with signed CSRF protection, compatible Bearer-token clients, and scoped `X-API-Key` credentials with expiry, revocation, IP allowlists, and rate limits.
- Cross-service task tracing, cancellation, recovery, operation logs, and login logs.
- Manual and Cron-based HTTP automation with encrypted request configuration and outbound-host controls.
- Versioned visual workflows with reusable node templates, conditional branches, iteration, loops, tool-calling agents, manual runs, and API-key invocation.
- Runtime platform branding and language configuration.

## Technology Stack

- **Frontend:** Vue 3, Vite, Pinia, Vue Router, Element Plus, and Axios.
- **Backend:** Java 17, Spring Boot 3.3, Spring MVC, Spring Data JPA, and JDBC.
- **LLM worker:** Python 3.12, FastAPI, HTTPX, PyYAML, and OpenAI-compatible APIs.
- **Data services:** MySQL, PostgreSQL, and Redis.
- **Deployment:** Docker Compose and multi-stage container builds.

## Architecture

```text
Browser
  |
  v
Vue frontend (port 80)
  |
  | /api/* reverse proxy
  v
Spring Boot backend (port 8080)
  |                  |
  |                  +--> MySQL: identity, configuration, models, workflows, tasks, and logs
  |                  +--> PostgreSQL: automation configurations and execution logs
  |                  +--> Redis: sessions, revoked tokens, locks, and rate-limit state
  |
  | authenticated internal requests
  v
Python worker (internal port 8000)
  |
  v
OpenAI-compatible model providers
```

Provider API keys are normally kept out of browser responses; authenticated system administrators may explicitly reveal them from the management console. The Java service resolves an enabled model route, decrypts the relevant provider credentials, and sends candidate configurations to the Python worker. The worker enforces concurrency limits, rotates credentials, fails over between candidates, and reports usage and tracing data to Java.

## Data Responsibilities

### MySQL system database

MySQL is the primary platform database. It contains:

- Users, roles, menus, permissions, departments, positions, dictionaries, and system settings.
- Model providers, encrypted provider credentials, models, and capability routes.
- External API key metadata, HMAC-SHA256 digests, and encrypted copies for administrator-only reveal.
- Workflow node templates, definitions, immutable published versions, workflow runs, and per-node execution logs.
- System tasks, Java/Python trace records, operation logs, and login logs.

Flyway manages the complete MySQL schema, including JPA platform entities, task traces, and logs. Existing non-empty databases are baselined at version 0 and then receive all idempotent migrations; Hibernate runs in `validate` mode and never mutates tables. Create the target database before starting the application and grant the configured account schema-management privileges.

### PostgreSQL business database

PostgreSQL is reserved for subordinate business modules. The current platform stores API-trigger configurations and execution logs there, and a separate Flyway migration chain updates that schema automatically. The configured account therefore requires DDL privileges during startup.

Future business modules can access PostgreSQL through the `postgresqlJdbcTemplate` Spring bean and should maintain their schemas independently.

### Redis cache

Redis stores state that can be rebuilt, including online sessions, revoked token identifiers, API key rate-limit counters, and distributed scheduler locks. MySQL remains the source of truth for users and permissions.

## Security Model

- Passwords are hashed with BCrypt.
- Login tokens use HS256 signatures, unique `jti` values, and configurable expiration.
- Logging out records the token identifier in Redis until the token expires.
- Backend endpoints enforce permissions with `@RequiredPermission`; frontend route and menu checks provide the corresponding user experience.
- The built-in `ADMIN` role receives all seeded permissions.
- Sensitive system settings, provider credentials, and automation request data are encrypted with AES-GCM.
- Request and audit logging masks passwords, tokens, cookies, authorization headers, and API keys.
- API responses use `{success, code, message, data}`. Messages follow `Accept-Language`, or `APP_DEFAULT_LOCALE` when the header is absent.

## Model Management and Chat

Configure providers, models, and capability routes from the **Model Management** section of the web console:

1. Add an OpenAI-compatible provider URL and one or more API keys.
2. Add models, select their supported model types, and configure timeouts or thinking parameters as needed.
3. Add a capability route and order its candidate models.
4. Use the AI Chat page in route mode or select a single provider model directly.

The initial model-type dictionary contains `text_model` and `vision_model`; administrators can add more enabled values through dictionary management. A request without `model_type` defaults to `text_model`.

The chat request path is:

```text
Vue -> Java /api/ai/chat -> Python /llm/chat -> OpenAI-compatible API
```

Prompt and model-response logging is disabled by default. The Java HTTP layer records request metadata only and never logs headers or bodies. Set `LLM_LOG_CONTENT=true` only after data-classification and log-access review; the Worker then records bounded model content with common credential formats redacted. When disabled, it records model, token, duration, and response-digest metadata only.

## API Key Access

External systems can call endpoints explicitly annotated as API-key accessible. Create, authorize, rotate, disable, or revoke keys from **System Management > API Key Management**.

```bash
curl --cacert caddy-root.crt -X POST https://127.0.0.1:444/api/ai/chat \
  -H 'X-API-Key: sk-<your-api-key>' \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"hello"}]}'
```

Important rules:

- A key uses the `sk-` prefix followed by a generated secret. Only users with the built-in `ADMIN` role may create, rotate, or reveal complete values.
- The database keeps a lookup prefix and HMAC-SHA256 digest for authentication. New and rotated keys also have an AES-GCM encrypted copy for administrator-only reveal.
- Existing keys created before encrypted copies were introduced remain valid but cannot be revealed until an administrator rotates them.
- Effective access is the intersection of the endpoints selected for the key and the bound user's RBAC permissions.
- The bound user and key must both remain enabled. Expiry, revocation, IP allowlists, and rate limits are also enforced.
- A request must not contain both `Authorization` and `X-API-Key`; mixed credentials return HTTP 401.
- API key management endpoints accept authenticated browser sessions or Bearer tokens, but never API keys.
- `APP_API_KEY_HASH_SECRET` is a required dedicated secret and cannot reuse `APP_CONFIG_ENCRYPTION_KEY`.

The currently exposed API-key endpoints are AI chat invocation, production API-trigger execution, and published workflow execution/status queries.

## Workflow Management

The top-level **Workflow** section contains reusable **Node Management** and visual **Canvas Management** pages. A canvas supports start/end, LLM, HTTP, tool-calling agent, condition, array iteration, and condition-loop nodes. Draft graphs can be validated and published as immutable versions before execution.

- Manual runs require workflow execution permission; external runs use a scoped `X-API-Key` and the workflow code.
- Non-admin users can only view and manage workflows they own. API keys must explicitly allowlist individual workflows; existing keys receive no workflow grants by default.
- Agent nodes can call the controlled HTTP tool or another published workflow. Recursion depth, iteration count, loop count, node count, and payload size are bounded by platform configuration.
- Workflow inputs, outputs, and node outputs are AES-GCM encrypted at rest. Definitions, versions, runs, and node logs remain framework-level data in MySQL.
- Database, Redis, S3, Kafka, and RabbitMQ connectors use an independent Host/CIDR egress allowlist. Existing targets are imported once during upgrade, and security-relevant connection changes require referenced workflows to be published again.
- Runs use a bounded queue, cumulative step/log budgets, and multi-instance leases. Cancellation is terminal, although irreversible side effects already accepted by a remote system cannot be rolled back automatically.
- Open execution uses `POST /api/workflows/{code}/runs`; status and decrypted results are read from `GET /api/workflows/runs/{runId}`.

## Task Tracing and Audit Logs

- Java creates or propagates `X-Request-Id` for each HTTP request.
- Tracked operations receive a separate `traceId` that joins Java, Python, and database records.
- Calls to the worker propagate request, parent-trace, Python-trace, and internal-authentication headers.
- `@TraceType` describes task type and trigger metadata; `@TraceIgnored` excludes endpoints that should not create tasks.
- Task state covers start, success, failure, cancellation, and forced termination.
- Parent cancellation propagates to the corresponding Python task without terminating the shared worker process.
- Startup recovery and scheduled checks mark abandoned tasks as failed after the heartbeat timeout.
- Operation and login logs are managed separately from task traces.

`backend/config/trace-tracking-exclusions.yml` defines HTTP methods and paths excluded from automatic task creation.

## HTTP Automation

The automation module supports configurable HTTP methods, headers, query parameters, request bodies, Cron schedules, timeouts, pre-request authentication, manual execution, and temporary connection tests.

- Request headers, request bodies, and authentication bodies are encrypted with `APP_CONFIG_ENCRYPTION_KEY`.
- Outbound host rules and loopback/private-network switches are managed from the **API Trigger Security** page and apply without a restart.
- Redis locks prevent duplicate Cron execution when multiple backend instances are running.
- Response summaries are truncated and sanitized before storage.
- Production executions also create MySQL task traces.

## Prerequisites

- Docker Engine with Docker Compose v2.
- Reachable MySQL, PostgreSQL, and Redis services. Docker Compose does not create these dependencies.
- Existing MySQL and PostgreSQL databases with accounts permitted to initialize the required schemas.

For local development outside containers, use Java 17, Maven 3.9, Node.js 24, and Python 3.12.

## Configuration

The repository tracks `.env.example` only. Never commit a populated `.env` file.

1. Copy the template:

   ```bash
   cp .env.example .env
   ```

2. Configure the external MySQL, PostgreSQL, and Redis connections.

3. Replace every placeholder credential. Generate an encryption key with:

   ```bash
   openssl rand -base64 32
   ```

4. At minimum, review these security-sensitive values:

   ```dotenv
   APP_TOKEN_SECRET=<at-least-32-random-characters>
   APP_SEED_ADMIN_PASSWORD=<at-least-12-secure-characters>
   APP_SEED_ADMIN_PASSWORD_SYNC_ENABLED=false
   APP_CONFIG_ENCRYPTION_KEY=<base64-encoded-32-byte-key>
   APP_API_KEY_HASH_SECRET=<at-least-32-random-characters>
   PYTHON_WORKER_INTERNAL_TOKEN=<at-least-24-random-characters>
   ```

The API key hash secret is optional only because it falls back to the encryption key; a separate value is recommended for production.

### Environment variable groups

- **MySQL:** `MYSQL_URL`, `MYSQL_USERNAME`, `MYSQL_PASSWORD`.
- **PostgreSQL:** `POSTGRES_URL`, `POSTGRES_USERNAME`, `POSTGRES_PASSWORD`, `POSTGRES_POOL_SIZE`.
- **Redis:** `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_DATABASE`, `REDIS_TIMEOUT`, `REDIS_SSL`.
- **Branding and locale:** `APP_PLATFORM_CODE`, `APP_PLATFORM_NAME_EN`, `APP_PLATFORM_NAME_ZH`, `APP_PLATFORM_SHORT_NAME`, `APP_DEFAULT_LOCALE`.
- **Authentication and encryption:** `APP_TOKEN_SECRET`, `APP_TOKEN_EXPIRE_MINUTES`, `APP_SESSION_COOKIE_SECURE`, `APP_SEED_ADMIN_USERNAME`, `APP_SEED_ADMIN_PASSWORD`, `APP_SEED_ADMIN_PASSWORD_SYNC_ENABLED`, `APP_CONFIG_ENCRYPTION_KEY`, `APP_CONFIG_ENCRYPTION_KEYS`, `APP_CONFIG_ENCRYPTION_ACTIVE_KEY_ID`, `APP_API_KEY_HASH_SECRET`.
- **Worker and model calls:** `PYTHON_WORKER_INTERNAL_TOKEN`, `JAVA_INSTANCE_ID`, `PYTHON_WORKER_INSTANCE_ID`, `LLM_TIMEOUT_SECONDS`, `LLM_LOG_CONTENT`.
- **Route health checks:** `LLM_ROUTE_HEALTH_CHECK_ENABLED`, `LLM_ROUTE_HEALTH_CHECK_INTERVAL_MS`.
- **Task tracing and logging:** `TRACE_TRACKING_EXCLUSIONS_FILE`, `TRACE_LOG_PERSIST_LEVEL`, `TRACE_LOG_QUEUE_CAPACITY`, `TRACE_LOG_BATCH_SIZE`, `TRACE_LOG_FLUSH_INTERVAL_MS`, `TRACE_LOG_RETENTION_DAYS`, `TRACE_HEARTBEAT_TIMEOUT_SECONDS`.
- **Automation:** `API_TRIGGER_SCHEDULER_POOL_SIZE`, `API_TRIGGER_LOCK_SECONDS`, `API_TRIGGER_RESULT_MAX_LENGTH`.
- **Workflow:** `WORKFLOW_EXECUTOR_POOL_SIZE`, `WORKFLOW_EXECUTOR_QUEUE_CAPACITY`, `WORKFLOW_MAX_NODES`, `WORKFLOW_MAX_ITERATIONS`, `WORKFLOW_MAX_AGENT_STEPS`, `WORKFLOW_MAX_RECURSION_DEPTH`, `WORKFLOW_MAX_PAYLOAD_BYTES`, `WORKFLOW_MAX_WAIT_SECONDS`, `WORKFLOW_MAX_EXECUTION_STEPS`, `WORKFLOW_MAX_RUN_LOG_BYTES`, `WORKFLOW_LEASE_SECONDS`, `WORKFLOW_WEBHOOK_MAX_BODY_BYTES`, `WORKFLOW_WEBHOOK_RATE_LIMIT_PER_MINUTE`, and `WORKFLOW_TRIGGER_DELIVERY_RETENTION_DAYS`.
- **HTTPS ingress and images:** `APP_HTTPS_SITES_FILE`, `TLS_CERTS_DIR`, `APP_HTTPS_IPS`, `TLS_CERT_CHECK_INTERVAL_SECONDS`, `IP_CERT_MIN_ISSUE_INTERVAL_SECONDS`, `IP_CERT_MAX_LEARNED_HOSTS`, `HTTP_PORT`, `HTTPS_PORT`, `FRONTEND_BACKEND_URL`, plus the optional image and package-mirror variables in `.env.example`. Backend port 8080 and Worker port 8000 are internal-only.

`APP_DEFAULT_LOCALE` accepts `en-US` or `zh-CN` and defaults to `en-US`.
Docker Compose derives the project name from `APP_PLATFORM_SHORT_NAME`, normalizes it to lowercase, and names the four runtime containers `<short-name>-backend`, `<short-name>-python-worker`, `<short-name>-frontend`, and `<short-name>-caddy`. For example, `APP_PLATFORM_SHORT_NAME=AI` produces `ai-backend`, `ai-python-worker`, `ai-frontend`, and `ai-caddy`.

### HTTPS ingress modes

Caddy can serve external domain certificates, preconfigured internal-CA IP certificates, and request-driven IP learning at the same time:

- **Domain certificates:** `APP_HTTPS_SITES_FILE` and `TLS_CERTS_DIR` must both be configured. Each YAML `sites` entry contains one or more `domains` and binds its own `tls_cert_file` and `tls_key_file`. Certificates must be PEM full chains including intermediates, and keys must be unencrypted PEM files. A partial configuration fails startup instead of downgrading to HTTP.
- **Preconfigured IPs:** `APP_HTTPS_IPS` accepts comma- or whitespace-separated canonical IPv4 addresses. Caddy adds them to its internal-CA certificate during startup, so their first request may use HTTPS directly. Removing an address and restarting removes its SAN unless the same address was also learned dynamically.
- **Dynamically learned IPs:** new IPv4 addresses can be learned on their first HTTP request whether or not domain certificates are configured. The project always includes `localhost` and `127.0.0.1` and persists both its internal CA and learned addresses. Preconfigured and learned IPs share one multi-SAN certificate, and every client must trust the Caddy root CA.

API triggers retain the JVM default public CAs and automatically load the Caddy root certificate from the dedicated read-only `caddy-public-ca` mount. Caddy atomically publishes only its public root certificate to that volume; its CA and site private keys remain unavailable to Backend. Triggers can therefore directly reach public-CA domains, IPs configured through `APP_HTTPS_IPS`, and dynamically learned IPs. Newly learned IPs continue to use the same root CA and require neither per-IP trust configuration nor a Backend restart.

API triggers support ordinary HTTP and controlled remote redirects. Every hop is revalidated against the Host and network-address policy, at most five redirects are followed, cross-Host redirects are rejected, and HTTPS cannot downgrade to HTTP. GET may follow 301, 302, 303, 307, and 308; other methods only follow 307 and 308 so their method and body remain unchanged. An HTTP authentication URL still sends its password and request body over a plaintext connection, so production configurations should use the final HTTPS URL directly.

Create an HTTPS sites list such as `/absolute/path/https-sites.yml`:

```yaml
sites:
  - domains:
      - ai.example.com
      - api.example.com
    tls_cert_file: site-a/fullchain.pem
    tls_key_file: site-a/privkey.pem

  - domains:
      - console.example.net
    tls_cert_file: site-b/fullchain.pem
    tls_key_file: site-b/privkey.pem
```

Certificates can be organized under `site-a/`, `site-b/`, and similar directories. YAML paths are relative to `TLS_CERTS_DIR`; absolute paths and `..` traversal are rejected. Startup verifies each certificate/key pair and checks that its SANs cover every domain in that group. A domain cannot appear in multiple groups.

The file accepts full YAML syntax, including block and flow lists, quoting, comments, and anchors, but only top-level `sites` is allowed, with `domains`, `tls_cert_file`, and `tls_key_file` in each group. It is limited to 64 KiB, 64 groups, and 256 total domains. Names are lowercased and deduplicated within a group and must be ASCII DNS hostnames without a scheme, port, path, or wildcard.

Example for multiple domains on standard ports:

```dotenv
APP_HTTPS_SITES_FILE=/absolute/path/https-sites.yml
TLS_CERTS_DIR=/absolute/path/tls
APP_HTTPS_IPS=192.168.1.20,203.0.113.10
HTTP_PORT=80
HTTPS_PORT=443
APP_SESSION_COOKIE_SECURE=true
```

Example for request-driven IP certificates:

```dotenv
APP_HTTPS_SITES_FILE=
TLS_CERTS_DIR=
APP_HTTPS_IPS=192.168.1.20,203.0.113.10
TLS_CERT_CHECK_INTERVAL_SECONDS=3600
IP_CERT_MIN_ISSUE_INTERVAL_SECONDS=5
IP_CERT_MAX_LEARNED_HOSTS=32
HTTP_PORT=81
HTTPS_PORT=444
APP_SESSION_COOKIE_SECURE=true
```

IP issuance runs no host-side detector and invokes no operating-system networking commands such as `uname`, `route`, `ifconfig`, or `ip`. Addresses in `APP_HTTPS_IPS` can be opened directly as `https://<IPv4>:<HTTPS_PORT>` after startup. An unconfigured address must first be opened as `http://<IPv4>:<HTTP_PORT>`; after Caddy validates the HTTP Host, issues a certificate, atomically persists the address, and hot reloads successfully, it returns a 308 redirect to HTTPS. This covers canonical loopback, private, directly assigned public, and NAT-mapped IPv4 addresses. A first direct HTTPS connection to an address that is neither configured nor learned cannot complete its TLS handshake.

The learning service listens only on the Caddy container loopback and accepts only GET and HEAD. Both configured and dynamic addresses reject IPv6, non-canonical, unspecified, link-local, multicast, and unusable values. The internal certificate contains at most 256 non-fixed-loopback IPv4 addresses; by default, at most 32 of those may be learned dynamically, with at least five seconds between new-address issuances. Reaching either limit returns HTTP 429 without evicting an existing address. Because a non-browser client can forge an HTTP Host header, this mechanism provides zero-configuration service access rather than proof of IP ownership. Never put passwords, tokens, or sensitive query values in the initial HTTP request.

The IP certificate targets a 30-day lifetime without exceeding the remaining lifetime of the Caddy intermediate. Changes to address SANs, the intermediate CA, or the renewal window trigger a new certificate and a hot reload through an admin endpoint bound only to the container loopback address. Issuance, persistence, or reload failures return HTTP 503 and restore the previous state. Background renewal checks run at `TLS_CERT_CHECK_INTERVAL_SECONDS`; failures retain the current certificate and retry on the next cycle.

The sites list and TLS root directory are mounted read-only, while Caddy runs as container UID `10001`. On Linux, grant that UID access with a dedicated group or ACL, including directory traversal permission; do not make private keys world-readable. Verify access before startup with:

```bash
docker compose run --rm --entrypoint sh caddy -c \
  'test -r /etc/caddy/https-sites.yml && test -r /etc/caddy/tls/site-a/fullchain.pem && test -r /etc/caddy/tls/site-a/privkey.pem'
```

After changing the sites list or replacing renewed domain certificate files, run `docker compose restart caddy` to revalidate and load every group. This project does not modify the list or renew host certificate files.

## Start with Docker Compose

Validate the resolved configuration before starting the stack. Be aware that `docker compose config` expands secrets, so do not share its output.

```bash
docker compose config --quiet
docker compose up --build -d
docker compose ps
```

IP learning and renewal run entirely inside the Caddy container. Standard Docker Compose commands are sufficient; no host-side script or additional runtime is required.

After all services are healthy:

- Web console: <https://127.0.0.1:444>
- Backend API and open platform: <https://127.0.0.1:444/api>
- Unified health check: <https://127.0.0.1:444/api/open/health>
- Backend liveness check: <https://127.0.0.1:444/api/open/health/live>
- Backend readiness check: <https://127.0.0.1:444/api/open/health/ready>
- Caddy health check: <https://127.0.0.1:444/health>

The unified and readiness checks return HTTP 503 unless MySQL, PostgreSQL, Redis, and the Python Worker are all available. Public ingress returns HTTP 404 for `/api/internal` paths; internal Java-to-Worker traffic continues directly over the Docker network.

The HTTP listener only returns 308 redirects; application pages and APIs are served over HTTPS. Ports 81/444 are the defaults to avoid conflicts with other local projects. Use 80/443 for a public domain that should work without explicit ports. HSTS is enabled only for standard 80/443 deployments so browsers do not upgrade a non-standard HTTP port to the wrong HTTPS port.

After the first IP-mode startup, export the public root certificate of the internal CA:

```bash
docker compose cp caddy:/data/caddy/pki/authorities/local/root.crt ./caddy-root.crt
```

Install only `root.crt`; never copy or distribute the root private key from the Caddy data volume. Common system trust commands are shown below. Restart browsers after installation.

```bash
# macOS
sudo security add-trusted-cert -d -r trustRoot \
  -k /Library/Keychains/System.keychain caddy-root.crt

# Windows (administrator command prompt)
certutil -addstore -f Root caddy-root.crt

# Debian/Ubuntu
sudo cp caddy-root.crt /usr/local/share/ca-certificates/base-ai-caddy.crt
sudo update-ca-certificates
```

Clients such as Firefox may use a separate trust store and require browser-level import. The Caddy CA and automatically-issued multi-SAN IP certificate are persisted in a named volume, so ordinary container rebuilds retain the root. Changing `APP_PLATFORM_SHORT_NAME`, running `docker compose down -v`, or deleting the Caddy data volume creates a new CA that every client must trust again.

All four runtime containers use non-root users. Linux capabilities are removed from the backend, frontend, and Worker; Caddy retains only `NET_BIND_SERVICE` for port 80. Base images are digest-pinned, production images omit unnecessary package managers, and the repository includes weekly Dependabot updates plus Trivy source, secret, configuration, and image scans in GitHub Actions.

After the first initialization, use the username configured by `APP_SEED_ADMIN_USERNAME` and the password configured by `APP_SEED_ADMIN_PASSWORD`. The initial administrator, role, permission tree, root department, and model-type dictionary are seeded automatically.

When `APP_SEED_ADMIN_PASSWORD_SYNC_ENABLED` is unset or `false`, the seed password is used only to create the initial administrator and never overwrites an existing password. When explicitly set to `true`, the application checks the existing administrator password at every startup and synchronizes it to `APP_SEED_ADMIN_PASSWORD` when they differ. In this mode, a password changed through the administration UI will be replaced by the environment value on the next startup.

To stop the application:

```bash
docker compose down
```

## Development and Tests

Run the backend test suite:

```bash
cd backend
mvn test -B
```

Run the Python worker tests with Python 3.12:

```bash
cd python-worker
python3.12 -m pytest
```

Run the complete frontend Node test suite from the repository root:

```bash
node --test frontend/test/*.test.mjs frontend/tests/*.test.js
```

Rebuild the Docker environment after code changes:

```bash
docker compose up --build -d
```

## Repository Layout

```text
backend/                        Spring Boot API and platform services
backend/config/                 Default backend runtime configuration
database/postgresql/            Reference PostgreSQL schema scripts
frontend/                       Vue administration console and API proxy
python-worker/                  FastAPI LLM worker
.env.example                    Environment variable template
docker-compose.yml              Application container definitions
TEST_REPORT.md                  Test baseline and execution history
```

## Production Notes

- Keep the production environment file outside the repository and restrict its filesystem permissions.
- Use separate least-privilege accounts for the MySQL system database and PostgreSQL business database.
- Rotate all example credentials before the first startup.
- Keep provider credentials out of source code, shell history, logs, and Git history.
- Configure a dedicated `APP_API_KEY_HASH_SECRET`; the encryption-key fallback is no longer supported.
- `LLM_LOG_CONTENT` is disabled by default. Enable it only after data and log-access review, and restrict task-log access because redacted model content can still contain sensitive business data.
- Plugin Workers only join the internal `outbound-network` and reach `OUTBOUND_ALLOWED_DOMAINS` through the authenticated outbound gateway; an empty allowlist denies all external access.
- For encryption-key rotation, add the new key to `APP_CONFIG_ENCRYPTION_KEYS` and switch `APP_CONFIG_ENCRYPTION_ACTIVE_KEY_ID`; keep prior keys until legacy `enc:` values have been rewritten progressively.
- Back up MySQL task/log data and PostgreSQL automation logs before schema or application upgrades.
- Override `MAVEN_IMAGE`, `JRE_IMAGE`, `NODE_IMAGE`, `PYTHON_IMAGE`, `GOPROXY`, `ALPINE_MIRROR`, or Python package-mirror settings when public registries and package proxies are unavailable. Caddy builds use `https://goproxy.cn,direct` for Go modules and `https://mirrors.tuna.tsinghua.edu.cn/alpine` for Alpine packages by default.

## License

This project is licensed under the Apache License 2.0. See `LICENSE` for details.
