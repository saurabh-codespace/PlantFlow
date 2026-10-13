export const isDispatcher = (user) =>
  ["ADMIN", "DISPATCHER"].includes(user?.role);
export const activeTrip = (trip) =>
  !["COMPLETED", "CANCELLED"].includes(trip.status);
export const nextStatus = (status) =>
  ({ ASSIGNED: "ARRIVED", ARRIVED: "IN_PROGRESS", IN_PROGRESS: "COMPLETED" })[
    status
  ];
export const statusAction = (status) =>
  ({
    ASSIGNED: "Start boarding",
    ARRIVED: "Start trip",
    IN_PROGRESS: "Complete trip",
  })[status];
export const label = (text) =>
  (text || "")
    .replaceAll("_", " ")
    .toLowerCase()
    .replace(/^./, (s) => s.toUpperCase());
export const number = (value) =>
  new Intl.NumberFormat("en-IN", { maximumFractionDigits: 1 }).format(
    value || 0,
  );
export const dateTime = (value) =>
  value
    ? new Date(value).toLocaleString("en-IN", {
        day: "2-digit",
        month: "short",
        hour: "2-digit",
        minute: "2-digit",
      })
    : "—";
export const canCancel = (trip, user) =>
  ["REQUESTED", "ASSIGNED", "ARRIVED"].includes(trip.status) &&
  (isDispatcher(user) ||
    (trip.kind === "ON_DEMAND" && trip.requestedBy === user.username));
export const canReserve = (trip) =>
  trip.kind === "SHUTTLE" &&
  trip.status === "ASSIGNED" &&
  new Date(trip.scheduledAt) > new Date();

export function ridePayload(values) {
  const payload = {
    originId: Number(values.originId),
    destinationId: Number(values.destinationId),
    seats: Number(values.seats),
    purpose: values.purpose.trim(),
  };
  if (
    !payload.originId ||
    !payload.destinationId ||
    payload.originId === payload.destinationId
  )
    throw new Error("Choose two different locations.");
  if (
    !Number.isInteger(payload.seats) ||
    payload.seats < 1 ||
    payload.seats > 40
  )
    throw new Error("Choose between 1 and 40 passengers.");
  if (!payload.purpose || payload.purpose.length > 200)
    throw new Error("Enter a purpose of up to 200 characters.");
  return payload;
}
