import { lazy, useEffect, useState, useRef } from "react";
import { motion, useReducedMotion } from "motion/react";
import RemoteBoundary from "./RemoteBoundary";
import {
  Activity,
  ArrowRight,
  ArrowUpRight,
  BusFront,
  CarFront,
  ChartNoAxesCombined,
  ChevronDown,
  Factory,
  LayoutDashboard,
  LoaderCircle,
  LogOut,
  Menu,
  Plus,
  RefreshCw,
  Route as RouteIcon,
  ShieldCheck,
  X,
} from "lucide-react";
import { request, downloadReport } from "./api";
import { login, logout, initializeAuth } from "./auth";
import {
  ActivityList,
  AssignmentFields,
  ErrorBox,
  Form,
  LocationFields,
  Modal,
} from "./components";
const Analytics = lazy(() => import("analytics/App"));
const TripBoard = lazy(() => import("transport/App"));
const Fleet = lazy(() => import("fleet/App"));
const Tracking = lazy(() => import("tracking/App"));
import { isDispatcher, label, ridePayload } from "./utils";

const initialData = {
  locations: [],
  vehicles: [],
  drivers: [],
  trips: [],
  events: [],
  analytics: null,
};
const tabInfo = {
  overview: ["Overview", LayoutDashboard],
  bookings: ["On-demand rides", RouteIcon],
  shuttles: ["Shift shuttles", BusFront],
  fleet: ["Fleet management", CarFront],
  reports: ["Analytics & reports", ChartNoAxesCombined],
  activity: ["Activity log", Activity],
  tasks: ["My driving tasks", RouteIcon],
  tracking: ["Live tracking", Activity],
};

function Login({ onLogin, initialError }) {
  return (
    <div className="login-page">
      <div className="login-story">
        <a className="brand" href="/">
          <span className="brand-symbol">
            <RouteIcon size={23} />
          </span>
          plantflow<span className="brand-period">.</span>
        </a>
        <div>
          <div className="eyebrow">LESS WAITING. MORE WORKING.</div>
          <h1>
            Good days start
            <br />
            with better
            <br />
            <span>movement.</span>
          </h1>
          <p>
            One connected workspace for people, vehicles and every journey in
            between.
          </p>
          <div className="login-pills">
            <span>
              <CarFront size={16} />
              On-demand rides
            </span>
            <span>
              <BusFront size={16} />
              Shift shuttles
            </span>
            <span>
              <ChartNoAxesCombined size={16} />
              Live insights
            </span>
          </div>
        </div>
        <span className="login-foot">
          PURPOSE-BUILT FOR LIFE INSIDE YOUR PLANT
        </span>
      </div>
      <div className="login-form-wrap">
        <div className="login-form">
          <span className="login-icon">
            <Factory size={26} />
          </span>
          <div className="eyebrow">WELCOME TO YOUR WORKSPACE</div>
          <h2>Let’s get moving.</h2>
          <p>Sign in to manage your plant’s transport.</p>
          {initialError && <ErrorBox message={initialError} />}
          <button
            className="button primary form-submit"
            onClick={() => login()}
          >
            Continue with secure sign-in <ArrowUpRight size={17} />
          </button>
          <p className="sso-note">
            Continue with your plant identity provider.
          </p>
          {import.meta.env.DEV && (
            <details className="demo-details">
              <summary>Local development accounts</summary>
              <p>
                Use <strong>dispatcher</strong>, <strong>employee</strong>,{" "}
                <strong>driver</strong> or <strong>admin</strong> with password{" "}
                <code>PlantFlow123!</code>. These accounts belong to the local
                development identity realm.
              </p>
            </details>
          )}
          <div className="secure-note">
            <ShieldCheck size={16} />
            Secure, role-based access to your plant
          </div>
        </div>
      </div>
    </div>
  );
}

export default function App() {
  const [user, setUser] = useState(null);
  const stopTracking = useRef(null);
  const [booting, setBooting] = useState(true);
  const [tab, setTab] = useState("overview");
  const [data, setData] = useState(initialData);
  const [error, setError] = useState("");
  const [toast, setToast] = useState("");
  const [modal, setModal] = useState(null);
  const [days, setDays] = useState(7);
  const [revision, setRevision] = useState(0);
  const [loading, setLoading] = useState(false);
  const [updatedAt, setUpdatedAt] = useState(null);
  const [mobileOpen, setMobileOpen] = useState(false);
  const dispatch = isDispatcher(user);
  const reduceMotion = useReducedMotion();

  function signedIn(account) {
    setUser(account);
    setTab(
      isDispatcher(account)
        ? "overview"
        : account.role === "DRIVER"
          ? "tasks"
          : "bookings",
    );
    setError("");
    setData(initialData);
  }
  useEffect(() => {
    let active = true;
    (async () => {
      try {
        const account = await initializeAuth();
        if (active && account) signedIn(account);
      } catch (err) {
        if (active && err.status !== 401) setError(err.message);
      } finally {
        if (active) setBooting(false);
      }
    })();
    return () => {
      active = false;
    };
  }, []);
  useEffect(() => {
    if (!user) return;
    let active = true;
    let pending = false;
    async function load() {
      if (pending) return;
      pending = true;
      setLoading(true);
      try {
        const keys = [
          "locations",
          "vehicles",
          "trips",
          ...(dispatch ? ["drivers", "analytics", "events"] : []),
        ];
        const values = await Promise.allSettled(
          keys.map((key) =>
            request(
              `/${key}${key === "analytics" ? `?days=${tab === "reports" ? days : 7}` : ""}`,
            ),
          ),
        );
        if (active) {
          const authFailure = values.find(
            (item) => item.status === "rejected" && item.reason.status === 401,
          );
          if (authFailure) throw authFailure.reason;
          setData((previous) => ({
            ...previous,
            ...Object.fromEntries(
              keys.flatMap((key, index) =>
                values[index].status === "fulfilled"
                  ? [[key, values[index].value]]
                  : [],
              ),
            ),
          }));
          const failures = keys.filter(
            (key, index) => values[index].status === "rejected",
          );
          setError(
            failures.length
              ? `Some services are unavailable (${failures.join(", ")}). Previously loaded data may be out of date.`
              : "",
          );
          setUpdatedAt(new Date());
        }
      } catch (err) {
        if (active) {
          setError(err.message);
          if (err.status === 401) {
            setUser(null);
            setModal(null);
          }
        }
      } finally {
        pending = false;
        if (active) setLoading(false);
      }
    }
    load();
    const timer = setInterval(load, 8000);
    return () => {
      active = false;
      clearInterval(timer);
    };
  }, [user, dispatch, days, tab, revision]);
  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast(""), 4500);
    return () => clearTimeout(timer);
  }, [toast]);

  async function mutate(path, body, method = "POST", rethrow = false) {
    try {
      const result = await request(path, { method, body });
      setRevision((value) => value + 1);
      setToast(
        result?.status === "ASSIGNING"
          ? "Assignment requested. Waiting for fleet confirmation..."
          : "Your changes have been saved.",
      );
      return result;
    } catch (err) {
      if (err.status === 401) {
        setUser(null);
        setModal(null);
      }
      if (rethrow) throw err;
      setError(err.message);
    }
  }
  async function save(path, body) {
    await mutate(path, body, "POST", true);
    setModal(null);
  }
  const changeTab = (value) => {
    setTab(value);
    setMobileOpen(false);
  };
  if (booting)
    return (
      <div className="boot-screen">
        <LoaderCircle className="spin" size={30} />
        <span>Connecting to PlantFlow…</span>
      </div>
    );
  if (!user) return <Login onLogin={signedIn} initialError={error} />;
  const tabs = dispatch
    ? [
        "overview",
        "bookings",
        "shuttles",
        "fleet",
        "tracking",
        "reports",
        "activity",
      ]
    : user.role === "DRIVER"
      ? ["tasks", "tracking"]
      : ["bookings", "shuttles", "tracking"];
  const tripProps = {
    trips: data.trips,
    user,
    onAction: mutate,
    onBook: () => setModal({ type: "booking" }),
    onAssign: (trip) => setModal({ type: "assign", trip }),
    onShuttle: () => setModal({ type: "shuttle" }),
  };
  return (
    <div className="app-shell">
      {mobileOpen && (
        <button
          className="sidebar-backdrop"
          aria-label="Close navigation"
          onClick={() => setMobileOpen(false)}
        />
      )}
      <aside className={`sidebar ${mobileOpen ? "open" : ""}`}>
        <a
          className="brand"
          href="/"
          onClick={(event) => {
            event.preventDefault();
            changeTab(tabs[0]);
          }}
        >
          <span className="brand-symbol">
            <RouteIcon size={23} />
          </span>
          plantflow<span className="brand-period">.</span>
        </a>
        <div className="workspace-switch">
          <span className="workspace-icon">
            <Factory size={20} />
          </span>
          <div>
            <strong>Plant workspace</strong>
            <small>Internal transport</small>
          </div>
          <ChevronDown size={15} />
        </div>
        <div className="nav-label">WORKSPACE</div>
        <nav aria-label="Main navigation">
          {tabs.map((key) => {
            const [name, Icon] = tabInfo[key];
            return (
              <button
                key={key}
                className={`nav-item ${tab === key ? "active" : ""}`}
                aria-current={tab === key ? "page" : undefined}
                onClick={() => changeTab(key)}
              >
                <Icon size={19} />
                {name}
                {key === "bookings" &&
                  dispatch &&
                  data.trips.some((trip) => trip.status === "REQUESTED") && (
                    <span className="nav-count">
                      {
                        data.trips.filter((trip) => trip.status === "REQUESTED")
                          .length
                      }
                    </span>
                  )}
              </button>
            );
          })}
        </nav>
        <div className="sidebar-bottom">
          <div className="sidebar-note">
            <span className="live-dot" />
            <strong>One plant. Connected.</strong>
            <p>Making every journey count.</p>
            <div className="tiny-road">
              <CarFront size={22} />
              <span />
              <Factory size={22} />
            </div>
          </div>
          <button
            className="profile"
            onClick={async () => {
              try {
                await stopTracking.current?.();
                await logout();
                setUser(null);
                setModal(null);
                setData(initialData);
                setError("");
              } catch (err) {
                setError(err.message);
              }
            }}
            aria-label="Sign out"
          >
            <span className="avatar">
              {user.name?.slice(0, 2).toUpperCase() ||
                user.username.slice(0, 2).toUpperCase()}
            </span>
            <span>
              <strong>{user.name || user.username}</strong>
              <small>{label(user.role)}</small>
            </span>
            <LogOut size={18} />
          </button>
        </div>
      </aside>
      <div className="main-shell">
        <header className="topbar">
          <div className="breadcrumb">
            <button
              className="icon-button mobile-menu"
              aria-label="Open navigation"
              onClick={() => setMobileOpen(true)}
            >
              <Menu size={21} />
            </button>
            <span>Workspace</span>
            <span className="breadcrumb-slash">/</span>
            <strong>{tabInfo[tab][0]}</strong>
          </div>
          <div className="topbar-right">
            <span className="live-status">
              <span className={`live-dot ${error ? "offline" : ""}`} />
              {error ? "Connection issue" : "Updates every 8s"}
            </span>
            <span className="topbar-date">
              {new Date().toLocaleDateString("en-IN", {
                day: "numeric",
                month: "short",
                year: "numeric",
              })}
            </span>
            <button
              className="icon-button"
              aria-label="Refresh data"
              disabled={loading}
              onClick={() => setRevision((value) => value + 1)}
            >
              <RefreshCw size={17} className={loading ? "spin" : ""} />
            </button>
          </div>
        </header>
        <main>
          {error && (
            <ErrorBox
              message={error}
              onRetry={() => setRevision((value) => value + 1)}
            />
          )}
          <div className="loading-indicator" role="status">
            {loading && !updatedAt ? "Loading your workspace…" : ""}
          </div>
          <RemoteBoundary key={tab}>
            <motion.div
              key={tab}
              initial={{ opacity: 0, y: reduceMotion ? 0 : 9 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: reduceMotion ? 0 : 0.23 }}
            >
              {tab === "overview" && (
                <Analytics
                  view="overview"
                  data={data}
                  setTab={changeTab}
                  openBooking={tripProps.onBook}
                />
              )}
              {["bookings", "shuttles", "tasks"].includes(tab) && (
                <TripBoard
                  key={tab}
                  {...tripProps}
                  shuttles={tab === "shuttles"}
                  driver={tab === "tasks"}
                />
              )}{" "}
              {tab === "fleet" && (
                <Fleet
                  vehicles={data.vehicles}
                  drivers={data.drivers}
                  onAddDriver={() => setModal({ type: "driver" })}
                  onAdd={() => setModal({ type: "vehicle" })}
                  onAction={mutate}
                />
              )}{" "}
              {tab === "reports" && (
                <Analytics
                  view="reports"
                  analytics={data.analytics}
                  days={days}
                  setDays={setDays}
                  onExport={async () => {
                    try {
                      await downloadReport(days);
                    } catch (err) {
                      setError(err.message);
                    }
                  }}
                />
              )}{" "}
              {tab === "activity" && <Analytics view="activity" data={data} />}{" "}
              {tab === "tracking" && (
                <Tracking
                  api={request}
                  user={user}
                  trips={data.trips}
                  registerStop={(callback) => {
                    stopTracking.current = callback;
                  }}
                />
              )}
            </motion.div>
          </RemoteBoundary>
          <footer className="app-footer">
            <span>PlantFlow · Internal plant mobility</span>
            <span>
              {updatedAt
                ? `Last synced ${updatedAt.toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit", second: "2-digit" })}`
                : "Waiting for first sync"}
            </span>
          </footer>
        </main>
      </div>
      {toast && (
        <div className="toast" role="status">
          <ShieldCheck size={18} />
          {toast}
          <button
            aria-label="Dismiss notification"
            onClick={() => setToast("")}
          >
            <X size={16} />
          </button>
        </div>
      )}
      {modal && (
        <Modal
          title={
            {
              booking: "Book an on-demand ride",
              assign: `Assign ride #${modal.trip?.id}`,
              shuttle: "Schedule a shift shuttle",
              vehicle: "Add a vehicle",
              driver: "Register a driver",
            }[modal.type]
          }
          onClose={() => setModal(null)}
        >
          {modal.type === "booking" && (
            <Form
              submit="Request ride"
              onSubmit={(values) => save("/trips", ridePayload(values))}
            >
              <p className="form-help">
                Your dispatcher will assign an available vehicle and driver.
              </p>
              <LocationFields locations={data.locations} />
              <label>
                Passengers
                <input
                  name="seats"
                  type="number"
                  min="1"
                  max="40"
                  defaultValue="1"
                  required
                />
              </label>
              <label>
                Purpose of travel
                <textarea
                  name="purpose"
                  placeholder="e.g. Inspection at the turbine building"
                  maxLength="200"
                  required
                  rows="3"
                />
              </label>
            </Form>
          )}
          {modal.type === "assign" && (
            <Form
              submit="Assign vehicle & driver"
              onSubmit={(values) =>
                save(`/trips/${modal.trip.id}/assign`, {
                  vehicleId: Number(values.vehicleId),
                  driverId: values.driverId,
                })
              }
            >
              <p className="form-help">
                {modal.trip.origin} → {modal.trip.destination} ·{" "}
                {modal.trip.passengers} passengers. Only available vehicles with
                enough seats are listed.
              </p>
              <AssignmentFields
                vehicles={data.vehicles}
                drivers={data.drivers}
                minimum={modal.trip.passengers}
              />
            </Form>
          )}
          {modal.type === "shuttle" && (
            <Form
              submit="Schedule shuttle"
              onSubmit={(values) => {
                if (values.originId === values.destinationId)
                  throw new Error("Choose two different locations.");
                const date = new Date(values.scheduledAt);
                if (!(date > new Date()))
                  throw new Error("Choose a departure time in the future.");
                return save("/shuttles", {
                  originId: Number(values.originId),
                  destinationId: Number(values.destinationId),
                  vehicleId: Number(values.vehicleId),
                  driverId: values.driverId,
                  scheduledAt: date.toISOString(),
                });
              }}
            >
              <p className="form-help">
                Schedule one departure. Fleet confirmation reserves the vehicle
                and driver until the trip ends.
              </p>
              <LocationFields locations={data.locations} />
              <label>
                Departure time (your local timezone)
                <input name="scheduledAt" type="datetime-local" required />
              </label>
              <AssignmentFields
                vehicles={data.vehicles}
                drivers={data.drivers}
              />
            </Form>
          )}
          {modal.type === "driver" && (
            <Form
              submit="Register driver"
              onSubmit={(values) =>
                save("/drivers", {
                  username: values.username.trim(),
                  name: values.name.trim(),
                })
              }
            >
              <p className="form-help">
                Create the user in your identity provider and grant the DRIVER
                role first. This registers their dispatch profile.
              </p>
              <label>
                Identity username
                <input name="username" required maxLength="80" />
              </label>
              <label>
                Full name
                <input name="name" required maxLength="120" />
              </label>
            </Form>
          )}
          {modal.type === "vehicle" && (
            <Form
              submit="Add to fleet"
              onSubmit={(values) =>
                save("/vehicles", {
                  code: values.code.trim(),
                  type: values.type,
                  capacity: Number(values.capacity),
                })
              }
            >
              <label>
                Vehicle code
                <input
                  name="code"
                  placeholder="e.g. EV-004"
                  required
                  maxLength="30"
                />
              </label>
              <div className="form-grid">
                <label>
                  Vehicle type
                  <select name="type">
                    <option value="BUGGY">Electric buggy</option>
                    <option value="VAN">Van</option>
                    <option value="BUS">Bus</option>
                  </select>
                </label>
                <label>
                  Passenger capacity
                  <input
                    name="capacity"
                    type="number"
                    min="1"
                    max="60"
                    defaultValue="6"
                    required
                  />
                </label>
              </div>
            </Form>
          )}
        </Modal>
      )}
    </div>
  );
}
