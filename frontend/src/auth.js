import Keycloak from "keycloak-js";
import { ApiError, configureApi } from "./api";
const identity = new Keycloak({
  url: import.meta.env.VITE_KEYCLOAK_URL || "http://localhost:8081",
  realm: import.meta.env.VITE_KEYCLOAK_REALM || "plantflow",
  clientId: import.meta.env.VITE_KEYCLOAK_CLIENT || "plantflow-web",
});
let initialization;
export function accountFromToken(token) {
  const roles = token.realm_access?.roles || [];
  const role = ["ADMIN", "DISPATCHER", "DRIVER", "EMPLOYEE"].find((value) =>
    roles.includes(value),
  );
  if (!role)
    throw new Error(
      "Your account has no PlantFlow role. Contact your administrator.",
    );
  return {
    username: token.preferred_username,
    name: token.name || token.preferred_username,
    role,
  };
}
export async function initializeAuth() {
  initialization ||= identity.init({
    onLoad: "check-sso",
    pkceMethod: "S256",
    checkLoginIframe: false,
    flow: "standard",
    responseMode: "query",
  });
  const authenticated = await initialization;
  configureApi(async () => {
    try {
      await identity.updateToken(30);
    } catch {
      throw new ApiError(
        "Your session has expired. Please sign in again.",
        401,
      );
    }
    if (!identity.token) throw new ApiError("Please sign in to continue.", 401);
    return identity.token;
  });
  return authenticated ? accountFromToken(identity.tokenParsed) : null;
}
export const login = () =>
  identity.login({ redirectUri: window.location.origin });
export const logout = () =>
  identity.logout({ redirectUri: window.location.origin });
