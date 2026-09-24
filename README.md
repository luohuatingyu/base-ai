# Base AI

[English](README.md) | [简体中文](README.zh-CN.md)

Base AI is an extensible administration and AI integration platform. It combines a Vue management console, a Spring Boot system service, and a Python LLM worker behind a Docker Compose deployment.

The platform provides identity and access management, OpenAI-compatible model routing, API key access, executable visual workflows, task tracing, audit logs, and scheduled HTTP automation. MySQL stores platform data, workflows, and automation data, PostgreSQL is reserved for subordinate business modules, and Redis stores disposable session state.

## Features

- Role-based access control with users, roles, menus, departments, positions, and data scopes.
- English and Simplified Chinese interfaces and localized API messages.
- Model provider, model, and capability-route management from the administration console.
- Text and multimodal chat through OpenAI-compatible providers, with candidate failover, concurrency limits, and token accounting.
- HttpOnly-cookie browser sessions with signed CSRF protection, compatible Bearer-token clients, and scoped `X-API-Key` credentials with expiry, revocation, IP allowlists, and rate limits.
- Cross-service task tracing, cancellation, recovery, operation logs, and login logs.
- Manual and Cron-based HTTP automation with encrypted request configuration and outbound-host controls.
- MySQL-backed macOS Device Agent pairing, anonymous iOS device pools, IDA/Appium automation, Remote XPC Registry, and health diagnostics.
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
  |                  +--> MySQL: identity, configuration, models, workflows, tasks, logs, and automation
  |                  +--> PostgreSQL: reserved for subordinate business modules
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
- Device Agent registrations, one-time pairings, health, device-scoped IDA commands, anonymous device pools, Registry status, and audits.
- System tasks, Java/Python trace records, operation logs, and login logs.

Flyway manages the complete MySQL schema through the immutable V1-V31 migration chain, including JPA platform entities, task traces, logs, built-in workflow node templates, and Device Agent automation management. Existing non-empty databases are baselined at version 0 before migrations run; Hibernate runs in `validate` mode and never mutates tables. Create the target database before starting the application. `MYSQL_MIGRATION_*` may use a DDL-capable migration account while `MYSQL_*` uses a least-privilege runtime account; omitted migration values fall back to the runtime connection for compatibility.

### PostgreSQL business database

PostgreSQL is reserved for subordinate business modules and is disabled by default. API-trigger configurations and execution logs are stored in MySQL, so a disabled PostgreSQL connection is neither created nor included in readiness. Set `POSTGRES_ENABLED=true` only for a module that needs the shared `postgresqlJdbcTemplate`. The platform runs no Flyway migration against PostgreSQL because the target schema may be shared with another application.

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
- Every Device Agent receives an independent encrypted HMAC secret; signed requests include a body digest, timestamp, and Redis-backed one-time nonce.
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
curl -X POST http://127.0.0.1:80/api/ai/chat \
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

2. Configure the external MySQL and Redis connections. PostgreSQL is optional and requires `POSTGRES_ENABLED=true`.

3. Replace every placeholder credential. Generate an encryption key with:

   ```bash
   openssl rand -base64 32
   ```

4. At minimum, review these security-sensitive values:

   ```dotenv
   APP_TOKEN_SECRET=<at-least-32-random-characters>
   APP_SEED_ADMIN_PASSWORD=<optional-strong-bootstrap-password>
   APP_SEED_ADMIN_PASSWORD_SYNC_ENABLED=false
   APP_CONFIG_ENCRYPTION_KEY=<base64-encoded-32-byte-key>
   APP_API_KEY_HASH_SECRET=<at-least-32-random-characters>
   PYTHON_WORKER_INTERNAL_TOKEN=<at-least-24-random-characters>
   DIFY_PLUGIN_WORKER_INTERNAL_TOKEN=<at-least-24-random-characters>
   N8N_PLUGIN_WORKER_INTERNAL_TOKEN=<at-least-24-random-characters>
   ADAPTER_MANAGER_INTERNAL_TOKEN=<at-least-24-random-characters>
   OUTBOUND_GATEWAY_TOKEN=<at-least-24-random-characters>
   PLUGIN_SANDBOX_EGRESS_SIGNING_KEY=<at-least-32-random-characters>
   ```

The API key hash secret is optional only because it falls back to the encryption key; a separate value is recommended for production.

### Environment variable groups

- **MySQL:** `MYSQL_URL`, `MYSQL_USERNAME`, `MYSQL_PASSWORD`, plus optional `MYSQL_MIGRATION_URL`, `MYSQL_MIGRATION_USERNAME`, and `MYSQL_MIGRATION_PASSWORD`.
- **PostgreSQL:** `POSTGRES_ENABLED`, `POSTGRES_URL`, `POSTGRES_USERNAME`, `POSTGRES_PASSWORD`, `POSTGRES_POOL_SIZE`.
- **Redis:** `REDIS_HOST`, `REDIS_PORT`, `REDIS_USERNAME`, `REDIS_PASSWORD`, `REDIS_DATABASE`, `REDIS_TIMEOUT`, `REDIS_SSL`.
- **Branding and locale:** `APP_PLATFORM_CODE`, `APP_PLATFORM_NAME_EN`, `APP_PLATFORM_NAME_ZH`, `APP_PLATFORM_SHORT_NAME`, `APP_DEFAULT_LOCALE`.
- **Authentication and encryption:** `APP_TOKEN_SECRET`, `APP_TOKEN_EXPIRE_MINUTES`, `APP_SESSION_COOKIE_SECURE`, `APP_SEED_ADMIN_USERNAME`, `APP_SEED_ADMIN_PASSWORD`, `APP_SEED_ADMIN_PASSWORD_SYNC_ENABLED`, `APP_CONFIG_ENCRYPTION_KEY`, `APP_CONFIG_ENCRYPTION_KEYS`, `APP_CONFIG_ENCRYPTION_ACTIVE_KEY_ID`, `APP_API_KEY_HASH_SECRET`.
- **Worker and model calls:** `PYTHON_WORKER_INTERNAL_TOKEN`, `DIFY_PLUGIN_WORKER_INTERNAL_TOKEN`, `N8N_PLUGIN_WORKER_INTERNAL_TOKEN`, `ADAPTER_MANAGER_INTERNAL_TOKEN`, `OUTBOUND_GATEWAY_TOKEN`, `PLUGIN_SANDBOX_EGRESS_SIGNING_KEY`, `PLUGIN_PACKAGE_ALLOWED_DOMAINS`, `PLUGIN_SANDBOX_PIDS_LIMIT`, `PLUGIN_SANDBOX_MEMORY_LIMIT`, `PLUGIN_SANDBOX_CPU_LIMIT`, `JAVA_INSTANCE_ID`, `PYTHON_WORKER_INSTANCE_ID`, `LLM_TIMEOUT_SECONDS`, `LLM_LOG_CONTENT`.
- **Route health checks:** `LLM_ROUTE_HEALTH_CHECK_ENABLED`, `LLM_ROUTE_HEALTH_CHECK_INTERVAL_MS`.
- **Task tracing and logging:** `TRACE_TRACKING_EXCLUSIONS_FILE`, `TRACE_LOG_PERSIST_LEVEL`, `TRACE_LOG_QUEUE_CAPACITY`, `TRACE_LOG_BATCH_SIZE`, `TRACE_LOG_FLUSH_INTERVAL_MS`, `TRACE_LOG_RETENTION_DAYS`, `TRACE_HEARTBEAT_TIMEOUT_SECONDS`.
- **Automation:** `API_TRIGGER_SCHEDULER_POOL_SIZE`, `API_TRIGGER_LOCK_SECONDS`, `API_TRIGGER_RESULT_MAX_LENGTH`.
- **Device Agent:** `DEVICE_AGENT_NONCE_TTL_SECONDS`, `DEVICE_AGENT_SIGNATURE_CLOCK_SKEW_SECONDS`, `DEVICE_AGENT_HEARTBEAT_STALE_SECONDS`, `DEVICE_AGENT_PAIRING_TTL_SECONDS`, `DEVICE_AGENT_REGISTRY_DEFAULT_PORT`, plus pinned runtime versions `AGENT_NODE_VERSION`, `AGENT_APPIUM_SPEC`, and `AGENT_XCUITEST_SPEC`.
- **Workflow:** `WORKFLOW_EXECUTOR_POOL_SIZE`, `WORKFLOW_EXECUTOR_QUEUE_CAPACITY`, `WORKFLOW_MAX_NODES`, `WORKFLOW_MAX_ITERATIONS`, `WORKFLOW_MAX_AGENT_STEPS`, `WORKFLOW_MAX_RECURSION_DEPTH`, `WORKFLOW_MAX_PAYLOAD_BYTES`, `WORKFLOW_MAX_WAIT_SECONDS`, `WORKFLOW_MAX_EXECUTION_STEPS`, `WORKFLOW_MAX_RUN_LOG_BYTES`, `WORKFLOW_LEASE_SECONDS`, `WORKFLOW_WEBHOOK_MAX_BODY_BYTES`, `WORKFLOW_WEBHOOK_RATE_LIMIT_PER_MINUTE`, and `WORKFLOW_TRIGGER_DELIVERY_RETENTION_DAYS`.
- **HTTP ingress and images:** `HTTP_PORT`, `FRONTEND_BACKEND_URL`, plus the optional image and package-mirror variables in `.env.example`. Frontend serves HTTP directly; terminate HTTPS in an external reverse proxy when needed. Backend port 8080 and Worker port 8000 are internal-only.

`APP_DEFAULT_LOCALE` accepts `en-US` or `zh-CN` and defaults to `en-US`.
Docker Compose derives the project name from `APP_PLATFORM_SHORT_NAME` and normalizes it to lowercase. Runtime container names use the same prefix, including Backend, Workers, Frontend, the Adapter Manager, and its isolated Supervisor.

### API trigger redirects

API triggers support HTTP and HTTPS destinations. Each redirect hop is revalidated against the Host and network-address policy, at most five redirects are followed, cross-Host redirects are rejected, and HTTPS cannot downgrade to HTTP.

## Start with Docker Compose

Validate the resolved configuration before starting the stack. Be aware that `docker compose config` expands secrets, so do not share its output.
Set `APP_IMAGE_REVISION` to the full commit being built. Compose uses that value as every local image tag and writes it to the OCI revision label.

```bash
export APP_IMAGE_REVISION="$(git rev-parse HEAD)"
docker compose config --quiet
docker compose up --build -d
docker compose ps
```

Frontend serves the HTTP entry and `/api` proxy directly. Standard Docker Compose commands are sufficient; terminate HTTPS in an external reverse proxy when needed. `docker compose up --build -d` builds and starts all ten core, deployment, and plugin services without extra profiles. Deployment Agent, Adapter Manager, and Outbound Gateway run continuously; the plugin page controls the Dify/n8n Workers, and the backend reconciles their state to the saved switches (disabled Workers may therefore stop after startup). These Workers host plugin ABIs, not complete Dify/n8n platforms. The old standalone Broker/Supervisor are rollback-only services in `legacy-plugin-adapters`; do not run them alongside the combined Manager.

Before startup, set `APP_IMAGE_REVISION` in `.env` to the full code commit, configure the internal tokens and `PLUGIN_SANDBOX_EGRESS_SIGNING_KEY`, and select the rootless Docker context hosting the entire stack. `ADAPTER_DOCKER_SOCKET` and `DEPLOYMENT_DOCKER_SOCKET` must be paths on that Docker host; socket group IDs must match their IDs visible inside containers. The Broker verifies `name=rootless` before opening control sockets. A normal Docker Desktop daemon does not satisfy this check. Keep the selected Docker VM running so subsequent plain Compose commands use the same images, volumes, and networks. Plugin invocations can create additional temporary sandbox containers.

### iOS Device Automation Agent

The **Automation / Device Agent Management** page creates a 15-minute one-time pairing code and an installation command for a target Mac. Frontend distributes self-contained Python 3.12 and Node.js 22 runtimes for Apple Silicon and Intel; the installer pins Appium 3.7 and XCUITest Driver 12.11 and configures a login-level Appium LaunchAgent plus a root-owned Remote XPC Registry LaunchDaemon. The target Mac needs full Xcode, and each iPhone/iPad must trust the Mac and enable Developer Mode.

The management page dispatches device-scoped `SETUP_IDA` and `START_IDA` commands, supports stable per-device ports and conflict detection, encrypts IDA signing metadata, manages Registry online/offline/recreate actions, and cancels active commands. Raw UDIDs never enter the backend, logs, or UI; they are used only in Agent memory and restricted local Unix Socket requests to the root helper. WeCom accounts, friends, and business tasks remain out of scope.

The Agent executes only the documented fixed command set: device discovery, diagnostics, IDA setup/start, Registry lifecycle, configuration refresh, relocation, and signed self-upgrade. Device selection uses Agent-scoped SHA-256 identifiers while raw iOS UDIDs stay on the target Mac. Before starting IDA, operators must ensure the selected device is not controlled by another automation process.

### Data synchronization and server management

- Data synchronization uses MySQL or PostgreSQL entries from Data Source Management. The target connection must explicitly enable `allowWrite`. New plans must select an enabled managed server owned by the plan owner. Table lookup, preflight, manual runs, Spring six-field Cron schedules, and retries all execute in a one-time Backend worker container on that server. Legacy plans without a server remain compatible by running on the platform-local target. Full replacement requires reconfirmation, and columns, types, and keys are checked before writes.
- Administrators and the built-in `OPS` role can use synchronization and server management. Other explicitly authorized users can only manage resources they own. SSH keys, passwords, passphrases, and host-key configuration are encrypted with the platform AES-GCM key, and list responses only contain masks.
- Servers are added and maintained manually on the management page, with SSH as the default for new entries. Compose paths are not required. Pasted private keys and locally selected private-key files enter the same encrypted credential field; the browser never submits the local file path. The connection test verifies only Agent or SSH connectivity.
- Open **Server Management → Credential Management** to store a label, RSA type, public key, private key, certificate, account, password, and optional private-key passphrase. Account/password-only entries are supported. New SSH servers select an enabled credential belonging to the same owner; its account is filled automatically, or entered on the server if absent. Legacy servers keep their existing configuration until switched to a shared credential. Subsequent connections resolve the latest credential values. Referenced credentials cannot be deleted or disabled; secret fields left blank preserve their saved values. Create and select a replacement credential to remove a secret. Lists expose no secrets; explicit plaintext viewing is restricted to administrators. Each key/certificate material is limited to 32 KiB. Certificates are stored only and do not enable SSH certificate authentication; key generation and key-pair verification are not provided.
- Users with server-test permission can open the resource-monitoring dialog to query CPU, system load, memory, disk, and uptime. Snapshots are not persisted; monitoring does not query Docker containers or require Docker socket access.
- Server deployment automatically detects `docker-compose.yml` or `compose.yml` from the fixed local workspace, existing Compose container metadata, or a bounded search of the SSH account home, `/opt`, and `/srv`. A unique Base AI project is preferred; missing or ambiguous projects fail without executing a deployment. Only `up -d --no-build` is executed, arbitrary shell commands are never accepted, and revisions must be valid Docker image tags, so a full Git commit hash is recommended.
- Local or SSH deployment requires the isolated Agent. Point `DEPLOYMENT_DOCKER_SOCKET` at a rootless Docker socket, set its numeric group ID and a random internal token of at least 24 characters, then run:

```bash
export DEPLOYMENT_AGENT_INTERNAL_TOKEN="$(openssl rand -hex 32)"
docker compose up --build -d
```

Local mode always detects the project under `/workspace` inside the Agent; `DEPLOYMENT_PROJECT_DIR` selects its read-only source mount. SSH supports a private key, an account password, or both. Encrypted private keys use a separate optional passphrase. Combined authentication supports servers requiring a public key followed by an account password; the server determines whether both are mandatory. Host keys are automatically trusted on every connection, including after rotation; no fingerprint maintenance is required and server identity is not pinned. Existing fingerprint fields are ignored. Remote deployment requires Docker Compose, exactly one detectable target project, and pre-pulled revision images.

Remote data synchronization uses the same Agent. `DATA_SYNC_WORKER_IMAGE` defaults to the current Compose project's Backend image. Every selected server must have Docker installed, already contain that image, and be able to reach both source and target databases. The Agent launches a one-time container through a fixed Java entry point with a read-only root filesystem, non-root user, dropped capabilities, resource limits, and a 15-minute timeout. Database credentials travel only through standard input and never enter command arguments, environment variables, job results, or logs. Cancellation removes the fixed-name worker container, and Backend restarts resume status reconciliation through the persisted Agent job ID.

After all services are healthy:

- Web console: <http://127.0.0.1:80>
- Backend API and open platform: <http://127.0.0.1:80/api>
- Unified health check: <http://127.0.0.1:80/api/open/health>
- Backend liveness check: <http://127.0.0.1:80/api/open/health/live>
- Backend readiness check: <http://127.0.0.1:80/api/open/health/ready>
- Frontend health check: <http://127.0.0.1:80/health>

The unified and readiness checks return HTTP 503 unless MySQL, PostgreSQL, Redis, and the Python Worker are all available. Public ingress returns HTTP 404 for `/api/internal` paths; internal Java-to-Worker traffic continues directly over the Docker network.

Frontend serves the web console, API, health endpoint, and Device Agent packages over HTTP on the configured `HTTP_PORT` (80 by default). It does not create certificates or redirect HTTP requests to HTTPS. If public HTTPS is required, place a separately managed reverse proxy in front of this HTTP listener.

Network-facing application containers use non-root users and drop unnecessary Linux capabilities; the networkless Docker Broker remains an explicitly isolated host-privilege boundary. Base images are digest-pinned directly in Compose and Dockerfiles, production images omit unnecessary package managers, and the repository includes weekly Dependabot updates plus Trivy source, secret, configuration, and image scans in GitHub Actions.

The initial administrator, role, permission tree, root department, and model-type dictionary are seeded automatically. If `APP_SEED_ADMIN_PASSWORD` is empty or weak on first initialization, the backend generates a 32-character strong password in the protected `bootstrap-secrets` volume. Retrieve it once with `docker compose exec backend sh -c 'cat /app/bootstrap/admin-password'`, sign in with `APP_SEED_ADMIN_USERNAME`, and change it immediately.

When `APP_SEED_ADMIN_PASSWORD_SYNC_ENABLED` is unset or `false`, an existing administrator does not require a seed password and is never overwritten. When explicitly set to `true`, `APP_SEED_ADMIN_PASSWORD` must contain uppercase and lowercase letters, a number, and a symbol; it replaces a different administrator password at startup.

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
export APP_IMAGE_REVISION="$(git rev-parse HEAD)"
docker compose up --build -d
```

## Repository Layout

```text
backend/                        Spring Boot API and platform services
backend/config/                 Default backend runtime configuration
frontend/                       Vue administration console and API proxy
python-worker/                  FastAPI LLM worker
adapter-manager/                Adapter lifecycle manager and isolated Supervisor
.env.example                    Environment variable template
docker-compose.yml              Application container definitions
TEST_REPORT.md                  Test baseline and execution history
```

## Production Notes

- Keep the production environment file outside the repository and restrict its filesystem permissions.
- Keep `APP_CORS_ALLOWED_ORIGINS` empty for the normal same-origin proxy deployment. If a separate browser origin is required, list each exact HTTP or HTTPS origin without paths or wildcards.
- Review the container memory, CPU, and PID limits against measured production load before increasing them.
- Use separate least-privilege accounts for the MySQL system database and PostgreSQL business database.
- Rotate all example credentials before the first startup.
- Keep provider credentials out of source code, shell history, logs, and Git history.
- Configure a dedicated `APP_API_KEY_HASH_SECRET`; the encryption-key fallback is no longer supported.
- `LLM_LOG_CONTENT` is disabled by default. Enable it only after data and log-access review, and restrict task-log access because redacted model content can still contain sensitive business data.
- Dify and n8n control Workers do not execute plugin code, mount package directories, or hold outbound proxy credentials. Each package inspection or invocation runs in a fresh non-root container with a read-only root filesystem, dropped capabilities, resource limits, a fingerprint-specific volume, and an operation-specific internal network. Invocation mounts the package volume read-only.
- Plugin sandboxes receive only a short-lived proxy credential bound to their source, package fingerprint, operation, and administrator-approved exact domains. Package installation uses `PLUGIN_PACKAGE_ALLOWED_DOMAINS`; normal invocation uses only the approved admission domains, always intersected with `OUTBOUND_ALLOWED_DOMAINS`. Rotate `PLUGIN_SANDBOX_EGRESS_SIGNING_KEY` as a dedicated secret shared only by the Broker and outbound gateway.
- The network-facing Adapter Manager has no Docker socket, Compose file, or environment-file access. Its networkless Supervisor accepts only typed N8N/Dify commands over a private Unix socket. A separate networkless Broker is the sole component that mounts the Docker socket and accepts only fixed lifecycle and sandbox commands; that remaining host-level privilege must still be protected operationally.
- For encryption-key rotation, add the new key to `APP_CONFIG_ENCRYPTION_KEYS` and switch `APP_CONFIG_ENCRYPTION_ACTIVE_KEY_ID`; keep prior keys until legacy `enc:` values have been rewritten progressively.
- Back up MySQL task, log, and automation data before schema or application upgrades.
- Override `GOPROXY`, `ALPINE_MIRROR`, or language package-mirror settings when public package proxies are unavailable. Compose base images remain digest-pinned; update their reviewed digests in source rather than overriding them from a deployment environment file.

## License

This project is licensed under the Apache License 2.0. See `LICENSE` for details.
