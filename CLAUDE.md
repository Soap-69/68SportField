# 68 Sport Field — Project Handoff / Context for Claude Code

Purpose of this file: give a fresh Claude Code session the full picture of what has been built, which decisions are FROZEN, which lessons were learned the hard way, what is in flight right now, and what the working rules are. Read this before touching code.

---

## 1. Project

- E-commerce upgrade of an existing product showcase site for 68 Sport Field (sports card B2B business). Solo resume project.
- Stack: Spring Boot 3 + Thymeleaf (Layout Dialect) + PostgreSQL + Flyway; Bootstrap 5; Spring Security with TWO filter chains (admin + customer).
- Repo: `Soap-69/68SportField`, branch `feature/ecommerce-v1`. Local dir `C:\Users\18572\cardshop` (Git Bash on Windows). DB runs in Docker container `card-db` (database `card_showcase`).
- Interactive Docker on MINGW64 needs `winpty`: `winpty docker exec -it card-db psql -U postgres -d card_showcase`
- Production: AWS Lightsail + Cloudflare at https://68sportfield.com (not part of this work stream).
- Current workflow: Codex is the reviewer and maintains this handoff; Claude Code performs the approved implementation; Yi makes business/deployment decisions and authorizes commits, pushes and deployment. Follow the current task authorization rather than assuming any tool may publish changes.

---

## 2. Working rules for Claude Code (READ FIRST)

1. **Do exactly what the prompt scopes. Nothing else.** Every prompt has a "Do NOT" list; obey it. Do not refactor "while you're here". Do not touch frozen architecture (section 4).
2. **Never overwrite files from a stale worktree.** Two incidents: a Week 7.1 run rebuilt `SecurityConfig.java` from the Week 6 commit and destroyed the dual-chain security setup (admin 405 on POST `/admin/login`, customer auth gone), and also overwrote `admin/layout.html` (lost the Orders and Inventory nav links). Always edit the CURRENT working tree; never regenerate a whole config/layout file from an older commit.
3. **Do not commit or push unless the current prompt explicitly says so.** Approval to review, implement or commit is not approval to push or deploy. Week 9 begins with Phase A only; Phase B requires explicit approval.
4. **Report exact numbers**: Tests run / Passed / Failed / Errors / Skipped. Never write "verified" for something you did not actually run. If a concurrency scenario cannot be reliably proven in the test environment, say so; never present a sequential test as a concurrency test.
5. **Completion reports are not trusted by themselves.** Reviewers diff the actual code. Past incident: commit messages claimed fixes that were not in the pushed diff. Report honestly what changed, file by file.
6. **Flyway**: never edit an applied migration; confirm the next free version before adding one (latest confirmed: V31 `create_notification_log`; next is V32 unless something changed — check). Every entity/column change needs its own migration.
7. **Business-rule values must be approved.** Do not invent prices, thresholds, or rules (an earlier invented "$100 free shipping / $9.99 otherwise" had to be ripped out). If a rule is undefined, return an explicit "deferred / requires manual quote" state.
8. Prompts to you are in English. Yi talks to the reviewing Claude in Chinese.
9. **User-mandated development and review constraints:** no speculative design, no overengineering, no defenses for unobserved errors, no backward compatibility, and no glue code that hides implementations or obsolete paths that should be trimmed. Use concrete current requirements and evidence when reviewing guards and error handling.

---

## 3. Testing / environment lessons (important)

- **Test profile uses H2 with `ddl-auto: create-drop` and Flyway DISABLED.** Therefore tests can NOT catch a missing Flyway migration or a schema/entity mismatch. After any entity/migration change, a FULL restart of the Spring Boot app against the real PostgreSQL container is mandatory (hot reload does not run Flyway). A missing `created_at` column on `admin_users` broke startup once for exactly this reason. Idea parked for ~Week 11: Testcontainers PostgreSQL with `ddl-auto: validate` + Flyway enabled.
- H2 can serialize "concurrent" transactions, so concurrency tests there only prove guard logic, not real PostgreSQL race behavior.
- `.with(csrf())` in MockMvc bypasses real CSRF token delivery. A green test does not prove the browser path works. (An admin POST 403 slipped through because `admin/layout.html` lacked the `_csrf` meta tags.)
- CSRF mechanics: session-based `HttpSessionCsrfTokenRepository`; a custom `CsrfTokenRequestAttributeHandler` in `SecurityConfig` forces eager token materialization because the large public layout fills Tomcat's 8 KB buffer before the `th:action` hidden field is reached ("Cannot create a session after the response has been committed"). Never exempt `/api/cart/**`. JS reads the token from `<meta name="_csrf">`/`_csrf_header` (admin layout now has these) and sends header `X-CSRF-TOKEN`. Tokens go stale: refresh the page before manual `fetch()` tests.
- Spring Security does not read CSRF tokens from JSON bodies; use the header.
- Thymeleaf/SpEL pitfalls hit so far: Java lambdas are not valid in SpEL (compute booleans in the controller); URL parameter literals must be quoted (`@{/login(redirect='/checkout')}`); a render exception mid-template leaves a half-rendered page (header visible, body blank) because the fragment buffer is discarded.
- Admin and customer sessions share one `JSESSIONID` on `localhost:8080`; logging into one kicks out the other. Use a separate browser/incognito window.
- Dev-only features need Spring profile `dev` (IntelliJ Run Configuration → Active profiles). `ManualPaymentGateway` and its admin controller are `@Profile({"dev","test"})`.
- Remove temporary `logging.level.org.springframework.security: DEBUG` from `application.yml` before committing (application.yml is YAML, not `.properties`).
- Git hygiene: `.claude/worktrees/` must be in `.gitignore`. Diagnostic trio: `git status` → `git log --oneline -5` → `git diff --cached --stat`. Make WIP commits during long weeks — Week 7 ran ~2 weeks of work with no commit and no rollback point during the SecurityConfig incident.
- Test-suite growth (for sanity checks): W4 71→79, W5 97→108, W6 133→178, W7 203→212, W7.1 225, Review #2 239, Review #3 243 (all green at each step).

---

## 4. Frozen architecture (do not redesign)

### Order / state machine (Week 4)
- States: `PENDING_PAYMENT, PAID, PROCESSING, SHIPPED, DELIVERED, COMPLETED, CANCELLED, REFUNDED`.
- Legal transitions: PENDING_PAYMENT→PAID|CANCELLED; PAID→PROCESSING|REFUNDED; PROCESSING→SHIPPED|REFUNDED; SHIPPED→DELIVERED|REFUNDED; DELIVERED→COMPLETED|REFUNDED; COMPLETED→REFUNDED; CANCELLED/REFUNDED terminal. **PAID cannot go to CANCELLED** — post-payment reversal is a refund.
- `OrderStatus.isPhysicallyDispatched()` = SHIPPED|DELIVERED|COMPLETED (explicit set, no `ordinal()` comparisons anywhere).
- **Order.status is the single source of truth for "has it shipped".** `Shipment.shippedAt` is only an audit timestamp, set atomically with PROCESSING→SHIPPED. Never branch business logic on `shippedAt`.

### Checkout (Week 4, hardened)
- Guest checkout supported (`customer_id` nullable); logged-in may use saved address; Order always stores immutable address/price/variant snapshots (no live FK to Address).
- Cart is resolved server-side from the request's guest cart token cookie / authenticated customer. No `cartId` from the client, ever.
- Checkout does a read-only stock check; it does NOT reserve or deduct inventory. Inventory is deducted only at payment success (Week 5).
- Idempotency: unique `idempotency_key` + `request_fingerprint` (covers cart variants+qty, identity, full inline address fields incl. firstName/lastName/line2/phone via shared `inlineAddressFp()` helper, saved-address ids, billingSameAsShipping, serviceLevel). Same key + different content → 409. Replay must match checkout identity (customer_id or SHA-256 `guest_cart_token_hash`; raw guest token is never stored). Only the `idempotency_key` UNIQUE violation is treated as a race; other `DataIntegrityViolationException`s surface as errors.
- Checkout validates every variant AND parent product is active; conditional input validation (guest name/email/address; billing when not same-as-shipping) → clean 400.
- Order number format `ORD-YYYYMMDD-XXXXXXXX` (8 base-36 chars).
- Destination validation (`ShippingService.validateDestination`): US only, valid state codes (51 incl. DC); shared by checkout (authoritative) and the preview endpoint.

### Payment domain (Week 5) — provider-independent
- `Order 1 → 1 logical Payment`; `Payment 1 → N PaymentEvent`. DB: `UNIQUE(order_id)`, `UNIQUE(idempotency_key)`, `PaymentEvent.event_id UNIQUE`. No `PaymentAttempt` entity. PaymentEvent metadata must never hold PAN/CVV/secrets.
- `PaymentStatus`: PENDING, SUCCEEDED, FAILED, REFUNDED, PARTIALLY_REFUNDED (the last is reserved, never exercised). Lifecycle: PENDING→SUCCEEDED|FAILED; FAILED→PENDING via an explicit `applyRetryTransition` (writes `PAYMENT_RETRY_STARTED`; requires Order=PENDING_PAYMENT) → confirm again.
- `PaymentGateway` is a coarse, pure I/O interface (`processPayment`, `refund`); gateways NEVER mutate Order or Inventory. `PaymentService` owns all business side effects.
- `PaymentService.confirmSuccessfulPayment`: business failures (insufficient stock, DECLINED/ERROR) COMMIT a FAILED state (no exception-driven rollback). An optimistic-lock conflict is NOT a business failure: whole transaction rolls back, Payment stays PENDING, caller gets a clean retryable `PaymentConcurrencyException` (409); `em.flush()` runs inside the try/catch so the OLE is caught there. Order must be PENDING_PAYMENT before any payment work; `getOrInitializePayment` only creates a first Payment for PENDING_PAYMENT orders; SUCCEEDED confirm is an idempotent no-op. Cross-order idempotency-key reuse → 409.
- Deterministic multi-location deduction (location id ASC, skip rows with 0 stock, assert `remaining == 0`), all variants/locations in one transaction. Each deduction persists an `InventoryAllocation(order_item, inventory, quantity_committed > 0)` row in the same transaction (V30; `UNIQUE(order_item_id, inventory_id)`).
- `ManualPaymentGateway` (dev/test only) is NOT the future wire-transfer flow. Trigger: `POST /admin/api/orders/{orderId}/payments/manual-confirm` body `{outcome: SUCCESS|DECLINED|ERROR, idempotencyKey}`; it internally does get-or-initialize → retry-transition-if-FAILED → confirm. Admin auth + CSRF apply.
- Real Authorize.net/PayPal is deferred to ~Week 10; real provider sequencing (authorize/capture/webhooks) is NOT inferred from the manual flow.

### Shipping (Week 6)
- `ShippingService` is a rules engine (pure calculation; does not mutate Order/Payment). Continental US: Ground & subtotal ≥ $500 → $0; Next Day Air → +$150; Ground & < $500 → `REQUIRES_MANUAL_QUOTE` (no invented price). AK/HI → `AK_HI_DEFERRED`. No carrier API yet.
- `Shipment` (Order 1→1) holds carrier, service level, quoted amount, tracking, timestamps and `ShippingPaymentStatus`: `NOT_REQUIRED, QUOTE_REQUIRED, QUOTED, PAYMENT_PENDING, PAID, WAIVED`. Transitions QUOTE_REQUIRED→QUOTED→PAYMENT_PENDING→PAID (or →WAIVED). `recordQuote` stops at QUOTED; `requestShippingPayment` moves to PAYMENT_PENDING; `confirmShippingPaymentReceived` →PAID. None of these create Payment/PaymentEvent rows or call a gateway.
- **READY_TO_SHIP is derived, never persisted.** `ShippingPaymentStatus.isFulfillmentReady()` (NOT_REQUIRED|PAID|WAIVED) is the single shared rule used by `OrderService.isReadyForFulfillment` and by tracking/processing gates.
- **Principle: one-payment-by-default, supplemental-payment-by-exception.** Normal orders pay once. Only AK/HI (and continental Ground < $500 until a pricing rule/UPS API exists) use the supplemental shipping obligation; the two-step flow must never become the default.
- `recordTracking` stores carrier+tracking only (requires Order=PAID and shipping fulfillment-ready); it does NOT set `shippedAt` or change Order status.
- `Order.getEffectiveShippingAmount(shipment)` / `getEffectiveTotal(shipment)` are the shared helpers for admin detail, customer detail and CSV (supplemental shipping shown once PAID).
- Public `GET /api/shipping/quote?state=&subtotal=&serviceLevel=` is a non-authoritative preview that delegates to `ShippingService`; checkout recomputes from server-side data. Rule values live in exactly one place (`ShippingService`); templates contain no rule logic.

### Admin order management / refunds / dispatch (Week 7)
- `dispatchShipment`: requires Order=PROCESSING + Shipment with carrier & tracking; in ONE transaction Order PROCESSING→SHIPPED and `Shipment.shippedAt = now`.
- Routine transitions by ADMIN or SENIOR_ADMIN (no approval): PAID→PROCESSING (gated by fulfillment readiness), SHIPPED→DELIVERED, DELIVERED→COMPLETED. Practical operator order: record tracking while PAID, then Mark Processing, then Dispatch.
- Refunds: real two-step approval via `RefundRequest` (`PENDING_APPROVAL, APPROVED, REJECTED, EXECUTED, FAILED`). FULL REFUNDS ONLY (amount must equal order total, server-authoritative). ADMIN or SENIOR_ADMIN can submit; only SENIOR_ADMIN can approve/reject; self-approval allowed in V1. Not allowed for PENDING_PAYMENT/CANCELLED/already-REFUNDED orders.
- Approval executes immediately in a REQUIRES_NEW transaction: Payment→REFUNDED + one REFUND PaymentEvent, Order→REFUNDED, RefundRequest→EXECUTED. Failure rolls back side effects and commits RefundRequest→FAILED separately. Losing a race raises `RefundAlreadyProcessedException` (409, not marked FAILED).
- Inventory on refund: if pre-refund status is PAID/PROCESSING → restore EXACTLY per persisted `InventoryAllocation` rows; if SHIPPED/DELIVERED/COMPLETED → no automatic restock (physical returns workflow is deferred). Missing/incomplete provenance must FAIL CLOSED (sum of allocations per item must equal item quantity; otherwise abort the refund).
- Exactly-once is scoped to the Order/Payment (pessimistic lock on the Payment row at submit and at approve, re-check `payment.status == REFUNDED` after locking), not just to one RefundRequest id. Only one active PENDING_APPROVAL request per order.
- Admin UI: `/admin/orders` (search/filter/pagination, CSV export button), `/admin/orders/{id}` (items, addresses, payment, shipment, refund requests, action buttons; main-payment vs supplemental-shipping breakdown with a note that refunds cover the main payment only). Action buttons use `fetch()` with the `_csrf` meta tag.
- CSV export (`/admin/api/orders/export`) is an ALLOWLIST: order_number, customer_or_guest_name, email, status, subtotal, shipping_amount, tax_amount, total, created_at, shipping_state, carrier, tracking_number. Never export idempotency keys, token hashes, fingerprints, provider ids, passwords, etc.

### Admin user management (Week 7.1)
- SENIOR_ADMIN-only `/admin/users` and `/admin/api/users/**`: list, create (BCrypt via the existing `PasswordEncoder`; min 8 chars), change role, enable/disable.
- Append-only `AdminUserAudit` (CREATED, ROLE_CHANGED, ENABLED, DISABLED) written in the same transaction as the mutation.
- Guards: cannot disable or demote yourself; invariant "≥ 1 enabled SENIOR_ADMIN" enforced with `SELECT … FOR UPDATE` (ordered by id) over enabled senior admins before counting.
- `admin_users.created_at` added in V29 (nullable; old rows stay NULL on purpose).
- Out of scope and not to be built: invitations, password reset, MFA, custom roles/RBAC, SSO, session management.

### Security
- Dual filter chains: `@Order(1)` admin chain scoped to `/admin/**` (`loginProcessingUrl("/admin/login")`), `@Order(2)` customer chain (`CustomerUserDetailsService`, `CustomLoginSuccessHandler`, CSRF timing fix). Do not merge or "simplify" them. SENIOR_ADMIN-only matchers sit before the generic `/admin/**` rule.
- Backend authorization is authoritative; hiding buttons in the UI is not security.

---

## 5. Current state (2026-11-03; Week 8 closed)

- Branch: `feature/ecommerce-v1`; local HEAD `f9a53f8`. Yi reports that `origin/feature/ecommerce-v1` was checked after push and matches local HEAD, with no ahead/behind. This remote result is user-confirmed, not a new remote fetch performed for this documentation update.
- Weeks 0–8 are complete. Week 7 follow-up fixes were committed in `dc5eee7`; they are no longer pending working-tree changes. Their order/payment/refund invariants remain documented in section 4.
- `6f6828e` — Week 8 transactional order notifications, PDF invoices, tax skeleton, and review fixes. V31 adds `notification_log` with `UNIQUE(order_id, type)`. Enqueue is in the business transaction; sending is after commit. Automatic sends and manual resends share the atomic claim path; disabled skips do not increment attempts. SENT/SENDING rows are not resent. The guarantee is one delivery attempt per successful claim, not exactly-once end-to-end SMTP delivery. No scheduler or external queue was added.
- Six email templates: order received (explicitly unpaid), payment confirmed with invoice, shipped, supplemental shipping payment requested, refund executed, and admin new paid order. Existing business state machines and transaction invariants remain authoritative.
- PDF: OpenPDF 2.0.5, generated on demand without disk persistence; uses the order's main-payment snapshot amounts, not supplemental-shipping effective totals. Refunds retain original invoice amounts and add a refunded marker/date. Customer authorization precedes invoice availability checks. Bundled Noto Sans and Noto Sans CJK SC fonts include source/license files; reviewer accepted the roughly 16 MB CJK font in Git history. PDFs embed font subsets.
- `TaxService.calculateTax(subtotal)` currently returns `0.00`; this is a deferred tax implementation, not a production tax determination.
- `f9a53f8` — admin narrow-screen table fix. Data tables scroll in individual Bootstrap `table-responsive` regions; the totals table is no longer forced to 560 px. Existing UI style is preserved. Browser validation covered 304, 390, 768 and 1280 px, right-side action accessibility, notification resend and product edit modal behavior.
- **Reviewer final full-suite run on 2026-11-03 at `f9a53f8`: 276 tests run / 276 passed / 0 failures / 0 errors / 0 skipped; Maven BUILD SUCCESS (H2). Both Week 8 commits passed review with no outstanding P-level findings in the reviewed scope.** The 243-test Week 7 baseline and initial 270-test Week 8 report are historical, not the current baseline.
- Separate isolated PostgreSQL/Mailpit verification covered migrations V1–V31, notification claim/resend concurrency and real HTTP/browser flows. This was targeted verification, not a full PostgreSQL test-suite run. Temporary verification containers/data were removed; `card-db` was left stopped and untouched. PDF visual checks used system-rendered images, not an embedded browser PDF viewer. Some earlier implementer Mailpit records were lost on receiver deletion; do not describe all historical raw mail evidence as retained.
- `.claude/settings.local.json` has unrelated local modifications and was excluded from both Week 8 commits. Do not overwrite, stage or commit it with future work.

### Outstanding facts and production deployment gate

- Merchant display name, invoice legal company name and address are still unconfirmed. Empty configuration omits the corresponding signature/footer; do not invent business identity.
- Reply-To and intended admin recipient are `order@68sportfield.com`; the approved site URL is `https://68sportfield.com`. The intended mailbox does not prove that production `ADMIN_EMAIL` or SMTP credentials are configured.
- Confirm production JVM default timezone (`TZ`, `-Duser.timezone`, image defaults), historical changes, and the write timezone of existing order/payment/shipment rows. `BusinessTimeFormatter` interprets stored `LocalDateTime` values using the current JVM default zone and displays America/New_York with ET; this is only correct when the relevant historical write zone matches the current process zone.
- Confirm actual production SMTP sender, authentication method, `MAIL_USERNAME`, `MAIL_PASSWORD`, `ADMIN_EMAIL` and `APP_NOTIFICATION_ENABLED`, and verify delivery when explicitly authorized. Record confirmation/results, never secrets.
- **Review/push approval is not deployment approval. Do not deploy this build until the production timezone/history prerequisite is confirmed. The gate covers both email and invoice downloads; setting `APP_NOTIFICATION_ENABLED=false` alone does not satisfy it.** Carry all missing business/deployment facts into final acceptance and launch review; do not silently mark them done.

---

## 6. Known gaps / backlog (none scheduled unless noted)


- Checkout has no Ground vs Next Day Air selector (all orders default to GROUND; backend fully supports both).
- State field is free text (idea: dropdown of valid US states; address autocomplete would derive city/state from zip, not the reverse).
- Admin refund "reason" is auto-filled and not editable (intentional for full-refund-only; consider an optional reason input later).
- Homepage mobile navbar at ~340–430 px still hides login/account icons (pre-existing since before Week 4; unresolved). The separate admin 304 px table-clipping issue was fixed in `f9a53f8`; do not conflate them.
- Intermittent single 403 on the customer login form was seen twice in Weeks 2–3; targeted CSRF-failure logging is in place; not reproduced since.
- AK/HI and Ground < $500 two-step shipping payment should collapse to one checkout payment once a UPS rate API (Week 10) and/or an approved <$500 pricing rule exists.
- Supplemental-shipping refunds, partial refunds, returns/warehouse restock policy, real provider refunds: all deferred (Week 10+).
- Testcontainers/PostgreSQL integration profile for migration validation: proposed for the E2E week.
- No admin UI for creating the first SENIOR_ADMIN other than SQL (user management exists now; bootstrap of the very first account is still manual).

---

## 7. Roadmap (revised 12-week plan; Weeks 0–8 done)

- **Week 8 — complete**: transactional emails, PDF invoices, tax skeleton, review fixes and admin narrow-screen table repair. Production deployment remains gated as described in section 5.
- **Week 9**: security audit / brute-force protection (customer order history was already delivered early in Week 4).
- **Week 10**: real payment providers behind the existing `PaymentGateway` (Authorize.net via Accept.js; PayPal if credentials are delayed), webhook handling with signature verification and `event_id` dedupe, supplemental-shipping payment modeling decision (Order 1→N Payments with a purpose, or ShippingCharge + provider reference), real UPS rate API.
- **Week 11**: E2E tests (payment matrix, shipping matrix, edge cases); consider Testcontainers.
- **Week 12**: production launch (credentials, small real-money test, docs/training).

---

## 8. Immediate next steps

1. Begin **Week 9 Phase A: security audit and brute-force protection proposal only**. Read the current worktree and the reviewer's Phase A prompt. Produce evidence-backed findings, the smallest implementation proposal, unresolved decisions and a test matrix; do not begin Phase B until explicitly approved.
2. Recheck actual Flyway files before any schema work. Latest confirmed migration is V31; V32 is the next available number, not an instruction to create a migration. Add one only if the approved implementation changes the database.
3. Preserve both Spring Security chains, current authorization/CSRF behavior, checkout/payment/refund invariants and customer cart-merge behavior. Apply the five rules in section 2.9; do not add speculative platforms, compatibility paths or broad refactors.
4. Keep verification isolated from `card-db`, `card_showcase` and production. Use synthetic accounts/data and a local mail receiver where needed. Report exact test counts and distinguish H2 tests, PostgreSQL evidence and real browser checks.
5. Keep the homepage mobile navigation issue in backlog unless separately scoped. Maintain section 5's business/deployment gates through final acceptance. Do not commit, push, deploy or send external mail without applicable user authorization; exclude `.claude/settings.local.json` from task commits.
