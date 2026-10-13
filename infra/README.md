# Local development infrastructure

Image versions were checked against official release information on October 13, 2026: [Keycloak 26.7.5](https://www.keycloak.org/2026/09/keycloak-2675-released), [PostgreSQL 17.11](https://www.postgresql.org/docs/17/release-17-11.html), and [RabbitMQ 4.3.6](https://www.rabbitmq.com/release-information). These include fixes released after the earlier development images. RabbitMQ 4.2 community support ended July 31, 2026; it is not the final deployment target.

Run `powershell -NoProfile -ExecutionPolicy Bypass -File infra/start-dev.ps1` from the repository. The execution-policy flag applies only to this PowerShell process and does not change the machine policy. The script generates independent 256-bit random infrastructure credentials in the ignored `.env` file, then builds and starts the backend with Docker Compose. Existing `.env` values are preserved. `-InitializeOnly` generates credentials without starting containers. Docker Desktop must be running. Start the React frontend separately as described in the root README.

All published ports bind to `127.0.0.1`. PostgreSQL and RabbitMQ AMQP are only reachable on the Docker network. RabbitMQ's management UI is at `http://localhost:15672` with username `plantflow` and its generated `.env` password. Keycloak is at `http://localhost:8081`; the administrative account is `bootstrap-admin` with the generated `KEYCLOAK_ADMIN_PASSWORD`. Do not paste `.env` values or a fully rendered Compose configuration into issue reports.

| Service | Port | Database / role |
| --- | --- | --- |
| Transport | 8082 | transport |
| Fleet | 8083 | fleet |
| Analytics | 8084 | analytics |
| Tracking | 8085 | tracking |
| Keycloak | 8081 | keycloak |

Each service has a separate database and non-superuser PostgreSQL account; public connection grants to those databases are revoked. Services own their schema to run Flyway migrations locally. The PostgreSQL administrator password is never supplied to an application container. Database initialization runs only when its named volume is first created. Changing `.env` after initialization does not rotate credentials in existing PostgreSQL or RabbitMQ volumes: rotate the actual accounts and configuration together. `docker compose down` retains persisted data; removing named volumes destroys it.

The Java images run as an unprivileged user, drop Linux capabilities, have a read-only filesystem and use a temporary `/tmp`. Startup waits for PostgreSQL initialization, RabbitMQ and Keycloak readiness. Each application has an HTTP health check. Durable broker queues and database volumes persist events across ordinary restarts. The local stack uses one authenticated broker account shared by services; production needs per-service broker identities and publish/consume permissions, plus a dedicated management identity.

## Upgrading an existing development broker

A fresh volume starts directly on RabbitMQ 4.3.6. If an existing volume was initialized on 4.1, preserve it and upgrade through 4.2 first. RabbitMQ requires all stable feature flags enabled before each series upgrade and only supports 4.2-to-4.3 for the final step. Review the [official upgrade guide](https://www.rabbitmq.com/docs/upgrade), back up the broker data, and run these from the repository root:

```powershell
docker compose exec -T rabbitmq rabbitmqctl enable_feature_flag all
docker compose -f compose.yml -f infra/compose.rabbit42.yml up -d --no-deps --wait rabbitmq
docker compose exec -T rabbitmq rabbitmqctl enable_feature_flag all
docker compose up -d --no-deps --wait rabbitmq
docker compose exec -T rabbitmq rabbitmqctl enable_feature_flag all
```

The temporary override uses `rabbitmq:4.2.9-management-alpine`, the last published community 4.2 image in Docker Hub. Later 4.2.10/4.2.11 patches are listed under commercial support and their community Docker Hub image tags are unavailable. Do not leave the bridge version running as the final target. These commands retain the same named volume; they never delete data. Check health, application reconnection, outstanding outbox events and dead-letter queues after the upgrade. For a production broker, use a tested backup/restore and the full upgrade procedure appropriate to its topology.

## Demo identity

The `plantflow` realm imports demo users `admin`, `dispatcher`, `employee`, `driver`, `driver2` and `driver3`, all with password `PlantFlow123!`. These are development-only accounts. The application `ADMIN` role does not grant Keycloak realm administration. The `dev` Spring profile seeds fleet directory entries matching the three driver usernames.

The public `plantflow-web` client accepts only authorization-code flow with PKCE S256. Password/direct grants and implicit flow are disabled; frontend code must use the browser redirect flow. Registered redirect origins are exactly `http://localhost:5173`, `http://localhost:5273` (alternate local shell port), and `http://localhost:8080`, with paths allowed beneath each origin. Tokens expire after five minutes and include the `plantflow-api` audience. Brute-force protection is enabled and self-registration is disabled. HTTP is limited to local development; the public issuer is `http://localhost:8081/realms/plantflow`, while containers fetch signing keys from Keycloak over the private Docker network. See Keycloak's [hostname configuration](https://www.keycloak.org/server/hostname) and [health checks](https://www.keycloak.org/observability/health).

Realm import only creates a realm if absent. Editing the JSON does not update an existing imported realm; apply deliberate changes through Keycloak administration or recreate a disposable development database after preserving anything needed.

## Device provisioning

`plantflow-device-example` is disabled and contains no committed client secret. It illustrates a confidential service-account client with the `DEVICE` role, `plantflow-api` audience and signed numeric `vehicle_id=1` claim. It has no user-login or password grant. The vehicle claim is issued by Keycloak and must never be taken from an untrusted payload as authorization.

Choose the real GPS/device vendor, communication protocol, installation and sampling requirements before enabling a hardware integration. Provision one client per vehicle through Keycloak administration, issue a unique credential or an approved certificate-based client authentication method, and set its vehicle claim to the actual fleet ID. Restrict its service account to `DEVICE`; issue no employee/driver/admin roles. Store credentials on the device securely, rotate them and disable lost devices promptly. Verify a token's audience, role and vehicle claim before testing the tracking ingestion API. Do not activate the example client as a shared production credential.

## Deployment boundary

This Compose stack is a local demo, not a production deployment. Before deploying, use TLS for browser and service traffic, production Keycloak mode, managed secrets, separate migration/runtime database roles, separate broker identities and policies, pinned image digests with vulnerability scanning, tested backups and restores, monitored dead-letter queues and retention policies, network controls, and plant-specific location access/retention rules. Remove all demo identities and require appropriate workforce identity policies. Select supported versions and verify upgrade migrations in CI. High availability and real-device GPS reliability have not been certified by this setup.
