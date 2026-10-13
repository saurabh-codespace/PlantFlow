import { useEffect, useRef, useState } from "react";
import {
  MapContainer,
  TileLayer,
  CircleMarker,
  Popup,
  Circle,
  useMap,
} from "react-leaflet";
import {
  LocateFixed,
  Navigation,
  Radio,
  ShieldCheck,
  Square,
  Users,
} from "lucide-react";
import { Empty, ErrorBox } from "../../../src/components";
import { dateTime } from "../../../src/utils";
import {
  consumeFrames,
  isStale,
  trackableTrip,
  validPosition,
} from "./tracking";
import "leaflet/dist/leaflet.css";
import "./styles.css";

const positionKey = (position) =>
  `${position.tripId}:${position.username}:${position.subject}`;
function FitPositions({ positions }) {
  const map = useMap();
  const fitted = useRef(false);
  useEffect(() => {
    if (!fitted.current && positions.length) {
      map.fitBounds(
        positions.map((p) => [p.latitude, p.longitude]),
        { maxZoom: 17, padding: [50, 50] },
      );
      fitted.current = true;
    }
  }, [positions, map]);
  return null;
}

export default function Tracking({ api, trips, user, registerStop }) {
  const [positions, setPositions] = useState([]);
  const [error, setError] = useState("");
  const [geoError, setGeoError] = useState("");
  const [streaming, setStreaming] = useState(false);
  const [selected, setSelected] = useState("");
  const [sharing, setSharing] = useState(null);
  const [now, setNow] = useState(Date.now());
  const [lastSent, setLastSent] = useState(null);
  const watcher = useRef(null);
  const sending = useRef(null);
  const sharingRef = useRef(null);
  const generation = useRef(0);
  const availableTrips = trips.filter((trip) => trackableTrip(trip, user));
  const configuredCenter = (import.meta.env.VITE_MAP_CENTER || "")
    .split(",")
    .map(Number);
  const center = positions.length
    ? [positions[0].latitude, positions[0].longitude]
    : configuredCenter.length === 2 && configuredCenter.every(Number.isFinite)
      ? configuredCenter
      : null;

  useEffect(() => {
    const controller = new AbortController();
    let disposed = false;
    let polling = false;
    let retry;
    async function poll() {
      if (polling) return;
      polling = true;
      try {
        const result = await api("/tracking/positions", {
          signal: controller.signal,
        });
        if (!disposed) {
          setPositions(result.filter(validPosition));
          setError("");
          setNow(Date.now());
        }
      } catch (err) {
        if (!disposed) setError(err.message);
      } finally {
        polling = false;
      }
    }
    async function stream() {
      try {
        const response = await api("/tracking/stream", {
          raw: true,
          signal: controller.signal,
        });
        if (!response.body) throw new Error("Streaming is unavailable");
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";
        if (!disposed) setStreaming(true);
        while (!disposed) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          if (buffer.length > 1_000_000)
            throw new Error("Invalid stream frame");
          buffer = consumeFrames(buffer, (position) => {
            if (!disposed)
              setPositions((existing) => [
                ...existing.filter(
                  (p) => positionKey(p) !== positionKey(position),
                ),
                position,
              ]);
          });
        }
      } catch {
        /* Polling continues when SSE is unavailable or a token expires. */
      }
      if (!disposed) {
        setStreaming(false);
        retry = setTimeout(stream, 5000);
      }
    }
    poll();
    stream();
    const timer = setInterval(poll, 5000);
    const clock = setInterval(() => setNow(Date.now()), 5000);
    return () => {
      disposed = true;
      controller.abort();
      clearInterval(timer);
      clearInterval(clock);
      clearTimeout(retry);
    };
  }, [api]);

  async function stopSharing(updateUi = true) {
    const tripId = sharingRef.current;
    sharingRef.current = null;
    generation.current += 1;
    if (watcher.current !== null) {
      navigator.geolocation.clearWatch(watcher.current);
      watcher.current = null;
    }
    if (updateUi) setSharing(null);
    if (tripId) {
      await Promise.resolve(sending.current).catch(() => {});
      try {
        await api(`/tracking/trips/${tripId}/positions/me`, {
          method: "DELETE",
        });
      } catch (err) {
        if (updateUi)
          setGeoError(
            `Sharing stopped on this device. Last position could not be removed: ${err.message}`,
          );
      }
    }
  }
  useEffect(() => {
    registerStop?.(() => stopSharing());
    return () => {
      registerStop?.(null);
      stopSharing(false);
    };
  }, []);
  useEffect(() => {
    if (sharing && !availableTrips.some((trip) => trip.id === sharing))
      stopSharing();
  }, [trips, sharing]);

  function startSharing() {
    setGeoError("");
    if (!navigator.geolocation || !window.isSecureContext) {
      setGeoError(
        "Location sharing requires a secure HTTPS connection (or localhost) and a browser with geolocation.",
      );
      return;
    }
    const tripId = Number(selected);
    if (!availableTrips.some((trip) => trip.id === tripId)) {
      setGeoError("Choose an active trip you are participating in.");
      return;
    }
    sharingRef.current = tripId;
    setSharing(tripId);
    const currentGeneration = ++generation.current;
    let lastUpload = 0;
    watcher.current = navigator.geolocation.watchPosition(
      (position) => {
        if (
          currentGeneration !== generation.current ||
          sending.current ||
          Date.now() - lastUpload < 5000
        )
          return;
        lastUpload = Date.now();
        const body = {
          latitude: position.coords.latitude,
          longitude: position.coords.longitude,
          accuracy: position.coords.accuracy,
          recordedAt: new Date(position.timestamp).toISOString(),
        };
        sending.current = api(`/tracking/trips/${tripId}/positions`, {
          method: "POST",
          body,
        })
          .then(() => {
            if (currentGeneration === generation.current) {
              setLastSent(new Date());
              setGeoError("");
            }
          })
          .catch((err) => {
            if (currentGeneration === generation.current)
              setGeoError(err.message);
          })
          .finally(() => {
            sending.current = null;
          });
      },
      (err) => {
        setGeoError(
          err.code === 1
            ? "Location permission was denied. Allow location access in your browser to share your position."
            : "Your device could not determine its location. Try outdoors or check device location settings.",
        );
        stopSharing();
      },
      { enableHighAccuracy: true, maximumAge: 0, timeout: 20000 },
    );
  }

  return (
    <div className="pf-tracking">
      <div className="page-heading">
        <div>
          <div className="eyebrow">THE RIGHT PEOPLE. THE RIGHT PLACE.</div>
          <h1>
            Live movement<span className="heading-dot">.</span>
          </h1>
          <p>
            Real device positions for authorized trips, with privacy in your
            hands.
          </p>
        </div>
        <span className="tracking-connection">
          <Radio size={15} />
          {streaming ? "Live stream connected" : "Syncing every 5 seconds"}
        </span>
      </div>
      {error && <ErrorBox message={error} />}
      <section className="panel tracking-consent">
        <div className="tracking-consent-copy">
          <span className="tracking-shield">
            <ShieldCheck size={23} />
          </span>
          <div>
            <h2>Share your trip location</h2>
            <p>
              Your position is visible to dispatch and authorized participants
              during your active trip. Sharing stops when you leave this page,
              stop it, or your trip ends.
            </p>
            <small>
              Map tiles load from your configured provider, which can see your
              IP and map area. Device location is never simulated.
            </small>
          </div>
        </div>
        <div className="tracking-controls">
          <label>
            Active trip
            <select
              value={selected}
              disabled={Boolean(sharing)}
              onChange={(event) => setSelected(event.target.value)}
            >
              <option value="">Select your trip</option>
              {availableTrips.map((trip) => (
                <option key={trip.id} value={trip.id}>
                  #{trip.id} · {trip.origin} → {trip.destination}
                </option>
              ))}
            </select>
          </label>
          {sharing ? (
            <button
              className="button stop-sharing"
              onClick={() => stopSharing()}
            >
              <Square size={14} />
              Stop sharing
            </button>
          ) : (
            <button
              className="button primary"
              disabled={!selected}
              onClick={startSharing}
            >
              <LocateFixed size={17} />
              Share my location
            </button>
          )}
        </div>
        {sharing && (
          <p className="sharing-state" role="status">
            <span className="live-dot" />
            Sharing is on{" "}
            {lastSent
              ? `· last sent ${lastSent.toLocaleTimeString()}`
              : "· waiting for your device"}
          </p>
        )}
        {geoError && <ErrorBox message={geoError} />}
      </section>
      <section className="panel live-map-panel">
        <div className="panel-heading">
          <div>
            <h2>Riders & vehicles</h2>
            <p>
              {positions.length} authorized positions · points older than 30
              seconds are marked stale
            </p>
          </div>
          <span className="small-chip">
            <Navigation size={12} />
            Live GPS
          </span>
        </div>
        {center ? (
          <MapContainer
            center={center}
            zoom={16}
            className="tracking-map"
            scrollWheelZoom={false}
          >
            <TileLayer
              attribution={
                import.meta.env.VITE_MAP_ATTRIBUTION ||
                '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
              }
              url={
                import.meta.env.VITE_MAP_TILE_URL ||
                "https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
              }
            />
            <FitPositions positions={positions} />
            {positions.map((position) => {
              const stale = isStale(position, now);
              const color = stale
                ? "#92988e"
                : ["DRIVER", "VEHICLE"].includes(position.subject)
                  ? "#465c2d"
                  : "#788cce";
              return (
                <CircleMarker
                  key={positionKey(position)}
                  center={[position.latitude, position.longitude]}
                  radius={
                    ["DRIVER", "VEHICLE"].includes(position.subject) ? 10 : 7
                  }
                  pathOptions={{
                    color: "#fff",
                    weight: 3,
                    fillColor: color,
                    fillOpacity: 0.95,
                  }}
                >
                  <Popup>
                    <strong>
                      {position.vehicleLabel || position.username}
                    </strong>
                    <br />
                    {position.subject === "VEHICLE"
                      ? "Vehicle"
                      : position.subject === "DRIVER"
                        ? "Driver"
                        : "Rider"}{" "}
                    · Trip #{position.tripId}
                    <br />
                    {dateTime(position.recordedAt)}{" "}
                    {stale ? "· Stale" : "· Recent"}
                    <br />
                    Accuracy ±{Math.round(position.accuracy || 0)} m
                  </Popup>
                </CircleMarker>
              );
            })}
          </MapContainer>
        ) : (
          <div className="tracking-empty">
            <LocateFixed size={38} />
            <h2>Waiting for the first live location</h2>
            <p>
              When a rider or driver opts in on an active trip, their real
              position appears here.
            </p>
            <span>No simulated GPS points</span>
          </div>
        )}
      </section>
      <div className="tracking-position-list">
        {positions.map((position) => (
          <article className="panel position-card" key={positionKey(position)}>
            <div
              className={`position-person ${position.subject.toLowerCase()}`}
            >
              <Users size={19} />
            </div>
            <div>
              <strong>{position.vehicleLabel || position.username}</strong>
              <p>
                {position.subject === "VEHICLE"
                  ? "Vehicle"
                  : position.subject === "DRIVER"
                    ? "Driver"
                    : "Rider"}{" "}
                · Trip #{position.tripId}
              </p>
            </div>
            <span
              className={
                isStale(position, now) ? "position-stale" : "position-live"
              }
            >
              {isStale(position, now) ? "Stale" : "Recent"}
            </span>
          </article>
        ))}
      </div>
    </div>
  );
}
