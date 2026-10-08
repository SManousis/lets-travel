import * as React from "react";
import { ApiError, errorMessage, isAbort } from "./api";

export type Resource<T> =
  | { state: "loading" }
  | { state: "ready"; data: T }
  /** `degraded` is true when a dependency is down (503 or unreachable), so the page can say "try again later". */
  | { state: "error"; message: string; degraded: boolean };

/**
 * Loads data for a view and reloads it on demand. A newer load (or leaving the view) aborts the older
 * request, so a slow answer can never overwrite a newer one.
 */
export function useResource<T>(load: (signal: AbortSignal) => Promise<T>): [Resource<T>, () => void] {
  const [resource, setResource] = React.useState<Resource<T>>({ state: "loading" });
  const [attempt, setAttempt] = React.useState(0);

  React.useEffect(() => {
    const controller = new AbortController();
    setResource(current => current.state === "ready" ? current : { state: "loading" });
    load(controller.signal)
      .then(data => { if (!controller.signal.aborted) setResource({ state: "ready", data }); })
      .catch(caught => {
        if (controller.signal.aborted || isAbort(caught)) return;
        const degraded = caught instanceof ApiError && (caught.status === 0 || caught.status >= 502);
        setResource({ state: "error", message: errorMessage(caught), degraded });
      });
    return () => controller.abort();
  }, [load, attempt]);

  return [resource, React.useCallback(() => setAttempt(value => value + 1), [])];
}
