export const trackableTrip = (trip, user) =>
  ["ASSIGNED", "ARRIVED", "IN_PROGRESS"].includes(trip.status) &&
  (trip.driverId === user.username ||
    (trip.kind === "ON_DEMAND" && trip.requestedBy === user.username) ||
    Boolean(trip.myBookingId));
export const isStale = (position, now = Date.now()) =>
  now - new Date(position.recordedAt).getTime() > 30000;
export function validPosition(position) {
  return (
    Number.isFinite(position.latitude) &&
    position.latitude >= -90 &&
    position.latitude <= 90 &&
    Number.isFinite(position.longitude) &&
    position.longitude >= -180 &&
    position.longitude <= 180 &&
    Number.isFinite(new Date(position.recordedAt).getTime())
  );
}
export function consumeFrames(buffer, onPosition) {
  const frames = buffer.replaceAll("\r\n", "\n").split("\n\n");
  const remainder = frames.pop();
  for (const frame of frames) {
    const lines = frame.split("\n");
    if (!lines.some((line) => line.trim() === "event: position")) continue;
    const json = lines
      .filter((line) => line.startsWith("data:"))
      .map((line) => line.slice(5).trimStart())
      .join("\n");
    try {
      const position = JSON.parse(json);
      if (validPosition(position)) onPosition(position);
    } catch {
      /* Ignore malformed stream frames; polling reconciles state. */
    }
  }
  return remainder;
}
