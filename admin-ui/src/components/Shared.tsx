import type { Page } from "../lib/api";
import type { ReactNode } from "react";

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
