import { test, expect } from "@playwright/test";

async function signIn(page, username) {
  await page.goto("/");
  await page
    .getByRole("button", { name: "Continue with secure sign-in" })
    .click();
  await page.locator("#username").fill(username);
  await page
    .locator("#password")
    .fill(process.env.PLANTFLOW_TEST_PASSWORD || "PlantFlow123!");
  await page.locator("#kc-login").click();
  await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
}

test("settled analytics renders nonzero bars from the live projection", async ({
  page,
}) => {
  await signIn(page, "dispatcher");
  await expect(
    page.getByRole("heading", { name: "Operations overview." }),
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
  const reportedTrips = await page.evaluate(async () => {
    const { request } = await import("/src/api.js");
    return request("/analytics?days=7");
  });
  expect(reportedTrips.totalTrips).toBeGreaterThan(0);
  const response = page.waitForResponse((response) =>
    response.url().includes("/api/analytics?days=7"),
  );
  await page.getByRole("button", { name: "Refresh data" }).click();
  await response;
  await expect(
    page
      .locator(".metric")
      .filter({ hasText: "Active trips" })
      .locator(".metric-value"),
  ).toHaveText(String(reportedTrips.activeTrips));
  await page.screenshot({
    path: "artifacts/plantflow-live-overview.png",
    animations: "disabled",
    fullPage: true,
  });
  await page.getByRole("button", { name: "On-demand rides" }).click();
  await expect(
    page.getByRole("heading", { name: "Ride dispatch." }),
  ).toBeVisible();
  await page.screenshot({
    path: "artifacts/plantflow-live-dispatch.png",
    animations: "disabled",
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "artifacts/plantflow-live-mobile.png",
    animations: "disabled",
    fullPage: true,
  });
});

test("real OIDC, booking saga, driver lifecycle, location SSE and analytics", async ({
  browser,
}) => {
  const baseURL = "http://localhost:5273";
  const riderContext = await browser.newContext({ baseURL });
  const dispatcherContext = await browser.newContext({
    baseURL,
    viewport: { width: 1440, height: 1000 },
  });
  // Browser geolocation is controlled test input; APIs, identity, broker and database are real.
  const driverContext = await browser.newContext({
    baseURL,
    permissions: ["geolocation"],
    geolocation: { latitude: 22.682, longitude: 80.779, accuracy: 5 },
  });
  const rider = await riderContext.newPage();
  const dispatch = await dispatcherContext.newPage();
  const driver = await driverContext.newPage();
  const purpose = `Browser integration ${Date.now()}`;
  let tripId;
  try {
    await signIn(rider, "employee");
    await rider
      .getByRole("button", { name: "Book a ride", exact: true })
      .click();
    await expect(
      rider.locator('select[name="originId"] option'),
    ).not.toHaveCount(1);
    await rider.getByLabel("Pickup location").selectOption({ index: 1 });
    await rider
      .getByLabel("Destination", { exact: true })
      .selectOption({ index: 2 });
    await rider.getByLabel("Purpose of travel").fill(purpose);
    const created = rider.waitForResponse(
      (response) =>
        response.url().endsWith("/api/trips") &&
        response.request().method() === "POST",
    );
    await rider.getByRole("button", { name: "Request ride" }).click();
    const createdResponse = await created;
    expect(createdResponse.ok()).toBe(true);
    tripId = (await createdResponse.json()).id;
    await expect(rider.getByRole("dialog")).toHaveCount(0);
    const forbidden = await rider.evaluate(async () => {
      const { request } = await import("/src/api.js");
      try {
        await request("/drivers");
        return 200;
      } catch (error) {
        return error.status;
      }
    });
    expect(forbidden).toBe(403);

    await signIn(dispatch, "dispatcher");
    await dispatch.getByRole("button", { name: "On-demand rides" }).click();
    const dispatchCard = dispatch
      .locator(".trip-card")
      .filter({ hasText: purpose });
    await dispatchCard.getByRole("button", { name: "Assign vehicle" }).click();
    await expect(
      dispatch.locator('select[name="vehicleId"] option'),
    ).not.toHaveCount(1);
    await dispatch
      .getByLabel("Vehicle", { exact: true })
      .selectOption({ index: 1 });
    await dispatch
      .getByLabel("Driver", { exact: true })
      .selectOption({ index: 1 });
    const driverName = await dispatch
      .locator('select[name="driverId"]')
      .inputValue();
    const assigned = dispatch.waitForResponse((response) =>
      response.url().endsWith(`/api/trips/${tripId}/assign`),
    );
    await dispatch
      .getByRole("button", { name: "Assign vehicle & driver" })
      .click();
    expect((await assigned).status()).toBe(202);
    await expect(dispatchCard.locator(".badge")).toHaveText("Assigned");

    await signIn(driver, driverName);
    await driver
      .getByRole("button", { name: "Live tracking", exact: true })
      .click();
    await driver.getByLabel("Active trip").selectOption(String(tripId));
    const posted = driver.waitForResponse(
      (response) =>
        response.url().endsWith(`/api/tracking/trips/${tripId}/positions`) &&
        response.request().method() === "POST",
    );
    await driver.getByRole("button", { name: "Share my location" }).click();
    expect((await posted).ok()).toBe(true);
    await expect(
      driver.getByText("Live stream connected", { exact: true }),
    ).toBeVisible();
    await dispatch
      .getByRole("button", { name: "Live tracking", exact: true })
      .click();
    await expect(
      dispatch.locator(".position-card").filter({ hasText: driverName }),
    ).toBeVisible();
    await dispatch.screenshot({
      path: "test-results/plantflow-live-tracking.png",
      animations: "disabled",
      fullPage: true,
    });
    const removed = driver.waitForResponse(
      (response) =>
        response.url().endsWith(`/api/tracking/trips/${tripId}/positions/me`) &&
        response.request().method() === "DELETE",
    );
    await driver.getByRole("button", { name: "Stop sharing" }).click();
    expect((await removed).ok()).toBe(true);
    await expect(
      dispatch.locator(".position-card").filter({ hasText: driverName }),
    ).toHaveCount(0);

    await driver
      .getByRole("button", { name: "My driving tasks", exact: true })
      .click();
    const driverCard = driver
      .locator(".trip-card")
      .filter({ hasText: purpose });
    await driverCard.getByRole("button", { name: "Start boarding" }).click();
    await driverCard
      .getByRole("button", { name: "Start trip", exact: true })
      .click();
    await driverCard.getByRole("button", { name: "Complete trip" }).click();
    await driver
      .getByRole("button", { name: "Completed", exact: true })
      .click();
    await expect(driverCard.locator(".badge")).toHaveText("Completed");
    await dispatch
      .getByRole("button", { name: "Overview", exact: true })
      .click();
    await expect(
      dispatch.getByText("Total movements", { exact: true }),
    ).toBeVisible();
    await dispatch.screenshot({
      path: "test-results/plantflow-live-overview.png",
      animations: "disabled",
      fullPage: true,
    });
    await dispatch.getByRole("button", { name: "Analytics & reports" }).click();
    const downloadPromise = dispatch.waitForEvent("download");
    await dispatch.getByRole("button", { name: "Export CSV" }).click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toContain(".csv");
    await download.saveAs("test-results/live-trip-report.csv");
  } finally {
    if (tripId && !dispatch.isClosed())
      await dispatch
        .evaluate(async (id) => {
          try {
            const { request } = await import("/src/api.js");
            await request(`/trips/${id}/status`, {
              method: "POST",
              body: { status: "CANCELLED" },
            });
          } catch {
            /* Completed trips are immutable; a failed test cancels its unfinished booking where allowed. */
          }
        }, tripId)
        .catch(() => {});
    await Promise.all([
      riderContext.close(),
      dispatcherContext.close(),
      driverContext.close(),
    ]);
  }
});
