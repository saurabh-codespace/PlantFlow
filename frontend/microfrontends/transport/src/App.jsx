import { useState } from "react";
import {
  Plus,
  Search,
  Users,
  Clock3,
  ArrowUpRight,
  CarFront,
  BusFront,
} from "lucide-react";
import { Badge, Empty, Route, KindIcon, Form } from "../../../src/components";
import {
  activeTrip,
  canCancel,
  canReserve,
  dateTime,
  isDispatcher,
  label,
  nextStatus,
  statusAction,
} from "../../../src/utils";

import "./styles.css";
function TripBoard({
  trips,
  user,
  onAction,
  onBook,
  onAssign,
  onShuttle,
  shuttles = false,
  driver = false,
}) {
  const [filter, setFilter] = useState("active");
  const [search, setSearch] = useState("");
  const [busy, setBusy] = useState(null);
  const dispatch = isDispatcher(user);
  const matching = trips
    .filter(
      (trip) =>
        driver ||
        (shuttles ? trip.kind === "SHUTTLE" : trip.kind === "ON_DEMAND"),
    )
    .filter(
      (trip) =>
        filter === "all" ||
        (filter === "active" ? activeTrip(trip) : trip.status === filter),
    )
    .filter((trip) =>
      `${trip.origin} ${trip.destination} ${trip.id} ${trip.driverName || ""}`
        .toLowerCase()
        .includes(search.toLowerCase()),
    );
  async function action(trip, status) {
    setBusy(trip.id);
    try {
      await onAction(`/trips/${trip.id}/status`, { status });
    } finally {
      setBusy(null);
    }
  }
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">
            {driver
              ? "READY WHEN YOU ARE"
              : shuttles
                ? "ON TIME, EVERY SHIFT"
                : "EVERY JOURNEY MATTERS"}
          </div>
          <h1>
            {driver
              ? "My driving tasks"
              : shuttles
                ? "Shift shuttles"
                : dispatch
                  ? "Ride dispatch"
                  : "My rides"}
            <span className="heading-dot">.</span>
          </h1>
          <p>
            {driver
              ? "Your assigned trips, from boarding to arrival."
              : shuttles
                ? "Planned departures. Shared journeys. A better commute within your plant."
                : "Book, assign and follow on-demand transport across your site."}
          </p>
        </div>
        {!driver &&
          (!shuttles ? (
            <button className="button primary" onClick={onBook}>
              <Plus size={18} />
              Book a ride
            </button>
          ) : (
            dispatch && (
              <button className="button primary" onClick={onShuttle}>
                <Plus size={18} />
                Schedule shuttle
              </button>
            )
          ))}
      </div>
      <section className="panel">
        <div className="board-toolbar">
          <div className="segmented" aria-label="Trip filter">
            {[
              ["active", "Active"],
              ["COMPLETED", "Completed"],
              ["CANCELLED", "Cancelled"],
              ["all", "All trips"],
            ].map(([value, name]) => (
              <button
                key={value}
                aria-pressed={filter === value}
                className={filter === value ? "selected" : ""}
                onClick={() => setFilter(value)}
              >
                {name}
              </button>
            ))}
          </div>
          <label className="search">
            <Search size={17} />
            <input
              aria-label="Search trips"
              placeholder="Search route, trip or driver"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
          </label>
        </div>
        {matching.length ? (
          <div className="trip-cards">
            {matching.map((trip) => (
              <article className="trip-card" key={trip.id}>
                <div className="trip-card-top">
                  <span className="trip-code">
                    <KindIcon kind={trip.kind} />{" "}
                    {trip.kind === "SHUTTLE" ? "SHUTTLE" : "RIDE"} #{trip.id}
                  </span>
                  <Badge value={trip.status} />
                </div>
                <Route trip={trip} />
                <p className="trip-purpose">
                  {trip.purpose || "Scheduled shift transport"}
                </p>
                {trip.assignmentError && (
                  <div className="assignment-error" role="status">
                    {trip.assignmentError}
                  </div>
                )}
                {trip.status === "ASSIGNING" && (
                  <p className="assignment-pending">
                    Waiting for fleet confirmation...
                  </p>
                )}
                <div className="trip-meta">
                  <span>
                    <Users size={15} />
                    {trip.passengers}
                    {trip.capacity ? ` / ${trip.capacity}` : ""} passengers
                  </span>
                  <span>
                    <Clock3 size={15} />
                    {dateTime(trip.scheduledAt || trip.createdAt)}
                  </span>
                </div>
                <div className="trip-assignment">
                  <div className="vehicle-icon">
                    <CarFront size={20} />
                  </div>
                  <div>
                    <strong>
                      {trip.vehicleLabel || "Awaiting vehicle assignment"}
                    </strong>
                    <small>
                      {trip.driverName || "A dispatcher will assign your ride"}
                    </small>
                  </div>
                </div>
                <div className="trip-actions">
                  {dispatch && trip.status === "REQUESTED" && (
                    <button
                      className="button primary small"
                      onClick={() => onAssign(trip)}
                    >
                      Assign vehicle <ArrowUpRight size={15} />
                    </button>
                  )}
                  {(dispatch || (driver && trip.driverId === user.username)) &&
                    nextStatus(trip.status) && (
                      <button
                        disabled={busy === trip.id}
                        className="button primary small"
                        onClick={() => action(trip, nextStatus(trip.status))}
                      >
                        {busy === trip.id
                          ? "Updating…"
                          : statusAction(trip.status)}
                      </button>
                    )}
                  {canCancel(trip, user) && (
                    <button
                      disabled={busy === trip.id}
                      className="button ghost small"
                      onClick={() => {
                        if (
                          window.confirm(
                            `Cancel trip #${trip.id}? This cannot be undone.`,
                          )
                        )
                          action(trip, "CANCELLED");
                      }}
                    >
                      Cancel ride
                    </button>
                  )}
                  {user.role === "EMPLOYEE" &&
                    canReserve(trip) &&
                    (trip.myBookingId ? (
                      <button
                        disabled={busy === trip.id}
                        className="button ghost small"
                        onClick={async () => {
                          setBusy(trip.id);
                          try {
                            await onAction(
                              `/trips/${trip.id}/reservations/me`,
                              undefined,
                              "DELETE",
                            );
                          } finally {
                            setBusy(null);
                          }
                        }}
                      >
                        Cancel my reservation
                      </button>
                    ) : trip.passengers < trip.capacity ? (
                      <Form
                        submit="Reserve seats"
                        onSubmit={async (values) => {
                          await onAction(
                            `/trips/${trip.id}/reservations`,
                            { seats: Number(values.seats) },
                            "POST",
                            true,
                          );
                        }}
                      >
                        <label className="reservation-label">
                          Seats
                          <input
                            name="seats"
                            type="number"
                            min="1"
                            max={trip.capacity - trip.passengers}
                            defaultValue="1"
                            required
                          />
                        </label>
                      </Form>
                    ) : (
                      <span className="muted">Shuttle is full</span>
                    ))}
                  {trip.myBookingId && (
                    <span className="reservation-confirmed">
                      You’re on the list
                    </span>
                  )}
                </div>
              </article>
            ))}
          </div>
        ) : (
          <Empty
            title={search ? "No matching journeys" : "No trips in this view"}
            text={
              search
                ? "Try another location, driver or trip number."
                : "Book a ride or check another filter to get moving."
            }
          />
        )}
      </section>
    </>
  );
}

export default function Transport(props) {
  return (
    <div className="pf-transport">
      <TripBoard {...props} />
    </div>
  );
}
