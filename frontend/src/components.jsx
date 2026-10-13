import { useState } from "react";
import * as Dialog from "@radix-ui/react-dialog";
import {
  ArrowRight,
  X,
  LoaderCircle,
  MapPin,
  BusFront,
  CarFront,
  AlertCircle,
} from "lucide-react";
import { label, dateTime } from "./utils";

export function Badge({ value }) {
  return (
    <span className={`badge ${value?.toLowerCase()}`}>{label(value)}</span>
  );
}
export function Empty({
  title = "Nothing here yet",
  text = "New activity will appear here.",
}) {
  return (
    <div className="empty">
      <MapPin size={28} />
      <h3>{title}</h3>
      <p>{text}</p>
    </div>
  );
}
export function ErrorBox({ message, onRetry }) {
  return (
    <div className="error" role="alert">
      <AlertCircle size={18} />
      <span>{message}</span>
      {onRetry && (
        <button className="text-button" onClick={onRetry}>
          Retry
        </button>
      )}
    </div>
  );
}
export function Route({ trip }) {
  return (
    <div className="trip-route">
      <strong>{trip.origin}</strong>
      <ArrowRight size={15} />
      <strong>{trip.destination}</strong>
    </div>
  );
}
export function KindIcon({ kind, size = 18 }) {
  return kind === "SHUTTLE" ? (
    <BusFront size={size} />
  ) : (
    <CarFront size={size} />
  );
}
export function Modal({ title, children, onClose }) {
  return (
    <Dialog.Root
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <Dialog.Portal>
        <Dialog.Overlay className="dialog-overlay" />
        <Dialog.Content className="dialog-content" aria-describedby={undefined}>
          <div className="modal-heading">
            <Dialog.Title asChild>
              <h2>{title}</h2>
            </Dialog.Title>
            <Dialog.Close asChild>
              <button className="icon-button" aria-label="Close dialog">
                <X size={20} />
              </button>
            </Dialog.Close>
          </div>
          {children}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
export function Form({ onSubmit, submit = "Save", children }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  return (
    <form
      onSubmit={async (event) => {
        event.preventDefault();
        const data = Object.fromEntries(new FormData(event.currentTarget));
        setBusy(true);
        setError("");
        try {
          await onSubmit(data);
        } catch (err) {
          setError(err.message);
        } finally {
          setBusy(false);
        }
      }}
    >
      <fieldset disabled={busy}>{children}</fieldset>
      {error && <ErrorBox message={error} />}
      <button
        className="button primary form-submit"
        type="submit"
        disabled={busy}
      >
        {busy ? <LoaderCircle className="spin" size={18} /> : null}
        {busy ? "Saving…" : submit}
      </button>
    </form>
  );
}
export function LocationFields({ locations }) {
  return (
    <div className="form-grid">
      <label>
        Pickup location
        <select
          aria-label="Pickup location"
          name="originId"
          required
          defaultValue=""
        >
          <option value="" disabled>
            Select pickup
          </option>
          {locations.map((location) => (
            <option key={location.id} value={location.id}>
              {location.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        Destination
        <select
          aria-label="Destination"
          name="destinationId"
          required
          defaultValue=""
        >
          <option value="" disabled>
            Select destination
          </option>
          {locations.map((location) => (
            <option key={location.id} value={location.id}>
              {location.name}
            </option>
          ))}
        </select>
      </label>
    </div>
  );
}
export function AssignmentFields({ vehicles, drivers, minimum = 1 }) {
  return (
    <div className="form-grid">
      <label>
        Vehicle
        <select aria-label="Vehicle" name="vehicleId" required defaultValue="">
          <option value="" disabled>
            Select available vehicle
          </option>
          {vehicles
            .filter(
              (vehicle) =>
                !vehicle.busy &&
                vehicle.status === "AVAILABLE" &&
                vehicle.capacity >= minimum,
            )
            .map((vehicle) => (
              <option key={vehicle.id} value={vehicle.id}>
                {vehicle.code} · {vehicle.capacity} seats
              </option>
            ))}
        </select>
      </label>
      <label>
        Driver
        <select aria-label="Driver" name="driverId" required defaultValue="">
          <option value="" disabled>
            Select available driver
          </option>
          {drivers
            .filter((driver) => !driver.busy)
            .map((driver) => (
              <option key={driver.username} value={driver.username}>
                {driver.name}
              </option>
            ))}
        </select>
      </label>
    </div>
  );
}
export function ActivityList({ events, compact = false }) {
  return events.length ? (
    <div className="activity-list">
      {events.slice(0, compact ? 5 : 50).map((event) => (
        <div className="activity-item" key={event.id}>
          <span className="activity-dot" />
          <div>
            <strong>{label(event.type)}</strong>
            <p>
              Trip #{event.tripId} · {event.actor}
            </p>
          </div>
          <time>{dateTime(event.occurredAt)}</time>
        </div>
      ))}
    </div>
  ) : (
    <Empty
      title="All quiet for now"
      text="Booking, assignment and trip events appear here."
    />
  );
}
