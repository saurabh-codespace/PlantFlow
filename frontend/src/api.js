let getToken;
export function configureApi(tokenProvider) {
  getToken = tokenProvider;
}
export class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.status = status;
  }
}
export async function request(
  path,
  { method = "GET", body, signal, raw = false } = {},
) {
  // Remotes receive this capability; access tokens never enter props, storage or URLs.
  if (
    !path.startsWith("/") ||
    path.startsWith("//") ||
    path.includes("..") ||
    path.includes("\\")
  )
    throw new ApiError("Invalid API path.", 400);
  if (!getToken) throw new ApiError("Please sign in to continue.", 401);
  const token = await getToken();
  const headers = {
    Authorization: `Bearer ${token}`,
    Accept: raw ? "*/*" : "application/json",
  };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  let response;
  try {
    response = await fetch(`/api${path}`, {
      method,
      headers,
      signal,
      credentials: "omit",
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (error) {
    if (error.name === "AbortError") throw error;
    throw new ApiError(
      "Cannot reach the server. Check your connection and retry.",
      0,
    );
  }
  if (!response.ok) {
    const data = await response.json().catch(() => ({}));
    throw new ApiError(
      data.message ||
        (response.status === 401
          ? "Your session has expired. Please sign in again."
          : `Request failed (${response.status}). Please retry.`),
      response.status,
    );
  }
  if (raw) return response;
  if (response.status === 204) return null;
  return response.json();
}
export async function downloadReport(days) {
  const response = await request(`/analytics/export?days=${days}`, {
    raw: true,
  });
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement("a");
  link.href = url;
  link.download = `plantflow-trips-${days}days.csv`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
