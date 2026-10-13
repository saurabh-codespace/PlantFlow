import { describe, expect, it } from "vitest";
import {
  consumeFrames,
  isStale,
  trackableTrip,
  validPosition,
} from "./tracking";
describe("live location safety and streaming", () => {
  const position = {
    tripId: 1,
    username: "driver",
    subject: "DRIVER",
    latitude: 22,
    longitude: 80,
    recordedAt: "2026-10-13T12:00:00Z",
  };
  it("only offers sharing for active trips where the user participates", () => {
    const trip = {
      status: "IN_PROGRESS",
      driverId: "driver",
      kind: "ON_DEMAND",
      requestedBy: "rider",
    };
    expect(trackableTrip(trip, { username: "driver" })).toBe(true);
    expect(trackableTrip(trip, { username: "stranger", role: "ADMIN" })).toBe(
      false,
    );
    expect(
      trackableTrip({ ...trip, status: "COMPLETED" }, { username: "driver" }),
    ).toBe(false);
  });
  it("marks locations stale after 30 seconds and rejects invalid coordinates", () => {
    expect(isStale(position, new Date("2026-10-13T12:00:31Z").getTime())).toBe(
      true,
    );
    expect(validPosition({ ...position, latitude: 91 })).toBe(false);
  });
  it("buffers split SSE frames and ignores heartbeats and malformed data", () => {
    const received = [];
    const partial = `event: position\ndata: ${JSON.stringify(position)}`;
    expect(
      consumeFrames(`: heartbeat\n\n${partial}`, (item) => received.push(item)),
    ).toBe(partial);
    expect(received).toEqual([]);
    expect(
      consumeFrames(
        `${partial}\n\nevent: position\ndata: invalid\n\n`,
        (item) => received.push(item),
      ),
    ).toBe("");
    expect(received).toEqual([position]);
  });
});
