# AGENTS.md: guide for coding agents working on this repository

A mini ERP: purchasing, multi-warehouse stock on an append-only ledger, sales with stock
reservations, invoicing and PDF/Excel reports, with a React web app signed in through the API
acting as its backend-for-frontend. It's an enterprise-style reference project, and
the code doubles as learning material (the owner is learning Java), so keep explanatory comments
and idiomatic code. The README is the human-facing overview; this file is the operating manual.
When they disagree, check the code and fix the doc.

## Status: what's built and what's left

**Done** (both on 2026-11-23):

1. **Milestone 1, backend core.** Normalised Postgres schema (Flyway V1..V5); append-only stock
   ledger with on-hand / reserved / available balances maintained by a database trigger; purchase
   and sales order state machines; race-safe reservations; two-step inter-warehouse transfers;
   reorder points and low-stock alerts through domain events; automatic invoices with gap-free
   numbering; stock report (current or "as of") as PDF and Excel, ledger export, invoice PDF,
   reconciliation audit; Keycloak roles with segregation of duties; Docker Compose; `demo.ps1`.
2. **Milestone 2, React web UI with BFF sign-in.** The API is the web app's backend-for-frontend
   (Keycloak code flow + PKCE as confidential client `erp-web`, server-side sessions in Postgres
   via Flyway V6, HttpOnly cookie, CSRF); a React SPA with a screen for every role's job; nginx
   (single origin, strict CSP); order responses say what the caller may do (`allowedActions`,
   `canEdit`, `canReceive`, `canShip`); `smoke-web.ps1`.

Verified state: 68 backend tests and 23 web tests green; the stack builds from a clean copy
(no `.env`, `node_modules`, `target`, `dist`) and `demo.ps1` and `smoke-web.ps1` pass against it.

**Known gaps in what's built** (fix these before or alongside new features):

- The signed-in screens have not been clicked through in a browser by an agent: sign-in is
  tested by `smoke-web.ps1` and the screens by component tests only. Do a manual pass first.
- Draft purchase and sales orders can be edited through the API (`PUT`, with `version`) but the
  UI has no edit form yet, although responses already carry `canEdit`.
- Master data is create-only: there are no endpoints to rename, edit or deactivate products,
  warehouses, bins or partners (the `active` columns and their UPDATE grants already exist).
- The CI workflow has never run (no remote repository). `smoke-web.ps1` has only been run on
  Windows PowerShell 5.1, so CI doesn't call it.

**What's left, in a sensible build order** (the README's "Not in this milestone"):

1. **Edit drafts in the UI** (warm-up). On the purchase and sales order pages, when `canEdit` is
   true, show the lines in `OrderLinesEditor` pre-filled from the order (`minorToText` for prices)
   and `PUT /api/{purchase|sales}-orders/{id}` with the order's `version`. On 409 `stale_version`
   offer a reload. Add a hook in `api/hooks.ts` and a Vitest test like `PurchaseOrderPage.test.tsx`.
2. **Purchase suggestions from low-stock alerts.** New migration V7: `product_suppliers`
   (product_id, supplier_id, preferred, uom_code, unit_price_minor, lead_time_days; composite FK
   to `product_units`) and `low_stock_alerts.purchase_order_id` (nullable FK). Endpoint
   `POST /api/alerts/{id}/purchase-order` (PURCHASING): lock the alert row, if it already has a
   PO return it (idempotent), else create a DRAFT PO for `reorder_quantity` from the preferred
   supplier and link it. Replenishment then depends on purchasing (check `ModularityTests` for
   cycles: purchasing must not depend on replenishment). Better still, let
   `ReplenishmentService.evaluate` use the inventory position (available + open PO quantity) so an
   alert with stock on order stops nagging. UI: a "Create purchase order" button on the alert.
3. **Backorders.** Today confirming is all-or-nothing. Allow partial confirmation: reserve what is
   available, record `backordered_base_quantity` on the line (V8, CHECK reserved + backordered +
   shipped = ordered), and allocate later: a listener on `StockLevelChanged` locks the oldest
   confirmed lines with backorders (`ORDER BY confirmed_at`, `FOR UPDATE SKIP LOCKED`) and posts
   RESERVE movements. Update the reconciliation's RESERVATION check and the order status machine
   (decide whether a backordered order is still CONFIRMED). Add a concurrency test: allocation
   racing with new confirmations must never over-reserve.
4. **Payments and credit notes.** Invoices stay immutable; add `payments` (invoice_id, amount,
   received_at, method) and derive the invoice's balance and status (OPEN, PARTIALLY_PAID, PAID)
   from them, with a state machine in Java. Credit notes: their own gap-free numbering per year
   (generalise `InvoiceNumbering` and the V4 triggers to a document type, e.g. `CN-2026-000001`),
   lines that reference invoice lines and can't exceed what was invoiced. Customer returns need a
   new movement type (e.g. RETURN: on hand +, bin, source = return line): a migration that replaces
   `stock_movements_type_valid` and `stock_movements_shape`, plus `MovementType` and `StockMovement`.
5. **Inventory valuation and FIFO costing.** Cost layers created by receipts (unit cost per base
   unit from the PO line, converted to a company base currency with an exchange-rate table),
   consumed FIFO by shipments, transfers and negative adjustments (an append-only consumption
   table written in the same transaction as the movement). Reports: stock value, cost of goods
   sold per invoice. Reconciliation: remaining layer quantities = on hand.
6. **Lot and serial tracking.** `lots` (product, lot code, expiry); `lot_id` on `bin_stock`'s key
   and on `stock_movements`; receipts capture the lot, picking becomes first-expiry-first-out, and
   a trace report answers "where did lot X go?".
7. **Multi-company tenancy.** `tenant_id` from a token claim on every table, composite foreign
   keys `(tenant_id, id)`, Hibernate `@TenantId` on entities, and the tenant in every plain-SQL
   query, trigger and report; invoice counters per (tenant, year). The owner's HRMS project
   (`C:\Projects\New folder (3)`) shows this pattern end to end.

Ops follow-ups: push to a remote and get CI green; make `smoke-web.ps1` work in PowerShell 7 and
add it to the end-to-end CI job; a Playwright browser test for the main flows.

## Map

| Path | What |
|---|---|
| `services/erp-api/` | The API: Java 25, Spring Boot 4.2, Spring Security 7 (JWT resource server + OAuth2 login as BFF), Spring Session JDBC, Hibernate 7, Flyway, Spring Modulith 2.2, OpenPDF 3, Apache POI 6, springdoc. One deployable, split into modules (below) |
| `services/erp-api/src/main/resources/db/migration/` | Flyway migrations V1..V6: the whole schema, triggers and grants (V6: browser sessions) |
| `web/` | The SPA: React 19, TypeScript 6, Vite 8, Tailwind 4, TanStack Query, React Router; served by nginx (`nginx.conf`: proxy + CSP). No OIDC library, no tokens |
| `infra/keycloak/erp-realm.json` | Realm `erp`: roles, clients `erp-web` (confidential, code + PKCE, secret `${KEYCLOAK_CLIENT_SECRET}`) and dev-only `erp-cli`, demo users (`${DEMO_USER_PASSWORD}`) |
| `infra/postgres/init/01-init.sh` | Logins `erp_owner` (owns schema `erp`, runs Flyway), `erp_app` (the API), `keycloak` (own DB) |
| `docker-compose.yml` | postgres, keycloak, erp-api, web (compose project `mini-erp`) |
| `scripts/` | `init-env.ps1` (creates `.env`, or adds settings new to `.env.example`), `lib.ps1` (helpers), `demo.ps1` (end-to-end flow over the API), `smoke-web.ps1` (browser sign-in, CSRF, sign-out), `get-token.ps1` |
| `.github/workflows/ci.yml` | `mvnw verify`, web lint/test/build, Trivy, compose + demo |

Modules are the direct sub-packages of `com.minierp` (Spring Modulith; `ModularityTests` fails
the build on a cycle or on use of another module's internals):

```
shared (OPEN: errors, Money, Quantities, Actor, security, events housekeeping)
catalog      products, units of measure, unit conversion
partners     suppliers, customers
inventory    warehouses, bins, StockLedger, stock queries, adjustments, transfers
purchasing   purchase orders, goods receipts             -> catalog, partners, inventory
sales        sales orders, reservations, shipments      -> catalog, partners, inventory
invoicing    invoices, gap-free numbering                -> sales (event + SalesQueries), partners, catalog
replenishment reorder rules, low-stock alerts            -> inventory (event + StockQueries), catalog
reporting    stock report PDF/XLSX, ledger XLSX, invoice PDF, reconciliation (read model)
```

Cross-module references are by id, never JPA associations. A module that must react to another
listens to its event (`ShipmentDispatched`, `StockLevelChanged`) instead of being called by it.

## Run it on a new machine

**Prerequisites**

| What | Version | Needed for |
|---|---|---|
| Windows 10 or 11 | | the helper scripts are PowerShell (on macOS/Linux, follow the steps by hand or try PowerShell 7; untested) |
| Docker Desktop | 4.x with Compose v2 and the WSL 2 backend (tested with Docker Engine 29.9) | everything: the stack, the Java build and tests, optionally the web tests |
| Windows PowerShell | 5.1 (built into Windows) | `init-env.ps1`, `demo.ps1`, `smoke-web.ps1`, `get-token.ps1` |
| Internet | | the first build downloads images, Maven and npm packages |
| Node.js | 24 (npm 11), **optional** | only for `npm run dev` (hot reload) or running the web tests without Docker |
| JDK / Maven | **not needed** | Java builds and tests run inside Docker (`eclipse-temurin:25-jdk`, Maven via `mvnw`) |

Give Docker at least 6 GB of memory and 10 GB of disk. These ports must be free on 127.0.0.1:
**5780** (web), **8780** (API), **8880** (Keycloak), **37432** (Postgres).

**From a clean copy** (a zip without `.env`, `node_modules`, `target`, `dist`; it must keep the
dot-files and dot-folders `.env.example`, `.mvn/`, `.github/`, `.dockerignore`, `.gitattributes`,
and `web/package-lock.json`, which `npm ci` needs):

```powershell
cd C:\Projects\mini-erp
# Only if the zip was downloaded from the internet (Windows blocks downloaded scripts):
Get-ChildItem -Recurse | Unblock-File
# Only if PowerShell refuses to run local scripts:
Set-ExecutionPolicy -Scope CurrentUser RemoteSigned

.\scripts\init-env.ps1             # creates .env from .env.example, with random secrets
docker compose up -d --build --wait   # first time 5-10 min: builds the API and web images
docker compose ps                  # postgres, keycloak, erp-api, web: all "healthy"
.\scripts\demo.ps1                 # purchase -> ... -> reconciliation; PDFs and Excel files in .\out\
.\scripts\smoke-web.ps1            # signs in like a browser, checks CSRF and sign-out
```

Don't copy `.env.example` to `.env` by hand: its secrets are empty on purpose, and compose
refuses to start with an empty password (`required variable ... is missing a value`).
`init-env.ps1` fills each empty entry with a random value and copies the others (`ERP_TIME_ZONE`).
The images build their own dependencies (Maven and npm run inside the Dockerfiles), so nothing
in `target/`, `node_modules/` or `dist/` is needed.

| URL | What | Sign in with |
|---|---|---|
| http://localhost:5780 | the web app | a demo user: `adam`, `pam`, `max`, `bea`, `walt`, `sue` or `finn`; password = `DEMO_USER_PASSWORD` in `.env` |
| http://localhost:8780/swagger-ui.html | the API (Swagger UI) | "Authorize" with a token from `.\scripts\get-token.ps1 walt` |
| http://localhost:8880 | Keycloak admin console | `admin`, password = `KEYCLOAK_ADMIN_PASSWORD` in `.env` |
| 127.0.0.1:37432 | Postgres (database `erp`, schema `erp`) | `erp_app` / `DB_APP_PASSWORD` (read-mostly) or `postgres` / `POSTGRES_SUPERUSER_PASSWORD` |

**Every test suite**

```powershell
# Backend: Checkstyle, javac -Werror, 68 tests against Postgres in Testcontainers
# (~5 min; the first run also downloads Maven and the dependencies into the mini-erp-m2 volume)
docker run --rm -v "${PWD}\services\erp-api:/workspace" -v mini-erp-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /workspace eclipse-temurin:25-jdk sh mvnw -B verify

# Web, without Node installed (node_modules stays inside the container)
docker run --rm -v "${PWD}\web:/app" -v /app/node_modules -w /app node:24-alpine sh -c "npm ci && npm run lint && npm test && npm run build"

# Web, with Node 24
cd web; npm ci; npm run lint; npm test; npm run build; cd ..

# Against the running stack
.\scripts\demo.ps1
.\scripts\smoke-web.ps1
```

**Stop, reset, troubleshoot**

- `docker compose stop` / `docker compose up -d`: pause and resume, data kept.
- `docker compose down -v`: delete everything, including the database and Keycloak's realm (the
  realm is re-imported from `infra/keycloak/erp-realm.json` on the next start).
- The passwords in `.env` are baked into the Postgres and Keycloak volumes at their first start.
  If you delete or regenerate `.env`, also run `docker compose down -v`, or the services can't log in.
- "port is already allocated": another program holds 5780/8780/8880/37432; find it with
  `Get-NetTCPConnection -State Listen -LocalPort 5780`.
- Keycloak's first start (it builds its server, creates its tables and imports the realm) takes
  one to six minutes depending on the machine; `--wait` waits for every healthcheck, and the
  healthchecks give Keycloak 5 minutes and the API 2 minutes of grace before failures count.
- A build that stops with a network error (`npm error code ECONNRESET`, a Maven download
  timeout) is the connection, not the project: run `docker compose up -d --build --wait` again.
- Hot reload for the web app: `docker compose stop web`, then `cd web; npm install; npm run dev`
  (Vite on the same port 5780, proxying to the API on 8780).

## Run and verify

The dev machine is Windows (PowerShell 5.1 and Git Bash) with Docker Desktop. **There is no
local JDK or Maven**: Java is built and tested inside Docker.

```powershell
.\scripts\init-env.ps1                       # once: .env with random secrets (git-ignored)
docker compose up -d --build                 # postgres + keycloak + erp-api + web (http://localhost:5780)
.\scripts\demo.ps1                           # the end-to-end flow; files land in .\out\
.\scripts\smoke-web.ps1                      # signs in like a browser, checks CSRF and sign-out
```

| Check before you call a change done | Command (from the repo root) |
|---|---|
| Build, lint, all tests (~4 min; Testcontainers starts Postgres) | `docker run --rm -v "${PWD}\services\erp-api:/workspace" -v mini-erp-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /workspace eclipse-temurin:25-jdk sh mvnw -B verify` |
| Web | In `web/`: `npm test`, `npm run lint`, `npm run build` (includes `tsc`) |
| Running stack | `docker compose up -d --build erp-api web`, then `.\scripts\demo.ps1` and `.\scripts\smoke-web.ps1`. UI changes: also sign in at http://localhost:5780 |

Lint = Checkstyle (`config/checkstyle.xml`, line length 140) + `javac -Xlint:all -Werror`: a
warning (deprecated API, raw type, ...) fails the build.

Ports (all on 127.0.0.1, chosen to avoid the owner's other projects): web **5780** (also the
Vite dev server, and in Keycloak's redirect URI), API **8780** (management 8081 inside the
container only), Keycloak **8880**, Postgres **37432**. Before adding a port,
check `Get-NetTCPConnection -State Listen -LocalPort <port>`.

Demo users (realm `erp`, password = `DEMO_USER_PASSWORD` in `.env`; never print or repeat it):
`adam` ADMIN, `pam` PURCHASING, `max` MANAGER, `bea` PURCHASING+MANAGER, `walt` WAREHOUSE,
`sue` SALES, `finn` FINANCE.

## Invariants: do not break these

**Stock ledger**
- Stock changes only by inserting rows into `stock_movements` (through `StockLedger.post`). The
  table is append-only (trigger + the app login has only SELECT/INSERT).
- `stock_levels` and `bin_stock` are maintained by the trigger `apply_stock_movement` in the same
  statement; the app login can't write them. Never add a code path that updates balances directly.
- The trigger locks the (warehouse, product) balance row, refuses negative available stock
  (SQLSTATE `ER001`) and negative bin stock (`ER002`) with a JSON DETAIL; `StockLedger` maps those
  to 422 `insufficient_stock` / `insufficient_bin_stock`.
- `StockLedger` sorts movements by (warehouse, product, bin) before inserting: a fixed lock order,
  so concurrent multi-line documents can't deadlock. Keep it.
- Each movement type has a fixed shape (`stock_movements_shape`, mirrored in `StockMovement`)
  and exactly one typed source link whose composite FK includes product (and warehouse/bin), so a
  movement can't disagree with its document. A document line is posted at most once (partial
  unique indexes).
- `ReconciliationService` must stay clean after any operation. Tests call it after concurrent
  scenarios; if you add a movement type or document, extend the reconciliation.

**Quantities and money**
- Stock quantities are `long` in the product's base unit. Typed quantities (`NUMERIC(18,3)` +
  unit) are converted with `Quantities.toBase`, which refuses fractions of a base unit. Never
  round stock.
- Money is `long` minor units + ISO currency. Rounding: HALF_UP, once per line, in
  `Money.lineAmount` and `Money.taxOn`; totals are sums of rounded lines. No floats, ever.

**Documents and state machines**
- Transitions live only in `PurchaseOrderStatus`, `SalesOrderStatus`, `TransferStatus`,
  `AlertStatus` as exhaustive switch expressions (no `default`). Add states/actions there and let
  the compiler find every place to update; `*StatusTests` hold the full transition tables.
- Status codes: 404 not found, 403 role or relationship (`missing_role`, `separation_of_duties`),
  409 state forbids it (`invalid_transition`, `stale_version`, `pick_conflict`), 422 business rule
  (`insufficient_stock`, `over_receipt`, ...) with `errors[]` / `shortages[]`.
- Nobody approves their own purchase order: service check (403) + CHECK constraint.
- State changes lock the document row (`findForUpdate`, PESSIMISTIC_WRITE); draft edits use
  `@Version` + the client's `version` (409 `stale_version`).
- Goods receipts, shipments, histories, invoices and adjustments are immutable (`@Immutable`,
  append-only triggers, no UPDATE/DELETE grants).
- Column-level UPDATE grants: immutable columns are `@Column(updatable = false)` in the entities.
  A new updatable column needs both the grant (new migration) and the mapping.

**Invoicing**
- Numbers come from `InvoiceNumbering` (locked per-year counter row), never a sequence. Order in
  `InvoiceService.issueForShipment`: lockYear, re-check "already invoiced", next, insert. Triggers
  enforce: counter moves +1 only, an invoice uses the current counter value, and (deferred, at
  commit) every drawn number has its invoice.
- Fiscal year = issue date in `erp.business-time-zone` (tests run in Pacific/Auckland on purpose).
- Issuing is idempotent per shipment (event redelivery, manual retry).

**Events**
- Events go through Spring Modulith's registry (`event_publication`, table owned by Flyway V5,
  schema v2 of spring-modulith-events-jdbc 2.2). Listeners use `@ApplicationModuleListener`
  (async, after commit, own transaction) and must be idempotent: delivery is at least once.
- Listeners re-read current state instead of trusting event payloads (events can arrive late or
  out of order). `ReplenishmentService.evaluate` locks the rule row first; keep that.

**Security**
- Two filter chains (`SecurityConfig`): requests with `Authorization: Bearer` go to a stateless JWT
  resource server (issuer + audience `erp-api`, roles from the `roles` claim via `RolesClaim`);
  everything else is the browser chain (OAuth2 login, session, CSRF). Both: `/api/**`
  authenticated, docs/health public, everything else `denyAll`.
- **No tokens in the browser.** The SPA has no OIDC library; tokens live only in the server-side
  session (`ERP_SESSION`, HttpOnly, SameSite=Lax; stored in Postgres by Spring Session). Cookie
  names are app-specific because cookies on localhost are shared across ports.
- `/api/**` without a session is 401, never a redirect. Sign-in starts at
  `/oauth2/authorization/keycloak`; the redirect URI is built from `erp.public-url`, never from
  request headers. Post-login return paths go through `safeReturnTo` in `web/src/auth/session.ts`.
- Every state-changing browser request needs `X-XSRF-TOKEN` (from the `ERP-XSRF-TOKEN` cookie);
  `apiFetch` adds it, and sign-out is a POST with it. Don't add CSRF exemptions.
- Sign-out returns Keycloak's logout URL as JSON (fetch can't follow a cross-site redirect); the
  SPA navigates there, which ends the Keycloak session too.
- Role checks: `@PreAuthorize` on controllers; relationship rules in services. The UI renders the
  `allowedActions` / `canEdit` / `canReceive` / `canShip` the API returns; role-based menus are
  cosmetic. Don't re-implement permission rules in TypeScript.
- Render names, notes and other stored text as plain text (React escapes it); never use
  `dangerouslySetInnerHTML`. The CSP (`web/nginx.conf`) allows no inline scripts or styles and only
  `self` for connections; React `style={{}}` is fine, `<style>` and inline HTML are not.
- The actor (`Actor`) comes from the token or session only, never from a request body.
- Secrets only in `.env` (git-ignored, generated by `init-env.ps1`). Compose uses `${VAR:?...}`.

**Schema**
- Flyway migrations are immutable once applied: add `V7__...`, never edit V1..V6. Hibernate runs
  with `ddl-auto: validate`.
- New tables: composite foreign keys for "same warehouse / same order" rules, CHECK constraints for
  shapes, grants for `${appRole}` with the least privilege that works.

## Conventions

- Constructor injection; records for DTOs, events and config (`ErpProperties`); services
  `@Transactional(readOnly = true)` by default, `@Transactional` on writers; thin controllers.
- JPA for documents (aggregates: header owns lines); `JdbcClient` + SQL for the ledger, balances,
  replenishment and reports (set-based work, `RETURNING`, `ON CONFLICT`).
- Spring Boot 4 specifics: `spring-boot-starter-webmvc`, test annotations in new packages (e.g.
  `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`), Jackson 3
  (`tools.jackson.*`, `JsonNode.asString()`), Testcontainers 2
  (`org.testcontainers.postgresql.PostgreSQLContainer`).
- Tests: plain JUnit for pure logic; `IntegrationTest` (Testcontainers + MockMvc + signed test
  tokens from `TestTokens`) for everything touching the DB. Each test creates its own warehouse,
  products and partners (random codes). Concurrency tests call services from threads via `race`.
- PDFs: OpenPDF 3 (package `org.openpdf.text`, standard Helvetica, nothing embedded). Excel: POI;
  don't use `autoSizeColumn` (needs AWT fonts the JRE image may lack); use `Workbooks.fitColumns`.
- Browser-session tests: `TestUsers.User.session()` (an OIDC login whose name is
  `preferred_username`, like production) plus `csrf()` for POSTs.
- Web: all HTTP through `apiFetch` (`api/client.ts`) and TanStack Query hooks (`api/hooks.ts`);
  mutations refresh everything but `me` (one action ripples into stock, alerts, invoices); show
  server field errors with `ApiError.messageFor(field)` and stock shortages with `ErrorText`.
  Prices are parsed exactly into minor units (`parseMoney`), never via floats. Named exports,
  strict TypeScript, Tailwind classes, Vitest + Testing Library. Downloads are plain links: the
  browser sends the session cookie.

## Things that already bit us

- Hibernate flushes INSERTs before DELETEs: replacing an order's lines needs `flush()` after
  `clearLines()`, or the new line 1 collides with the old line 1 on `(order_id, line_no)`.
- `StockLedger` uses plain SQL inside the JPA transaction: it calls `entityManager.flush()` first
  so document rows written through Hibernate exist before the ledger's foreign keys check them.
- `INSERT ... ON CONFLICT` evaluates CHECK constraints on the proposed row before conflict
  detection, so the trigger inserts a zero row first and then updates it.
- `SELECT ... FOR UPDATE` needs UPDATE privilege on at least one column; balance rows are locked
  inside the SECURITY DEFINER trigger instead.
- JdbcClient `query(lambda)` can be ambiguous between `RowCallbackHandler` and
  `ResultSetExtractor`: cast to `(RowCallbackHandler)`.
- Spring Data won't find repository interfaces nested in another class: keep them top level.
- Windows PowerShell 5.1: `Invoke-RestMethod` passes a JSON array as one object (`lib.ps1` unrolls
  it); POST without a body sends a form content type (always send JSON). On an HTTP error, 5.1
  leaves `$_.ErrorDetails.Message` empty: `Invoke-ErpRaw` rewinds the WebException's response
  stream to read the problem body (PowerShell 7 fills ErrorDetails instead).
- The management port shares the security filter chain: `/actuator/health/**` must be
  `permitAll`, or the container healthcheck gets 401 and Docker reports the API unhealthy.
- Spring 7 JDBC exceptions don't repeat the driver's message at the top level: assert database
  errors with `hasStackTraceContaining(...)`, not `hasMessageContaining(...)`.
- Event listeners run asynchronously: tests that move the clock (invoice years) first wait until
  every earlier shipment is invoiced, or a straggler lands in the test's fiscal year.
- Healthchecks need a `start_period` long enough for the slowest first start: on a busy machine
  Keycloak took 5+ minutes (`start-dev` rebuilds its server on every container start), and with
  the old 30 s grace `docker compose up --wait` failed although nothing was wrong.
- Testcontainers waits 60 s by default for Postgres to say "ready". On an overloaded machine
  (several Docker builds at once) that wasn't enough and every integration test failed with
  "Timed out waiting for log output"; `TestcontainersConfiguration` now allows 3 minutes.
- Keycloak realm import runs only on the first start (persisted in Postgres). Change a running
  realm with kcadm (pipe scripts through Git Bash: PowerShell adds a BOM/CRLF) and update
  `erp-realm.json`, or `docker compose down -v` to start over. In Git Bash set
  `MSYS_NO_PATHCONV=1`, or it rewrites `/opt/keycloak/...` into a Windows path. kcadm can read a
  client's JSON from stdin (`create clients -r erp -f -`), so secrets never touch a file.
- Keycloak marks its cookies `Secure`. Browsers send those to `http://localhost` anyway (a secure
  context), .NET doesn't; and Windows PowerShell 5.1 silently drops a hand-written `Cookie`
  header. `smoke-web.ps1` therefore rebuilds a cookie jar of plain cookies for each Keycloak request.
- Spring Security test: `oidcLogin()` names the principal after `sub` unless you pass an
  `OidcUser` built with `preferred_username`; `csrf().asHeader()` sends a masked token that the SPA
  CSRF handler compares raw (403), so use `csrf()` (a parameter) in MockMvc tests.

## Definition of done

1. `mvnw -B verify` passes (Checkstyle, -Werror, all tests); web changes: `npm test`, `npm run lint`,
   `npm run build` pass.
2. Schema changes: a new Flyway migration with grants; reconciliation extended if stock is involved.
3. The stack is rebuilt and `.\scripts\demo.ps1` passes; auth or BFF changes: `.\scripts\smoke-web.ps1`
   passes too.
4. README and this file updated if behaviour, commands, ports or decisions changed.
5. Don't commit or push unless the owner asks.

## Learning guide

Read in this order. Each topic says what to learn and where it lives; the tests named are the
best way to see a rule enforced.

1. **The big picture: a modular monolith.** One Spring Boot app, split into modules that are
   plain packages, with rules a test enforces: a module only uses another's public types, and
   dependencies never form a cycle. Read `README.md`, `MiniErpApplication.java`,
   `shared/package-info.java`, `ModularityTests.java`.
2. **A normalised schema with composite foreign keys.** Every fact lives in one place; documents
   are header + lines; business rules like "a receipt line belongs to a line of the same order and
   goes into a bin of the same warehouse" are composite foreign keys onto "superkey" UNIQUE
   constraints; CHECK constraints guard shapes; column-level grants make some columns immutable.
   Read `db/migration/V1__master_data.sql`, then `V2__orders.sql`.
3. **Money and quantities without floats.** Money is a `long` of minor units plus a currency;
   quantities are whole numbers of a product's base unit, converted exactly from what people type
   (`BigDecimal`); one rounding rule (HALF_UP, once per line). Read `shared/Money.java`,
   `shared/Quantities.java`, `MoneyAndQuantitiesTests.java`, `web/src/lib/format.ts` (`parseMoney`).
4. **The stock ledger.** Stock is never "updated": every change is an immutable row in
   `stock_movements`, and the balances (`stock_levels`, `bin_stock`) are a projection a database
   trigger maintains in the same statement. The app's login can't write balances at all. Read
   `V3__stock_ledger.sql` (the trigger `apply_stock_movement`), `inventory/MovementType.java`,
   `StockMovement.java`, `StockLedger.java`, `StockLedgerDatabaseTests.java`.
5. **Reservations under concurrency.** Confirming an order inserts RESERVE movements; the trigger
   locks the balance row (`SELECT ... FOR UPDATE`), so two orders racing for the last unit queue up
   and the second is refused. Java sorts movements so rows are always locked in the same order (no
   deadlocks). Compare with optimistic locking (`@Version` on drafts) and pessimistic document
   locks (`findForUpdate`). Read `sales/SalesOrderService.java` (`reserveAll`), the `LOCK_ORDER` in
   `StockLedger.java`, `ReservationConcurrencyTests.java`, `PurchaseOrderService.update`.
6. **Explicit state machines.** Each document status is an enum whose transitions are exhaustive
   `switch` expressions: add a status and the code doesn't compile until every move is decided.
   "What is legal" (the enum) is separate from "who may" (roles, separation of duties). Read
   `purchasing/PurchaseOrderStatus.java`, `PurchaseOrderAction.java`, `PurchaseOrderService.checkMayAct`,
   `SalesOrderStatus.java`, `TransferStatus.java`, `AlertStatus.java`, the `*StatusTests`.
7. **Transactions and JPA aggregates.** A document owns its lines (`@OneToMany`, cascade,
   `orphanRemoval`); immutable documents are `@Immutable`; one service method = one transaction
   that writes the document, its lines, its history and its ledger movements together
   (`Propagation.MANDATORY` makes the ledger join it). Read `PurchaseOrder.java`,
   `PurchaseOrderLine.java`, `GoodsReceiptService.java`, `sales/ShippingService.java`.
8. **Domain events and the transactional outbox.** Sales publishes `ShipmentDispatched`; invoicing
   listens and issues the invoice after commit, in its own transaction. Spring Modulith stores
   each event with the transaction that raised it and retries failed listeners, so delivery is
   at-least-once and listeners must be idempotent. Read `sales/ShipmentDispatched.java`,
   `invoicing/InvoicingListener.java`, `inventory/StockLevelChanged.java`,
   `replenishment/StockLevelListener.java`, `ReplenishmentService.evaluate`,
   `shared/EventPublicationMaintenance.java`, `V5__replenishment_and_events.sql`, `LowStockAlertTests.java`.
9. **Gap-free invoice numbering.** A sequence leaves gaps on rollback; a locked counter row per
   year doesn't, and three triggers make "consecutive, no gaps, no duplicates" a database
   guarantee. The fiscal year comes from the company's time zone. Read `V4__invoicing.sql`,
   `invoicing/InvoiceNumbering.java`, `InvoiceService.issueForShipment`, `shared/BusinessCalendar.java`,
   `InvoiceNumberingTests.java`.
10. **Reconciliation and reconstruction.** Because the ledger is complete, stock can be rebuilt
    for any past moment, and every stored number can be audited against it. Read
    `reporting/ReconciliationService.java`, `StockReportService.java` (`asOf`), `ReconciliationTests.java`.
11. **PDF and Excel exports.** One data object, two renderers (OpenPDF tables, Apache POI cells
    with real numbers, a frozen and filterable header; a streaming workbook for large exports);
    tests parse the files back. Read `reporting/StockReport.java`, `Pdfs.java`, `StockReportPdf.java`,
    `InvoicePdf.java`, `Workbooks.java`, `StockReportExcel.java`, `MovementsExcel.java`, `ReportTests.java`.
12. **API design and errors.** Records as DTOs, Bean Validation, thin controllers, and RFC 9457
    problem details with deliberate status codes (404 / 403 / 409 / 422) and machine-readable
    `code`s. Read `shared/Errors.java`, `GlobalExceptionHandler.java`, `purchasing/PurchasingDtos.java`,
    `PurchaseOrderController.java`.
13. **Security: JWT resource server and roles.** Two filter chains: bearer tokens (scripts) are
    verified on every request (signature, issuer, audience); roles come from a token claim; method
    security with `@PreAuthorize`; the acting user comes from the token, never the request body.
    Read `shared/SecurityConfig.java`, `RolesClaim.java`, `Actor.java`, `ActorArgumentResolver.java`,
    `infra/keycloak/erp-realm.json`, `SecurityTests.java`.
14. **The backend-for-frontend (BFF).** The browser never holds a token: the API signs the user in
    with Keycloak (authorization code + PKCE, confidential client), keeps the tokens in a server-side
    session, and gives the browser an HttpOnly cookie; CSRF protects state changes; sign-out also
    ends the Keycloak session. Read `shared/KeycloakClientConfig.java`, the browser chain in
    `SecurityConfig.java`, `MeController.java`, `V6__browser_sessions.sql`, `web/src/auth/session.ts`,
    `web/src/api/client.ts`, `web/nginx.conf`, `BrowserSessionTests.java`, `scripts/smoke-web.ps1`.
15. **React with a server-driven UI.** TanStack Query caches server data and refreshes it after
    each change; screens render the actions the server allows instead of re-deriving permissions.
    Read `web/src/api/hooks.ts`, `web/src/pages/PurchaseOrderPage.tsx`, `SalesOrderPage.tsx`,
    `web/src/components/OrderLinesEditor.tsx`, `PurchaseOrderPage.test.tsx`.
16. **Testing strategy.** Real Postgres in Testcontainers, the app on its least-privileged login,
    real signed JWTs, a movable clock, and a `race` helper that releases threads together. Read
    `IntegrationTest.java`, `TestcontainersConfiguration.java`, `TestTokens.java`, `TestUsers.java`,
    `MutableClock.java`.
17. **Running it.** Multi-stage Dockerfiles (build with a JDK or Node, run on a JRE or nginx as a
    non-root user), healthchecks, one login per purpose in Postgres, secrets only in `.env`. Read
    `docker-compose.yml`, `services/erp-api/Dockerfile`, `web/Dockerfile`, `infra/postgres/init/01-init.sh`,
    `.github/workflows/ci.yml`.

**Glossary**

| Term | Meaning here |
|---|---|
| SKU | Stock-keeping unit: one product, with a unique code (`products.sku`) |
| Base unit / unit of measure (UoM) | The unit stock is counted in (EA, G, ML); other units convert to it (1 BOX = 12 EA) |
| Bin (location) | A place inside a warehouse ("A-01-03"); every warehouse has a RECEIVING bin |
| On hand / reserved / available | Physically present / promised to confirmed orders / on hand minus reserved |
| Stock ledger, movement | The append-only list of every stock change; one row = one movement |
| Projection | A table derived from another (balances from the ledger), never edited directly |
| Reservation | Stock set aside for a confirmed sales order line (a RESERVE movement) |
| Backorder | Ordered quantity that couldn't be reserved yet (not built yet) |
| Purchase order (PO) / goods receipt | What we buy / the booking of a delivery into bins |
| Sales order (SO) / shipment | What a customer buys / goods leaving the warehouse |
| Transfer, in transit | Stock moving between warehouses: left the source, not yet received |
| Reorder point / reorder quantity | Alert when available stock is at or below it / how much to buy |
| Minor units | The smallest currency unit as an integer (cents): 1999 = 19.99 EUR |
| Basis points (bp) | Hundredths of a percent: 2000 bp = 20 % |
| HALF_UP | Round .5 away from zero (598.5 cents -> 599) |
| Fiscal year | The year an invoice belongs to: its issue date in the company's time zone |
| Gap-free numbering | Consecutive document numbers with no holes, even when transactions fail |
| Aggregate | A cluster of objects changed as one unit (an order and its lines) |
| State machine | The legal statuses of a document and the moves between them |
| Optimistic locking | Detect a conflicting change at save time with a version number (`@Version`) |
| Pessimistic locking | Lock the row while working on it (`SELECT ... FOR UPDATE`) |
| Lock ordering | Always lock rows in the same order, so transactions can't deadlock |
| Trigger / SECURITY DEFINER | Database code run on INSERT/UPDATE / running with its owner's rights |
| Composite foreign key, superkey | A key over several columns / the UNIQUE constraint it points at |
| Append-only | Rows can be added, never changed or deleted |
| Reconciliation | Recomputing stored numbers from their source of truth and comparing |
| Domain event | A fact one module publishes ("goods shipped") for others to react to |
| Transactional outbox / event publication registry | Events stored in the same transaction as the change, delivered afterwards |
| At-least-once, idempotent | An event may arrive twice / handling it twice has the same effect as once |
| Modular monolith | One deployable with enforced internal module boundaries |
| Segregation of duties | One person can't do two conflicting steps (raise and approve a PO) |
| JWT, issuer, audience | A signed token / who issued it / which application it is for |
| OAuth2 authorization code + PKCE | Browser sign-in where the app swaps a one-time code for tokens, proving it started the flow |
| Confidential client | An OAuth2 client that has a secret (a server), unlike a browser app |
| BFF (backend-for-frontend) | A server that signs the browser in and holds its tokens, giving it only a session cookie |
| HttpOnly / SameSite cookie | Unreadable by JavaScript / not sent on most cross-site requests |
| CSRF | A forged request from another site riding on the user's cookie; stopped by a token in a header |
| CSP | Content-Security-Policy: which scripts, styles and connections a page may use |
| RFC 9457 problem details | The standard JSON shape for HTTP errors (`title`, `status`, `detail` + extras) |
| Testcontainers | Real dependencies (Postgres) started in Docker for tests |

**Exercises** (each touches several layers; run `mvnw verify` and the web checks after each)

1. **Prove a second race.** Write a test where two warehouse clerks receive the last open
   quantity of the same purchase order line at the same moment (use `race` from
   `IntegrationTest`). Expect one receipt and one 422 `over_receipt`. Then explain which lock makes
   it safe, and what would happen without `findForUpdate`.
2. **See the lock order matter.** Temporarily remove the `.sorted(LOCK_ORDER)` in `StockLedger.post`
   and run `ReservationConcurrencyTests` a few times. Watch for deadlocks (Postgres aborts one
   transaction; the API answers 409 `concurrent_update`). Restore it and write down why sorting fixes it.
3. **Adjustment reason codes.** Replace the free-text adjustment reason with an enum (COUNT,
   DAMAGE, LOSS, FOUND) plus an optional note: a V7 migration with a CHECK constraint, the entity,
   the DTO with validation, the stock page's select, and tests for an unknown code (400).
4. **Edit drafts in the UI.** Build item 1 of "What's left": the edit form, the `PUT` with
   `version`, and a reload offer on 409 `stale_version`, with a Vitest test.
5. **A new report.** Sales per customer per month as an Excel export: one SQL `GROUP BY` in a new
   reporting service, a POI renderer reusing `Workbooks`, an endpoint for FINANCE, a download link
   on the reports page, and a `ReportTests` test that parses the file back.
