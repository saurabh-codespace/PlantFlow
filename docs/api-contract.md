# PlantFlow API contract

All endpoints are under `/api`, authenticated with `Authorization: Bearer <access-token>`. The shell signs in through Keycloak authorization code + PKCE, refreshes tokens in memory, and derives `{username,name,role}` from verified identity claims. Backend services independently validate JWT signature, issuer, expiry and `plantflow-api` audience. There are no local `/auth` endpoints or password database. Roles ADMIN, DISPATCHER, EMPLOYEE, DRIVER; DEVICE is limited to telemetry ingestion. Domain errors return `{message}`; standard authentication errors can have an empty body.

- GET `/locations` -> `[{id,name,zone,x,y}]` (x/y schematic percentages, not GPS).
- GET `/vehicles` -> `[{id,code,type,capacity,status,busy}]`; status AVAILABLE or MAINTENANCE; type BUGGY, VAN, BUS.
- GET `/drivers` -> `[{username,name,busy}]` (dispatch/admin).
- GET `/trips` -> array of Trip below. Employees see their on-demand trips and all shuttles; drivers see their assigned trips; dispatch/admin see all. Most recent first.
- POST `/trips` `{originId,destinationId,seats,purpose}` -> Trip. Seats 1–40, purpose required (max 200). Creates on-demand booking for current user.
- POST `/shuttles` `{originId,destinationId,scheduledAt,vehicleId,driverId}` -> HTTP 202 Trip in ASSIGNING state. ISO UTC scheduledAt, future. Dispatch/admin only. Fleet confirms or rejects asynchronously. Confirmed vehicle/driver are reserved until trip finishes.
- POST `/trips/{id}/reservations` `{seats}` -> Trip. Reserve shuttle seats for logged-in employee, one reservation per user/run, before departure and before boarding starts.
- DELETE `/trips/{id}/reservations/me` -> Trip. Cancel own shuttle reservation before boarding starts.
- POST `/trips/{id}/assign` `{vehicleId,driverId}` -> HTTP 202 Trip. Dispatch/admin only; REQUESTED -> ASSIGNING -> ASSIGNED after fleet confirmation, or back to REQUESTED with assignmentError on rejection. A rejected shuttle becomes CANCELLED. Poll trips to observe the result.
- POST `/trips/{id}/status` `{status}` -> Trip. ASSIGNED -> ARRIVED -> IN_PROGRESS -> COMPLETED. Driver owning trip or dispatch/admin. CANCELLED allowed before IN_PROGRESS by dispatcher or on-demand booking owner. Terminal states immutable.
- POST `/vehicles` `{code,type,capacity}` -> vehicle; PATCH `/vehicles/{id}/status` `{status}` -> vehicle. Dispatch/admin only.
- POST `/drivers` `{username,name}` -> driver. Dispatch/admin only. Register a username already provisioned with DRIVER role in Keycloak.
- GET `/analytics?days=7` (1–90, dispatch/admin) -> `{totalTrips,completedTrips,cancelledTrips,activeTrips,completionRate,averageWaitMinutes,passengersMoved,fleetBusy,fleetAvailable,fleetMaintenance,daily:[{date,trips,completed,passengers}],routes:[{route,trips,passengers}],byKind:[{kind,trips}],lastUpdated}`. Window is trip creation time, UTC calendar days inclusive of today; wait is created-to-arrived on-demand only, excluding cancelled trips. Passengers moved counts booked seats on completed trips, not independently verified boarding. Trip/fleet projections update asynchronously from durable events; lag depends on broker health.
- GET `/analytics/export?days=7` CSV with per-trip facts for same window.
- GET `/events` -> `[{id,tripId,type,occurredAt,actor}]` latest 50, dispatch/admin.

Trip: `{id,kind,originId,destinationId,origin,destination,status,passengers,requestedBy,purpose,vehicleId,vehicleLabel,capacity,driverId,driverName,scheduledAt,createdAt,assignedAt,arrivedAt,startedAt,completedAt,myBookingId,assignmentError}`. kind ON_DEMAND or SHUTTLE. Nullable assignment/timestamps/error. `myBookingId` null unless current user has active reservation. IDs are numbers except usernames. All timestamps ISO instants. ASSIGNING cannot be cancelled until the fleet decision arrives. Operations list is capped at 500 most recent trips.

Tracking service:

- GET `/tracking/positions` -> `[{tripId,subject,username,latitude,longitude,accuracy,recordedAt,vehicleId,vehicleLabel}]`, positions from the last five minutes. Subject DRIVER, RIDER or VEHICLE. Dispatch sees all active-trip positions; assigned drivers see their trip; riders see their own position and their trip's driver/vehicle, never other riders.
- POST `/tracking/trips/{id}/positions` `{latitude,longitude,accuracy,recordedAt}`. Phone GPS, explicit opt-in. Accuracy in meters; timestamp within last 60 seconds, at most five seconds ahead; strictly newer than previous fix. Max one update/second. Sender must be assigned driver or reserved rider on ASSIGNED/ARRIVED/IN_PROGRESS trip.
- DELETE `/tracking/trips/{id}/positions/me` -> 204, removes sender's current position.
- GET `/tracking/stream` -> SSE `event: position`, JSON Position. Authenticate fetch stream with bearer header, never a token query parameter. Stream closes after 60 seconds or token expiry; refresh token and reconnect. Poll positions to reconcile removed/stopped senders.
- POST `/tracking/device/positions` same update body, DEVICE role and positive signed `vehicle_id` claim required. Finds that vehicle's active trip. Uses OAuth2 client credentials with a separate client per vehicle; no hardware vendor adapter is bundled.

Tracking access follows asynchronous trip events. New access can briefly return 409 until synchronized. Closed trips purge positions when their event arrives; a cleanup task deletes idle live rows after 15 minutes. The API stores latest positions, not travel history.

Local dev Keycloak realm: plantflow. Demo accounts dispatcher, employee, driver, driver2, driver3, admin use `PlantFlow123!`. Ports: identity8081, transport8082, fleet8083, analytics8084, tracking8085; shell5173 proxies `/api`. Four frontend remotes use5174–5177. See [event contract](event-contract.md) for asynchronous behavior.
