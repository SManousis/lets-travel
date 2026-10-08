import type { Page } from "../lib/api";
import type { ReactNode } from "react";
import type { Resource } from "../lib/useResource";

export function PageControls<T>({ result, onPage }: { result: Page<T> | null; onPage: (page: number) => void }) {
  if (!result) return null;
  const hasNext = (result.page + 1) * result.size < result.total;
  return <div className="page-controls" aria-label="List pages">
    <button type="button" disabled={result.page === 0} onClick={() => onPage(result.page - 1)}>Previous</button>
    <span>Page {result.page + 1} · {result.total} total</span>
    <button type="button" disabled={!hasNext} onClick={() => onPage(result.page + 1)}>Next</button>
  </div>;
}

export function Message({ kind = "info", children }: { kind?: "info" | "error" | "success"; children: ReactNode }) {
  return <p className={`message ${kind}`} role={kind === "error" ? "alert" : "status"}>{children}</p>;
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="empty-state">{children}</p>;
}

/** Loading, failure (with retry) and success for one loaded resource. */
export function Loaded<T>({ resource, retry, label, children }: {
  resource: Resource<T>; retry: () => void; label: string; children: (data: T) => ReactNode;
}) {
  if (resource.state === "loading") return <p role="status" className="loading">Loading {label}…</p>;
  if (resource.state === "error") return <div className="message error" role="alert">
    <p>{resource.degraded ? `${label.charAt(0).toUpperCase()}${label.slice(1)} are temporarily unavailable. The rest of the app still works.`
      : resource.message}</p>
    <button type="button" onClick={retry}>Try again</button>
  </div>;
  return <>{children(resource.data)}</>;
}

export function Stat({ label, value, hint }: { label: string; value: ReactNode; hint?: string }) {
  return <div className="stat"><dt>{label}</dt><dd>{value}</dd>{hint && <small>{hint}</small>}</div>;
}
