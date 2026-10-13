import { describe, expect, it } from "vitest";
import { canCancel, canReserve, nextStatus, ridePayload } from "./utils";

describe("ride validation", () => {
  const values = {
    originId: "1",
    destinationId: "2",
    seats: "3",
    purpose: " Inspection ",
  };
  it("normalizes form values for the API", () =>
    expect(ridePayload(values)).toEqual({
      originId: 1,
      destinationId: 2,
      seats: 3,
      purpose: "Inspection",
    }));
  it("rejects identical stops, fractional seats and empty purpose", () => {
    expect(() => ridePayload({ ...values, destinationId: "1" })).toThrow(
      "different",
    );
    expect(() => ridePayload({ ...values, seats: "1.5" })).toThrow(
      "passengers",
    );
    expect(() => ridePayload({ ...values, purpose: " " })).toThrow("purpose");
  });
});
describe("trip actions", () => {
  it("only offers cancellation to the owner or dispatch before departure", () => {
    const trip = {
      status: "ASSIGNED",
      kind: "ON_DEMAND",
      requestedBy: "alice",
    };
    expect(canCancel(trip, { username: "alice", role: "EMPLOYEE" })).toBe(true);
    expect(canCancel(trip, { username: "bob", role: "EMPLOYEE" })).toBe(false);
    expect(
      canCancel({ ...trip, status: "IN_PROGRESS" }, { role: "ADMIN" }),
    ).toBe(false);
  });
  it("never advances terminal states", () => {
    expect(nextStatus("COMPLETED")).toBeUndefined();
    expect(nextStatus("CANCELLED")).toBeUndefined();
    expect(nextStatus("ARRIVED")).toBe("IN_PROGRESS");
  });
  it("closes reservations at departure and when boarding starts", () => {
    const trip = {
      kind: "SHUTTLE",
      status: "ASSIGNED",
      scheduledAt: "2099-01-01T00:00:00Z",
    };
    expect(canReserve(trip)).toBe(true);
    expect(canReserve({ ...trip, status: "ARRIVED" })).toBe(false);
    expect(canReserve({ ...trip, scheduledAt: "2000-01-01T00:00:00Z" })).toBe(
      false,
    );
  });
});
