# PlantFlow web workspace

React 19 shell with four independently built and deployed Module Federation remotes. The shell owns navigation, the shared design system, Keycloak identity and the authenticated API capability. Remotes receive data and actions through props; they never receive access tokens. React is shared as a singleton. A failed remote is contained by a React error boundary.

| Application | Development port | Build output | Responsibility |
| --- | --- | --- | --- |
| Shell | 5173 | `dist/shell` | OIDC, navigation, shared forms, service polling |
| Transport | 5174 | `dist/transport` | On-demand rides, dispatch, shift reservations, driver tasks |
| Fleet | 5175 | `dist/fleet` | Vehicle maintenance, availability, driver roster |
| Analytics | 5176 | `dist/analytics` | Operations dashboard, Recharts reports, CSV, activity |
| Tracking | 5177 | `dist/tracking` | Leaflet live positions, SSE, opt-in device sharing |

## Run

Use Node 20.19+ or 22.12+. Start the backend infrastructure and services described in the repository README, then:

```powershell
cd frontend
npm ci
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
npm run dev
```

Open `http://localhost:5173`. Each remote also has a separate command, such as `npm run dev:transport`, `npm run build:analytics`, or `npm run preview:tracking`. `npm run build` builds all five deployment artifacts; `npm run preview` serves those artifacts on their respective ports.

The shell proxies transport APIs to 8082, fleet APIs to 8083, analytics APIs to 8084, and tracking APIs to 8085. Keycloak runs on 8081. OIDC uses Authorization Code with PKCE S256. Access and refresh tokens stay in Keycloak adapter memory, never local/session storage. Mutations use bearer authorization and omit cookies. The backend independently enforces all permissions; hiding a tab is only a UI convenience.

## Deployment

Set each `VITE_*_PUBLIC_URL` to the absolute origin/path serving that remote before building it. Set the shell's `VITE_*_REMOTE` to each remote's `remoteEntry.js`. Set `VITE_SHELL_ORIGIN` to the exact permitted frontend origin for development/preview CORS. These variables are build-time configuration, not secrets.

Serve each output directory independently. Production hosting must route `/api` to the respective services, allow the shell origin on remote assets, apply an appropriate CSP, and serve HTTPS. Allowlist exact shell redirect URIs and web origins in the Keycloak public client. Never add a client secret to frontend configuration. Shell and remote compatibility depends on the component props described above and the shared React major version.

Use an approved tile provider with `VITE_MAP_TILE_URL` and attribution. The default is OpenStreetMap; tiles disclose the viewer's IP and visible map area to the provider. No tile requests occur until a real position exists or `VITE_MAP_CENTER=latitude,longitude` is configured. The operations overview is a clearly labeled site schematic, not GPS.

Location sharing requires an explicit click, browser permission, HTTPS/localhost and participation in an assigned active trip. Geolocation uploads are throttled to at most one per five seconds. It stops on navigation, unmount, terminal trip state or explicit stop; stop also requests deletion of the last published point. SSE uses authenticated fetch headers, with five-second snapshot polling for reconciliation/fallback. Points older than 30 seconds are marked stale. Browser tracking is foreground-only; production background mobile tracking requires a native application and device policy.

## Validate

```powershell
npm test
npm run build
npx playwright install chromium
npm run test:browser
```

Unit tests cover mutation credentials, URL restrictions, booking validation, trip action rules, tracking authorization hints, stale coordinates and partial SSE parsing. Browser tests run the real federated development applications with **test-only intercepted identity and API fixtures**; they verify remote loading, booking payloads, mobile layout and role-sensitive navigation. They do not replace full integration testing with Keycloak, RabbitMQ, databases and real devices. Browser screenshots are written under the ignored `test-results/` directory.

UI libraries: [Radix Dialog](https://www.radix-ui.com/primitives/docs/components/dialog), [Motion](https://motion.dev/docs/react-use-reduced-motion), [Recharts](https://recharts.github.io/en-US/examples/SimpleBarChart/), [React Leaflet](https://react-leaflet.js.org/docs/start-installation/), and Lucide icons. Motion follows the user's reduced-motion preference. Federation setup follows the [official Vite integration](https://module-federation.io/integrations/build-tool/vite); identity follows the [Keycloak JavaScript adapter](https://www.keycloak.org/securing-apps/javascript-adapter).

`npm run test:live` is a separate, opt-in test against running backend services and the development Keycloak realm. It creates a booking, verifies asynchronous assignment, checks role denial, completes the driver lifecycle, exports analytics, and sends controlled browser-geolocation coordinates through the real tracking API/SSE path. It creates real test records. Both browser suites use isolated ports 5273-5277, and Keycloak must allow the shell redirect/origin `http://localhost:5273` for the live test. `VITE_SHELL_PORT` and each `VITE_<MODULE>_PORT` can also override development ports. Development servers bind to loopback only.

Development deliberately serves built remote bundles alongside the shell dev server: this avoids federation/Vite dependency-discovery reloads resetting open forms. After editing a remote, run `npm run build:<module>` and refresh the shell, or run `npm run watch:<module>` in another terminal. Shell edits retain Vite hot reload. Each remote can be developed independently with its own build/preview command.
