# PlantFlow

Internal plant transport for on-demand rides and shift shuttles. Java 21 / Spring Boot services, React microfrontends, Keycloak identity, PostgreSQL and RabbitMQ.

This is a working single-plant MVP. It covers booking through completion, asynchronous fleet assignment, shuttle seat reservations, operations reporting, and consent-based live positions. It does not claim full commercial ride-hailing parity or production certification.

## Run locally

Requirements: Docker Desktop with Linux containers, Node.js 20.19+ (or a supported newer release), and npm. Java 21 is needed only for running/tests outside Docker; Maven Wrapper is included.

```powershell
# From the repository root: generates local secrets and starts backend infrastructure/services
powershell -NoProfile -ExecutionPolicy Bypass -File .\infra\start-dev.ps1

cd frontend
npm ci
npm run dev
```

Open **http://localhost:5173**. If those ports are occupied, configure alternate ports in `frontend/.env` as described in the [frontend guide](frontend/README.md); keep an existing `.env` when following setup steps. The shell loads four independently built frontend remotes. Sign in via the local Keycloak login page. The local-only demo users `dispatcher`, `employee`, `driver`, `driver2`, `driver3`, and `admin` use **PlantFlow123!**. Backend demo seeds include plant stops, a fleet, a driver roster and pending ride requests. Reports show actual persisted activity; historical success metrics and GPS points are not fabricated.

To test a trip: sign in as employee and request a ride; sign in as dispatcher in another browser/profile and assign a vehicle/driver; the assignment becomes confirmed after the fleet service handles its event. Sign in as that driver to mark arrival, start, and complete. Reports and fleet availability follow asynchronously. Scheduled shuttles reserve their vehicle/driver until completion, and cannot depart before their scheduled time.

For phone testing, geolocation requires a secure context (localhost or HTTPS). A phone's `localhost` is the phone itself: provision an HTTPS development hostname with matching Keycloak redirects/issuer and frontend configuration before using another device. The provided stack binds its published ports to loopback.

## Modules and ownership

```text
PlantFlow/
  backend/       Spring Boot services and shared security/messaging library
  frontend/      React shell and four independently built microfrontends
  infra/         Local setup, identity realm and database initialization
  docs/          Architecture, API/events, security and verification notes
  compose.yml    Local infrastructure and backend services
```

| Module | Responsibility | Local port |
| --- | --- | --- |
| Keycloak | Accounts, authentication, roles, PKCE and device credentials | 8081 |
| Transport service | Plant stops, ride lifecycle, shuttle bookings and dispatch orchestration | 8082 |
| Fleet service | Vehicles, driver roster, maintenance and exclusive resource allocations | 8083 |
| Analytics service | Event projections, KPIs, reports, CSV and operational activity | 8084 |
| Tracking service | Trip-scoped phone/device positions and authenticated live streams | 8085 |
| React shell | Sign-in, navigation and shared design primitives | 5173 |
| Transport / fleet / analytics / tracking remotes | Independently built module interfaces | 5174–5177 |

Each backend owns its database. The shared Java library contains authentication configuration, messaging infrastructure, error handling and AOP instrumentation, with no shared business tables. Services communicate through durable events; they do not query each other's databases.

React uses Module Federation, Radix Dialog, Motion, Recharts, Leaflet and Lucide. Animations respect reduced motion. The plant schematic is explicitly a schematic; the live map uses actual authorized GPS fixes with age and accuracy. Public OpenStreetMap tiles are the development default; use a permitted private/offline tile source for a restricted plant network.

## Design

Commands write business state and an outbox event in one transaction. RabbitMQ publisher confirms protect delivery; consumers apply changes and inbox deduplication in one transaction. Aggregate versions prevent older events overwriting newer projections. Messages that fail bounded retries go to dead-letter queues. Delivery is at least once, with idempotent handlers.

Dispatch follows an asynchronous saga: transport accepts an assignment request (`ASSIGNING`); fleet atomically reserves a vehicle and driver and confirms or rejects; transport updates the trip; analytics and tracking consume the resulting event. Terminal trip events release the fleet allocation. During broker outages, requests remain persisted and pending; operators must monitor and recover dead-lettered work.

AOP measures controller operations and emits sanitized timing/outcome logs without arguments, tokens or coordinates. Business authorization and state transitions remain explicit and testable. See [architecture](docs/architecture.md), [API contract](docs/api-contract.md), [event contract](docs/event-contract.md) and [security](docs/security.md).

## Live location and GPS devices

Riders/drivers explicitly start browser location sharing for an eligible trip and can stop it. The tracking service verifies their identity and projected trip membership. Other riders' positions are private. Phone updates are throttled; invalid, stale and out-of-order fixes are rejected. The UI combines an authenticated SSE stream with reconciliation polling and stale-position indicators.

The vendor-neutral device endpoint accepts telemetry only from a DEVICE client whose signed token is bound to one `vehicle_id`. A hardware provider has not been selected, so its protocol adapter is intentionally pending. See [infrastructure guide](infra/README.md) for provisioning. Browser location is not a guarantee of indoor accuracy or continuous background tracking.

## Verify

```powershell
cd backend
.\mvnw.cmd -ntp verify
cd ../frontend
npm test
npm run build
npm run test:browser
```

Backend tests exercise concurrency, seat capacity, state transitions, permissions, event replay and reporting. Browser fixtures test the federation UI independently; they are not substitutes for a running-stack integration test. See [verification notes](docs/verification.md) for what was actually run.

## Deployment scope

The Compose stack is explicitly development-only: local HTTP, imported demo identities and debug logging. Production requires deployment-specific TLS, identity/account policy, secrets, network/broker permissions, trusted remote origins, backups, retention and capacity checks. See [security preparation](docs/security.md).

Follow-on modules include recurring shift timetables, approval policies/restricted zones, workforce/HR integrations, SMS/push notifications, telematics adapters, verified boarding/occupancy, maintenance work orders, chargeback/cost reports and multi-plant tenancy. Current analytics do not invent mileage, fuel, emissions, SLA or cost KPIs without the source measurements needed to calculate them.
