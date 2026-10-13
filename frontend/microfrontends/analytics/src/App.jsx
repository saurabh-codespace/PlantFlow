import "./styles.css";
import {
  ResponsiveContainer,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
} from "recharts";
import {
  ArrowUpRight,
  CarFront,
  CheckCheck,
  Clock3,
  Users,
  ArrowRight,
  Download,
  Route as RouteIcon,
  CircleDot,
} from "lucide-react";
import { ActivityList, Badge, Empty, Route } from "../../../src/components";
import { activeTrip, number, dateTime } from "../../../src/utils";

function Metric({ title, value, detail, icon: Icon, featured }) {
  return (
    <article className={`metric ${featured ? "featured" : ""}`}>
      <div className="metric-top">
        <span>{title}</span>
        <Icon size={20} />
      </div>
      <strong className="metric-value">{value}</strong>
      <p>{detail}</p>
    </article>
  );
}
export function TrendChart({ daily = [] }) {
  return daily.length ? (
    <div
      className="recharts-trend"
      role="img"
      aria-label={`Daily trips: ${daily.map((d) => `${d.date}: ${d.trips} trips, ${d.completed} completed`).join("; ")}`}
    >
      <ResponsiveContainer width="100%" height={230}>
        <BarChart
          data={daily}
          margin={{ top: 12, right: 24, bottom: 4, left: 0 }}
          accessibilityLayer
        >
          <CartesianGrid vertical={false} stroke="#edf1e6" />
          <XAxis
            dataKey="date"
            tickFormatter={(date) =>
              new Date(`${date}T00:00:00Z`).toLocaleDateString("en-IN", {
                day: "numeric",
                month: "short",
                timeZone: "UTC",
              })
            }
            tick={{ fontSize: 12, fill: "#626f59" }}
            axisLine={false}
            tickLine={false}
          />
          <YAxis
            allowDecimals={false}
            tick={{ fontSize: 12, fill: "#626f59" }}
            axisLine={false}
            tickLine={false}
          />
          <Tooltip
            contentStyle={{
              borderRadius: 10,
              border: "1px solid #e3ead9",
              fontSize: 11,
            }}
          />
          <Bar
            dataKey="trips"
            name="All trips"
            fill="#e4ecd6"
            radius={[4, 4, 0, 0]}
            maxBarSize={30}
            isAnimationActive={false}
          />
          <Bar
            dataKey="completed"
            name="Completed"
            fill="#b5df72"
            radius={[4, 4, 0, 0]}
            maxBarSize={30}
            isAnimationActive={false}
          />
        </BarChart>
      </ResponsiveContainer>
    </div>
  ) : (
    <Empty
      title="Reports are warming up"
      text="Trip events will populate this chart."
    />
  );
}
function SiteMap({ locations, trips }) {
  return (
    <div className="site-map">
      <div className="map-grid" />
      <svg
        className="map-roads"
        viewBox="0 0 100 100"
        preserveAspectRatio="none"
        aria-hidden="true"
      >
        <path d="M0 50 H100 M24 0 V100 M75 0 V100 M0 22 H100 M0 80 H100" />
      </svg>
      {locations.map((location) => (
        <div
          className="map-location"
          key={location.id}
          style={{
            left: `${Math.max(8, Math.min(88, location.x))}%`,
            top: `${Math.max(12, Math.min(74, location.y))}%`,
          }}
        >
          <span className="map-pin">
            <CircleDot size={15} />
          </span>
          <span>{location.name}</span>
        </div>
      ))}
      <div className="map-caption">
        <span className="live-dot" />
        {trips.filter(activeTrip).length} active movements
        <span className="map-disclaimer">Site schematic · not GPS</span>
      </div>
    </div>
  );
}
function Dashboard({ data, setTab, openBooking }) {
  const { analytics: stats, trips, locations, events, vehicles } = data;
  if (!stats)
    return (
      <Empty
        title="Loading operations"
        text="Connecting to the analytics service…"
      />
    );
  const active = trips.filter(activeTrip);
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">YOUR PLANT, IN MOTION</div>
          <h1>
            Operations overview<span className="heading-dot">.</span>
          </h1>
          <p>A clear view of every journey. A smoother day for everyone.</p>
        </div>
        <button className="button primary" onClick={openBooking}>
          Book a ride <ArrowUpRight size={18} />
        </button>
      </div>
      <div className="metrics">
        <Metric
          title="Total movements"
          value={number(stats.totalTrips)}
          detail="Trips created in the last 7 UTC days"
          icon={RouteIcon}
          featured
        />
        <Metric
          title="Active trips"
          value={number(stats.activeTrips)}
          detail="Open trips in the reporting window"
          icon={CarFront}
        />
        <Metric
          title="Passengers moved"
          value={number(stats.passengersMoved)}
          detail="Across completed journeys"
          icon={Users}
        />
        <Metric
          title="Average pickup wait"
          value={`${number(stats.averageWaitMinutes)} min`}
          detail="On-demand · booking to arrival"
          icon={Clock3}
        />
      </div>
      <div className="dashboard-grid">
        <section className="panel map-panel">
          <div className="panel-heading">
            <div>
              <h2>Plant movement</h2>
              <p>Connected locations across your site</p>
            </div>
            <span className="small-chip">{locations.length} locations</span>
          </div>
          <SiteMap locations={locations} trips={trips} />
          <div className="map-footer">
            <span>
              <i className="legend-dot lime" />
              {vehicles.filter((v) => v.busy).length} vehicles in service
            </span>
            <span>
              <i className="legend-dot" />
              {
                vehicles.filter((v) => !v.busy && v.status === "AVAILABLE")
                  .length
              }{" "}
              ready to dispatch
            </span>
          </div>
        </section>
        <section className="panel">
          <div className="panel-heading">
            <div>
              <h2>Fleet readiness</h2>
              <p>Your vehicles, at a glance</p>
            </div>
            <CarFront size={20} />
          </div>
          <div className="fleet-summary">
            <div
              className="fleet-ring"
              style={{
                "--busy": `${vehicles.length ? (vehicles.filter((v) => v.busy).length / vehicles.length) * 100 : 0}%`,
              }}
            >
              <div>
                <strong>{vehicles.length}</strong>
                <span>total vehicles</span>
              </div>
            </div>
            <div className="fleet-legend">
              {[
                ["In service", vehicles.filter((v) => v.busy).length, "lime"],
                [
                  "Available",
                  vehicles.filter((v) => !v.busy && v.status === "AVAILABLE")
                    .length,
                  "dark",
                ],
                [
                  "Maintenance",
                  vehicles.filter((v) => v.status === "MAINTENANCE").length,
                  "gray",
                ],
              ].map(([name, count, color]) => (
                <div key={name}>
                  <span>
                    <i className={`legend-dot ${color}`} />
                    {name}
                  </span>
                  <strong>{count}</strong>
                </div>
              ))}
            </div>
          </div>
          <button className="wide-link" onClick={() => setTab("fleet")}>
            Manage fleet <ArrowRight size={16} />
          </button>
        </section>
      </div>
      <div className="dashboard-grid">
        <section className="panel">
          <div className="panel-heading">
            <div>
              <h2>Movement trends</h2>
              <p>Last 7 days · UTC</p>
            </div>
            <span className="chart-key">
              <i className="legend-dot lime" />
              Completed <i className="legend-dot gray" />
              All trips
            </span>
          </div>
          <TrendChart daily={stats.daily} />
        </section>
        <section className="panel">
          <div className="panel-heading">
            <div>
              <h2>Recent activity</h2>
              <p>Updates from the operations floor</p>
            </div>
            <button className="text-button" onClick={() => setTab("activity")}>
              View all
            </button>
          </div>
          <ActivityList events={events} compact />
        </section>
      </div>
      <section className="panel">
        <div className="panel-heading">
          <div>
            <h2>
              Live dispatch board <span className="count">{active.length}</span>
            </h2>
            <p>Active requests and journeys</p>
          </div>
          <button className="text-button" onClick={() => setTab("bookings")}>
            Open dispatch <ArrowRight size={15} />
          </button>
        </div>
        {active.length ? (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Journey</th>
                  <th>Mode</th>
                  <th>Vehicle / driver</th>
                  <th>Passengers</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {active.slice(0, 5).map((trip) => (
                  <tr key={trip.id}>
                    <td>
                      <Route trip={trip} />
                      <small>
                        #{trip.id} · {dateTime(trip.createdAt)}
                      </small>
                    </td>
                    <td>
                      {trip.kind === "SHUTTLE" ? "Shift shuttle" : "On-demand"}
                    </td>
                    <td>
                      {trip.vehicleLabel || "Awaiting assignment"}
                      <small>{trip.driverName || "—"}</small>
                    </td>
                    <td>{trip.passengers}</td>
                    <td>
                      <Badge value={trip.status} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <Empty
            title="Everything is moving smoothly"
            text="New ride requests and scheduled shuttles appear here."
          />
        )}
      </section>
    </>
  );
}

function Reports({ analytics, days, setDays, onExport }) {
  const stats = analytics;
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">INSIGHTS THAT MOVE YOU</div>
          <h1>
            Transport analytics<span className="heading-dot">.</span>
          </h1>
          <p>Understand demand, service quality and passenger movement.</p>
        </div>
        <div className="heading-actions">
          <label className="sr-only" htmlFor="report-days">
            Reporting window
          </label>
          <select
            id="report-days"
            value={days}
            onChange={(event) => setDays(Number(event.target.value))}
          >
            <option value="7">Last 7 days</option>
            <option value="30">Last 30 days</option>
            <option value="90">Last 90 days</option>
          </select>
          <button className="button primary" onClick={onExport}>
            <Download size={17} />
            Export CSV
          </button>
        </div>
      </div>
      {stats ? (
        <>
          <div className="metrics">
            <Metric
              title="Completion rate"
              value={`${number(stats.completionRate)}%`}
              detail={`${number(stats.completedTrips)} of ${number(stats.totalTrips)} trips`}
              icon={CheckCheck}
              featured
            />
            <Metric
              title="Passengers moved"
              value={number(stats.passengersMoved)}
              detail="Completed journeys"
              icon={Users}
            />
            <Metric
              title="Average pickup wait"
              value={`${number(stats.averageWaitMinutes)} min`}
              detail="On-demand bookings to arrival"
              icon={Clock3}
            />
            <Metric
              title="Cancelled trips"
              value={number(stats.cancelledTrips)}
              detail="Within reporting window"
              icon={CarFront}
            />
          </div>
          <section className="panel">
            <div className="panel-heading">
              <div>
                <h2>Daily trip volume</h2>
                <p>Trips created within the selected UTC calendar days</p>
              </div>
              <span className="chart-key">
                <i className="legend-dot lime" />
                Completed <i className="legend-dot gray" />
                All trips
              </span>
            </div>
            <TrendChart daily={stats.daily} />
          </section>
          <div className="dashboard-grid">
            <section className="panel">
              <div className="panel-heading">
                <h2>Most travelled routes</h2>
              </div>
              {stats.routes?.length ? (
                <div className="table-wrap">
                  <table>
                    <thead>
                      <tr>
                        <th>Route</th>
                        <th>Trips</th>
                        <th>Passengers</th>
                      </tr>
                    </thead>
                    <tbody>
                      {stats.routes.map((route) => (
                        <tr key={route.route}>
                          <td>{route.route}</td>
                          <td>{route.trips}</td>
                          <td>{route.passengers}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <Empty />
              )}
            </section>
            <section className="panel">
              <div className="panel-heading">
                <h2>Service mix</h2>
              </div>
              <div className="service-mix">
                {stats.byKind?.map((item) => (
                  <div key={item.kind}>
                    <div>
                      <strong>
                        {item.kind === "SHUTTLE"
                          ? "Shift shuttles"
                          : "On-demand rides"}
                      </strong>
                      <span>{number(item.trips)} trips</span>
                    </div>
                    <div className="progress-track">
                      <span
                        style={{
                          width: `${stats.totalTrips ? (item.trips / stats.totalTrips) * 100 : 0}%`,
                        }}
                      />
                    </div>
                  </div>
                ))}
              </div>
            </section>
          </div>
          <p className="footnote">
            Reports update asynchronously as service events arrive; this page
            polls every 8 seconds. Updated {dateTime(stats.lastUpdated)}. Fleet
            availability is live. Reporting window uses trip creation time.
          </p>
        </>
      ) : (
        <Empty title="Loading analytics" />
      )}
    </>
  );
}

export default function Analytics({ view, ...props }) {
  return (
    <div className="pf-analytics">
      {view === "overview" ? (
        <Dashboard {...props} />
      ) : view === "reports" ? (
        <Reports {...props} />
      ) : (
        <>
          <div className="page-heading">
            <div>
              <div className="eyebrow">EVERY UPDATE, IN ONE PLACE</div>
              <h1>
                Activity log<span className="heading-dot">.</span>
              </h1>
              <p>The latest 50 transport events across your workspace.</p>
            </div>
          </div>
          <section className="panel">
            <ActivityList events={props.data.events} />
          </section>
        </>
      )}
    </div>
  );
}
