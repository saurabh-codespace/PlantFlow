# Verification record

Verified locally on 13 October 2026. This records executed checks, not a production certification.

## Backend

`backend/mvnw.cmd -ntp verify` completed successfully for the entire Maven reactor: **40 tests, zero failures/errors**.

| Suite | Tests | Main coverage |
| --- | ---: | --- |
| Shared messaging | 7 | Broker ACK/NACK, unroutable returns, timeouts, transaction rollback, stable retry IDs and inbox deduplication |
| Transport | 10 | Asynchronous assignment replies, stale versions, transitions, ownership, concurrent last-seat reservations and HTTP permissions |
| Fleet | 7 | Vehicle/driver contention, duplicate delivery, terminal ordering, maintenance, transactional replies and permissions |
| Analytics | 8 | Duplicate/out-of-order projections, UTC reporting cohorts, KPI math, CSV safety and HTTP permissions |
| Tracking | 8 | Trip access, co-rider privacy, replay/freshness/rate validation, revocation, terminal cleanup and vehicle-bound device requests |

The isolated database tests use H2 PostgreSQL mode. Broker outcomes in the shared reliability tests are mocked. A subsequent real-stack check exercised PostgreSQL and RabbitMQ integration as described below.

## Running services

All seven Compose containers reached healthy state: four application services, PostgreSQL **17.11**, RabbitMQ **4.3.6** and Keycloak **26.7.5**. Application images build from Java 21 and run as a non-root user with a read-only root filesystem.

The local RabbitMQ upgrade retained its volume through 4.1 → 4.2 → 4.3, enabling feature flags between stages. The services reconnected. Persisted fleet outbox events were delivered after the publisher restarted, trip facts populated the analytics database, and all application queues and dead-letter queues were empty after delivery. This is a local recovery check, not a high-availability or disaster-recovery exercise.

The running-stack test found and fixed an analytics development-profile database override and missing fleet publisher scheduling. The full backend test run also exposed database timestamp precision affecting duplicate GPS comparisons; ingested GPS timestamps now use microsecond precision before persistence/comparison.

## Browser

Frontend validation also passed **12 unit tests**, all **five production builds** (shell and four remotes), and `npm audit` with **zero reported vulnerabilities** in the resolved npm dependency tree at the time of the check. The final fixture suite was rerun after the chart and capacity-input fixes.

Two Playwright fixture tests passed against the actual independently built federation remotes. They exercised screen loading, booking form payloads, mobile layout, role navigation, tracking empty state and reports. Authentication/API fixtures are confined to tests; there is no fake API fallback in the application.

One separate **live-stack Playwright test passed** with real Keycloak, API services, RabbitMQ and PostgreSQL:

1. Employee, dispatcher and driver signed in through authorization code + PKCE.
2. The employee was denied access to the dispatcher-only driver directory.
3. A ride was created, dispatch returned HTTP 202, and the fleet event confirmed the assignment.
4. The driver opted into location sharing; a position reached the real tracking API and authenticated SSE/map. Explicit stop removed it from the dispatch view.
5. The driver completed arrival, start and completion transitions.
6. The analytics CSV contained the completed trip.

A separate read-only live browser check then verified nonzero chart bars, compared the displayed active-trip KPI with the API, and captured the settled dashboard after the completion and fleet-release events had been projected. Recharts bar animations were disabled to keep polling updates immediately visible; Motion still supplies page transitions with reduced-motion support.

The GPS input in this browser test was a **controlled Playwright coordinate**, not a physical GPS device. No hardware vendor has been selected and no vendor adapter is claimed. Browser screenshots and the CSV are generated in the ignored `frontend/test-results/` directory; selected results are preserved under ignored `frontend/artifacts/` when subsequent test runs reset their output directory.

Run fixture checks with `npm run test:browser`; run the integration check with `npm run test:live` while the backend stack is healthy. The tests use frontend ports **5273–5277** so they do not stop the unrelated application already listening on 5173.

## Remaining validation

Physical phones/vehicle GPS, background mobile tracking, indoor accuracy, plant acceptance, peak shift load, penetration testing, production TLS/SSO policies, container/Java dependency vulnerability scanning and backup restoration have not been validated. The device-ingestion authorization path is tested; actual device credentials and a provider-specific adapter are future integration work. Production deployment preparation is described in [security.md](security.md).
