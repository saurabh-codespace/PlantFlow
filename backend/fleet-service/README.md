# Fleet service

Owns vehicles, the driver directory and active allocations. Runs on port 8083 with its own PostgreSQL database. JWTs come from the shared Keycloak realm; only dispatchers and administrators can modify the catalog or read the driver directory. Register each driver's Keycloak username through `POST /api/drivers` after provisioning that identity with the `DRIVER` realm role.

Assignment requests arrive through `plantflow.fleet`. An assignment, its inbox receipt and confirmation or rejection outbox event commit together. Vehicle and driver uniqueness constraints prevent double booking. Terminal trip events release resources; persisted terminal versions prevent delayed assignment requests from reactivating completed trips. The service never reads another service's database. Confirmed assignments stay reserved until the terminal event is consumed, including scheduled shuttles.

Every availability change emits a versioned snapshot for analytics. Fleet writes currently take one counter-row lock before resource locks, keeping snapshots consistent at single-plant dispatch volumes. Partition the counter and locking boundary when scaling to multiple plants or sustained high write traffic.

Configuration: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `OIDC_ISSUER`, `OIDC_JWK_SET_URI`, `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`. JWTs must include the `plantflow-api` audience. The `dev` profile creates sample vehicles and driver directory entries only. It does not create identity accounts.

Run the H2 PostgreSQL-mode integration tests from `backend`: `./mvnw -pl fleet-service -am test`. They cover concurrent resource contention and duplicate deliveries, durable event receipts, terminal event ordering, maintenance, assignment retries and role restrictions. RabbitMQ publishing is disabled for these tests; consumers are invoked through their transactional Spring proxies.
