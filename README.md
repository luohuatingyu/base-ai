# Base AI

[English](README.md) | [简体中文](README.zh-CN.md)

Base AI is an extensible administration and AI integration platform. It combines a Vue management console, a Spring Boot system service, and a Python LLM worker behind a Docker Compose deployment.

The platform provides identity and access management, OpenAI-compatible model routing, API key access, task tracing, audit logs, and scheduled HTTP automation. MySQL stores platform data, PostgreSQL stores automation data, and Redis stores disposable session state.

## Features

- Role-based access control with users, roles, menus, departments, positions, and data scopes.
- English and Simplified Chinese interfaces and localized API messages.
- Model provider, model, and capability-route management from the administration console.
- Text and multimodal chat through OpenAI-compatible providers, with candidate failover, concurrency limits, and token accounting.
- Bearer-token sessions and scoped `X-API-Key` credentials with expiry, revocation, IP allowlists, and rate limits.
- Cross-service task tracing, cancellation, recovery, operation logs, and login logs.
- Manual and Cron-based HTTP automation with encrypted request configuration and outbound-host controls.
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
  |                  +--> MySQL: identity, configuration, models, tasks, and logs
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
- System tasks, Java/Python trace records, operation logs, and login logs.

JPA manages platform entities, while `backend/src/main/resources/system-schema.sql` initializes the task and log tables. Create the target database before starting the application and grant the configured account schema-management privileges.

### PostgreSQL business database

PostgreSQL is reserved for subordinate business modules. The current platform stores API-trigger configurations and execution logs there. The backend runs `backend/src/main/resources/api-trigger-schema.sql` idempotently at startup, so the configured account requires DDL privileges.

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

Set `LLM_LOG_CONTENT=false` in environments where prompts and model responses must not be written to application logs. When content logging is disabled, the worker records metadata and a response digest instead.

## API Key Access

External systems can call endpoints explicitly annotated as API-key accessible. Create, authorize, rotate, disable, or revoke keys from **System Management > API Key Management**.

```bash
curl -X POST http://localhost/api/ai/chat \
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
- API key management endpoints themselves accept Bearer tokens only.
- `APP_API_KEY_HASH_SECRET` should be a dedicated secret. If omitted, the application reuses `APP_CONFIG_ENCRYPTION_KEY`.

The currently exposed API-key endpoints are AI chat invocation and production API-trigger execution.

## Task Tracing and Audit Logs

- Java creates or propagates `X-Request-Id` for each HTTP request.
- Tracked operations receive a separate `traceId` that joins Java, Python, and database records.
- Calls to the worker propagate request, parent-trace, Python-trace, and internal-authentication headers.
- `@TraceType` describes task type and trigger metadata; `@TraceIgnored` excludes endpoints that should not create tasks.
- Task state covers start, success, failure, cancellation, and forced termination.
- Parent cancellation propagates to the corresponding Python task without terminating the shared worker process.
- Startup recovery and scheduled checks mark abandoned tasks as failed after the heartbeat timeout.
- Operation and login logs are managed separately from task traces.

`trace-tracking-exclusions.yml` defines HTTP methods and paths excluded from automatic task creation.

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
   APP_SEED_ADMIN_PASSWORD=<at-least-10-secure-characters>
   APP_CONFIG_ENCRYPTION_KEY=<base64-encoded-32-byte-key>
   APP_API_KEY_HASH_SECRET=<at-least-32-random-characters>
   PYTHON_WORKER_INTERNAL_TOKEN=<at-least-24-random-characters>
   ```

The API key hash secret is optional only because it falls back to the encryption key; a separate value is recommended for production.

### Environment variable groups

- **Compose:** `COMPOSE_PROJECT_NAME`.
- **MySQL:** `MYSQL_URL`, `MYSQL_USERNAME`, `MYSQL_PASSWORD`.
- **PostgreSQL:** `POSTGRES_URL`, `POSTGRES_USERNAME`, `POSTGRES_PASSWORD`, `POSTGRES_POOL_SIZE`.
- **Redis:** `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_DATABASE`, `REDIS_TIMEOUT`.
- **Branding and locale:** `APP_PLATFORM_CODE`, `APP_PLATFORM_NAME_EN`, `APP_PLATFORM_NAME_ZH`, `APP_PLATFORM_SHORT_NAME`, `APP_DEFAULT_LOCALE`.
- **Authentication and encryption:** `APP_TOKEN_SECRET`, `APP_TOKEN_EXPIRE_MINUTES`, `APP_SEED_ADMIN_USERNAME`, `APP_SEED_ADMIN_PASSWORD`, `APP_CONFIG_ENCRYPTION_KEY`, `APP_API_KEY_HASH_SECRET`.
- **Worker and model calls:** `PYTHON_WORKER_INTERNAL_TOKEN`, `JAVA_INSTANCE_ID`, `PYTHON_WORKER_INSTANCE_ID`, `LLM_TIMEOUT_SECONDS`, `LLM_LOG_CONTENT`.
- **Route health checks:** `LLM_ROUTE_HEALTH_CHECK_ENABLED`, `LLM_ROUTE_HEALTH_CHECK_INTERVAL_MS`.
- **Task tracing and logging:** `TRACE_TRACKING_EXCLUSIONS_FILE`, `TRACE_LOG_PERSIST_LEVEL`, `TRACE_LOG_QUEUE_CAPACITY`, `TRACE_LOG_BATCH_SIZE`, `TRACE_LOG_FLUSH_INTERVAL_MS`, `TRACE_LOG_RETENTION_DAYS`, `TRACE_HEARTBEAT_TIMEOUT_SECONDS`.
- **Automation:** `API_TRIGGER_SCHEDULER_POOL_SIZE`, `API_TRIGGER_LOCK_SECONDS`, `API_TRIGGER_RESULT_MAX_LENGTH`.
- **Ports and images:** `BACKEND_PORT`, `FRONTEND_PORT`, `FRONTEND_BACKEND_URL`, plus the optional image and package-mirror variables in `.env.example`.

`APP_DEFAULT_LOCALE` accepts `en-US` or `zh-CN` and defaults to `en-US`.

## Start with Docker Compose

Validate the resolved configuration before starting the stack. Be aware that `docker compose config` expands secrets, so do not share its output.

```bash
docker compose config --quiet
docker compose up --build -d
docker compose ps
```

After all services are healthy:

- Web console: <http://localhost>
- Backend API: <http://localhost:8080>
- Backend health check: <http://localhost:8080/api/open/health>
- Frontend health check: <http://localhost/health>

Use the username configured by `APP_SEED_ADMIN_USERNAME` and the password configured by `APP_SEED_ADMIN_PASSWORD`. The initial administrator, role, permission tree, root department, and model-type dictionary are seeded automatically.

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
database/postgresql/            Reference PostgreSQL schema scripts
frontend/                       Vue administration console and API proxy
python-worker/                  FastAPI LLM worker
.env.example                    Environment variable template
docker-compose.yml              Application container definitions
trace-tracking-exclusions.yml   Default task-tracking exclusions
TEST_REPORT.md                  Test baseline and execution history
```

## Production Notes

- Keep the production environment file outside the repository and restrict its filesystem permissions.
- Use separate least-privilege accounts for the MySQL system database and PostgreSQL business database.
- Rotate all example credentials before the first startup.
- Keep provider credentials out of source code, shell history, logs, and Git history.
- Use a dedicated `APP_API_KEY_HASH_SECRET` instead of relying on the encryption-key fallback.
- Review `LLM_LOG_CONTENT`; the provided template enables content logging, which may be inappropriate for sensitive workloads.
- Back up MySQL task/log data and PostgreSQL automation logs before schema or application upgrades.
- Override `MAVEN_IMAGE`, `JRE_IMAGE`, `NODE_IMAGE`, `PYTHON_IMAGE`, or Python package-mirror settings when public registries are unavailable.

## License

This project is licensed under the Apache License 2.0. See `LICENSE` for details.
