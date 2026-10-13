import { Component, Suspense } from "react";
import { LoaderCircle } from "lucide-react";
export default class RemoteBoundary extends Component {
  state = { error: null };
  static getDerivedStateFromError(error) {
    return { error };
  }
  render() {
    if (this.state.error)
      return (
        <section className="panel remote-error" role="alert">
          <h2>This workspace module is unavailable</h2>
          <p>
            The rest of PlantFlow is still available. Check that the module
            service is running, then reload.
          </p>
          <button
            className="button primary"
            onClick={() => window.location.reload()}
          >
            Reload module
          </button>
        </section>
      );
    return (
      <Suspense
        fallback={
          <div className="remote-loading" role="status">
            <LoaderCircle className="spin" size={22} />
            Loading workspace module…
          </div>
        }
      >
        {this.props.children}
      </Suspense>
    );
  }
}
