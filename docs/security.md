# Security model and deployment preparation

PlantFlow separates identity, business authorization, operational databases, reporting and location visibility. These controls are an implementation baseline, not a certification or a completed penetration test.

## Identity and browser session

Keycloak is the identity provider. The React shell uses the OAuth2 authorization code flow with PKCE for a public browser client. Access/refresh tokens remain in memory rather than browser local storage. Each Spring Boot resource server validates signed JWTs, issuer, expiry and the `plantflow-api` audience. Backend identities come from the verified token; request bodies cannot select the acting username or role.

Configure exact redirect URIs, web origins, issuer and API audience for the deployment. Keep Keycloak administrative credentials and database secrets outside source control. Production identity should use the organization's account lifecycle and MFA policy. Development demo accounts and local HTTP are only for local development; do not expose them to the plant network or internet.

APIs are stateless bearer-token endpoints rather than cookie sessions. CSRF protection is consequently not the authentication mechanism. This assumption must be revisited if browser cookies become a source of API authentication. Keep TLS on all external origins and configure a strict Content Security Policy at the serving proxy with only the required API, identity, map and remote-module origins. Module-federation remotes run as trusted code with access to shell context, so control their build/deployment supply chain and origins.

## Business permissions

| Role | Intended access |
| --- | --- |
| EMPLOYEE | Own on-demand bookings, shuttle discovery/reservations, authorized ride positions |
| DRIVER | Their assigned trips and authorized driver location publication |
| DISPATCHER | Plant-wide dispatch, fleet catalog/maintenance, operational positions and reports |
| ADMIN | Dispatcher capabilities; Keycloak administration requires a separate identity-administrator account |

Role and resource ownership checks belong to backend services. UI navigation is convenience, not an access control. Trip transitions, vehicle capacity, reservations and allocation rules are verified server-side in transactions. Reports, export and activity APIs require dispatcher/admin privileges. Driver catalog registration is distinct from identity provisioning: the operator must create a matching Keycloak username and grant the DRIVER role. A catalog entry alone must not grant a user a role.

Transport/analytics/tracking databases must never be reachable by unrelated application database credentials. Each service uses its own database user and migrations. Service-to-service work travels through the broker; access to RabbitMQ is therefore a trusted producer boundary. Use isolated network access, deployment secrets and broker permissions that restrict each service to required exchanges/queues. Do not expose broker management to ordinary users.

## Location and employee information

Location coordinates are sensitive employee information. Collect them only when a user explicitly shares location for an authorized transport operation. Limit rider visibility to their booking/participating shuttle, driver publication to the assigned trip, and broad fleet visibility to dispatch/admin. Never place bearer tokens in SSE URLs or logs. Browser geolocation permission and token authentication are both required; one does not replace the other.

Trip access is projected asynchronously into tracking, so a new grant can be briefly unavailable while its event arrives. Terminal/revocation events also depend on delivery: monitor queue lag and retain only the short operational location window needed for the plant. Agree retention, employee notice, appropriate access and incident handling with the organization before enabling real employee tracking. Configure operational retention jobs before production, including backups; deleting only the live row does not delete backup copies.

Analytics stores the minimum fields needed for aggregate KPIs: trip IDs, kind, route, status, passenger counts and timestamps. Recent activity includes an actor username and is restricted to dispatchers/admins. Reports use completed-trip passengers for movement counts. CSV cells are quoted and formula-triggering prefixes neutralized so user-controlled route names cannot execute spreadsheet formulas when an operator opens an export.

## Input, logs and failure handling

API DTO validation, state checks and parameterized SQL protect server-side trust boundaries. Event consumers validate envelope schema/type and aggregate identity and reject invalid payloads. Inbox deduplication and monotonic projection versions prevent redelivery from duplicating effects or regressing report state. Consumer transactions must commit business data and inbox data together; failures roll back both.

Timing/audit AOP logs method identity, duration and sanitized metadata. Do not log method arguments, access/refresh tokens, authorization headers, credentials or precise positions. Development debug logging is enabled for application packages; broad Spring Security or JDBC request/parameter logging should remain off when real employee data is present. Errors returned to clients should not include stack traces or database details. Public health endpoints must not disclose internal configuration.

## Deployment checks

- Replace development credentials and restrict CORS/redirects to exact deployed origins. Enable HTTPS and secure reverse-proxy headers.
- Use non-public databases/broker, separate service credentials, secret injection and credential rotation. Enable network/TLS protection for identity, database and broker traffic appropriate to the hosting environment.
- Run dependency/container vulnerability scanning against the resolved lockfiles/images, review updates, and pin deployed artifacts. This document does not claim a clean scanner result.
- Verify authorization using employee, driver, dispatcher and administrator identities, including another employee's trip, another driver's assignment, a cancelled trip, expired tokens and wrong issuer/audience.
- Exercise duplicate/out-of-order events, broker failure, consumer poison messages and database restoration. Document who inspects/replays dead-letter messages and how projection rebuilds are performed.
- Apply reverse-proxy request limits and rate limits, and size SSE/device connection limits. Load-test the expected shift change peak before choosing capacity or availability targets.
- Define location/report retention and backup access, and centralize sanitized operational audit records with access control and retention. The current activity table is not an immutable audit log.

Single-plant deployment is the current isolation model. Multi-plant support requires a verified plant/tenant scope on every token, query, event, projection and live-position subscription; a frontend plant selector alone is insufficient.
