import { test, expect } from "@playwright/test";

const locations = [
  { id: 1, name: "Main gate", zone: "North", x: 20, y: 22 },
  { id: 2, name: "Turbine hall", zone: "East", x: 72, y: 48 },
  { id: 3, name: "Control room", zone: "Central", x: 47, y: 74 },
];
const vehicles = [
  {
    id: 1,
    code: "EV-001",
    type: "BUGGY",
    capacity: 6,
    status: "AVAILABLE",
    busy: false,
  },
];
const trip = {
  id: 1,
  kind: "ON_DEMAND",
  originId: 1,
  destinationId: 2,
  origin: "Main gate",
  destination: "Turbine hall",
  status: "REQUESTED",
  passengers: 2,
  requestedBy: "employee",
  purpose: "Equipment inspection",
  createdAt: "2026-10-13T10:00:00Z",
  myBookingId: null,
};
const analytics = {
  totalTrips: 6,
  completedTrips: 4,
  cancelledTrips: 1,
  activeTrips: 1,
  completionRate: 66.7,
  averageWaitMinutes: 3,
  passengersMoved: 20,
  daily: [
    { date: "2026-10-12", trips: 3, completed: 2 },
    { date: "2026-10-13", trips: 3, completed: 2 },
  ],
  routes: [{ route: "Main gate → Turbine hall", trips: 6, passengers: 20 }],
  byKind: [{ kind: "ON_DEMAND", trips: 6 }],
  lastUpdated: "2026-10-13T10:00:00Z",
};

async function stubServices(page, role = "DISPATCHER") {
  // Browser-only fixtures validate the actual federation runtime without a live identity/service stack.
  await page.route("**/src/auth.js*", (route) =>
    route.fulfill({
      contentType: "application/javascript",
      body: `import { configureApi } from '/src/api.js'; configureApi(async () => 'test-token'); export const initializeAuth = async () => ({ username: '${role === "DRIVER" ? "driver" : "employee"}', name: 'Alex Morgan', role: '${role}' }); export const login = async () => {}; export const logout = async () => {};`,
    }),
  );
  await page.route("**/api/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith("/stream"))
      return route.fulfill({
        status: 503,
        json: { message: "Polling fallback test" },
      });
    const data = {
      "/api/locations": locations,
      "/api/vehicles": vehicles,
      "/api/drivers": [{ username: "driver", name: "Ravi Kumar", busy: false }],
      "/api/trips": [trip],
      "/api/analytics": analytics,
      "/api/events": [
        {
          id: "a",
          tripId: 1,
          type: "TRIP_REQUESTED",
          actor: "employee",
          occurredAt: trip.createdAt,
        },
      ],
      "/api/tracking/positions": [],
    };
    if (route.request().method() === "POST")
      return route.fulfill({
        status: 200,
        json: { ...trip, ...route.request().postDataJSON() },
      });
    return route.fulfill({ status: 200, json: data[path] || [] });
  });
}

test("federated workspaces load and booking submits validated fields", async ({
  page,
}) => {
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await stubServices(page);
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "Operations overview." }),
  ).toBeVisible();
  await expect(
    page.getByText("Total movements", { exact: true }),
  ).toBeVisible();
  await expect
    .poll(() =>
      page
        .locator(".recharts-bar-rectangle")
        .evaluateAll((nodes) =>
          nodes.some((node) => node.getBBox().height > 0),
        ),
    )
    .toBe(true);
  await page.screenshot({
    path: "test-results/plantflow-desktop.png",
    animations: "disabled",
    fullPage: true,
  });
  await page.getByRole("button", { name: "On-demand rides" }).click();
  await expect(
    page.getByRole("heading", { name: "Ride dispatch." }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Book a ride", exact: true }).click();
  await page.getByLabel("Pickup location").selectOption("1");
  await page.getByLabel("Destination", { exact: true }).selectOption("2");
  await page.getByLabel("Purpose of travel").fill("Turbine inspection");
  const submitted = page.waitForRequest(
    (request) =>
      request.url().endsWith("/api/trips") && request.method() === "POST",
  );
  await page.getByRole("button", { name: "Request ride" }).click();
  expect((await submitted).postDataJSON()).toEqual({
    originId: 1,
    destinationId: 2,
    seats: 1,
    purpose: "Turbine inspection",
  });
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page.getByRole("button", { name: "Fleet management" }).click();
  await expect(page.getByRole("heading", { name: "EV-001" })).toBeVisible();
  await page
    .getByRole("button", { name: "Live tracking", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Waiting for the first live location" }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Share my location" }),
  ).toBeDisabled();
  await page.getByRole("button", { name: "Analytics & reports" }).click();
  await expect(
    page.getByRole("heading", { name: "Transport analytics." }),
  ).toBeVisible();
  expect(errors).toEqual([]);
});

test("mobile employee view keeps dispatcher modules hidden", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await stubServices(page, "EMPLOYEE");
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "My rides." })).toBeVisible();
  await page.getByRole("button", { name: "Open navigation" }).click();
  await expect(
    page.getByRole("button", { name: "Fleet management" }),
  ).toHaveCount(0);
  await page
    .getByRole("button", { name: "Shift shuttles", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Shift shuttles." }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Schedule shuttle" }),
  ).toHaveCount(0);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "test-results/plantflow-mobile.png",
    animations: "disabled",
    fullPage: true,
  });
});
