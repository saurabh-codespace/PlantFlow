import { useState } from "react";
import {
  Plus,
  Search,
  Users,
  Clock3,
  ArrowUpRight,
  CarFront,
  BusFront,
} from "lucide-react";
import { Badge, Empty, Route, KindIcon, Form } from "../../../src/components";
import {
  activeTrip,
  canCancel,
  canReserve,
  dateTime,
  isDispatcher,
  label,
  nextStatus,
  statusAction,
} from "../../../src/utils";

import "./styles.css";
function Fleet({ vehicles, drivers = [], onAdd, onAddDriver, onAction }) {
  const [busy, setBusy] = useState(null);
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">BUILT TO KEEP MOVING</div>
          <h1>
            Fleet management<span className="heading-dot">.</span>
          </h1>
          <p>Keep every vehicle ready for the next journey.</p>
        </div>
        <div className="fleet-heading-actions">
          <button className="button ghost" onClick={onAddDriver}>
            <Plus size={18} />
            Register driver
          </button>
          <button className="button primary" onClick={onAdd}>
            <Plus size={18} />
            Add vehicle
          </button>
        </div>
      </div>
      <div className="fleet-cards">
        {vehicles.map((vehicle) => (
          <article className="panel vehicle-card" key={vehicle.id}>
            <div className="vehicle-card-top">
              <div className="large-vehicle-icon">
                {vehicle.type === "BUS" ? (
                  <BusFront size={32} />
                ) : (
                  <CarFront size={32} />
                )}
              </div>
              <Badge value={vehicle.busy ? "IN_SERVICE" : vehicle.status} />
            </div>
            <h2>{vehicle.code}</h2>
            <p>
              {label(vehicle.type)} · {vehicle.capacity} seats
            </p>
            <div className="vehicle-card-bottom">
              <span>
                {vehicle.busy
                  ? "Assigned to a journey"
                  : vehicle.status === "AVAILABLE"
                    ? "Ready to dispatch"
                    : "Unavailable for dispatch"}
              </span>
              <button
                className="text-button"
                disabled={vehicle.busy || busy === vehicle.id}
                onClick={async () => {
                  setBusy(vehicle.id);
                  try {
                    await onAction(
                      `/vehicles/${vehicle.id}/status`,
                      {
                        status:
                          vehicle.status === "AVAILABLE"
                            ? "MAINTENANCE"
                            : "AVAILABLE",
                      },
                      "PATCH",
                    );
                  } finally {
                    setBusy(null);
                  }
                }}
              >
                {busy === vehicle.id
                  ? "Updating…"
                  : vehicle.status === "AVAILABLE"
                    ? "Maintenance"
                    : "Make available"}
              </button>
            </div>
          </article>
        ))}
      </div>
      {!vehicles.length && (
        <section className="panel">
          <Empty
            title="Your fleet starts here"
            text="Add a buggy, van or bus to start dispatching trips."
          />
        </section>
      )}
      <section className="panel">
        <div className="panel-heading">
          <div>
            <h2>Driver roster</h2>
            <p>Provisioned identities available for dispatch</p>
          </div>
          <span className="small-chip">{drivers.length} drivers</span>
        </div>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Driver</th>
                <th>Username</th>
                <th>Availability</th>
              </tr>
            </thead>
            <tbody>
              {drivers.map((driver) => (
                <tr key={driver.username}>
                  <td>{driver.name}</td>
                  <td>{driver.username}</td>
                  <td>
                    <Badge value={driver.busy ? "IN_SERVICE" : "AVAILABLE"} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </>
  );
}

export default function FleetModule(props) {
  return (
    <div className="pf-fleet">
      <Fleet {...props} />
    </div>
  );
}
