import * as React from "react";
import { api, newKey, type ApiContext } from "../../lib/api";
import type { Report, ReportCategory, ReportTargetType } from "../../lib/types";
import { Message } from "../../components/Shared";
import { categoryLabels, reportErrorMessage, targetLabels } from "./format";

export type ReportTarget = { type: ReportTargetType; id: string; name: string };

/**
 * Report a travel manager, a traveler or a travel. The reporter is never shown to the target;
 * an administrator reviews the report in the moderation queue.
 */
export function ReportForm({ context, target, onClose, onFiled }: {
  context: ApiContext; target: ReportTarget; onClose?: () => void; onFiled?: (report: Report) => void;
}) {
  const [category, setCategory] = React.useState<ReportCategory>("OTHER");
  const [description, setDescription] = React.useState("");
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState("");
  const [filed, setFiled] = React.useState(false);
  // Keep the same key until the report is accepted, so a retried request cannot file it twice.
  const key = React.useRef<string | null>(null);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true); setError("");
    key.current ??= newKey();
    try {
      const report = await api<Report>(context, "/api/reports", {
        method: "POST", headers: { "Idempotency-Key": key.current },
        body: { targetType: target.type, targetId: target.id, category, description: description.trim() },
      });
      key.current = null;
      setFiled(true);
      onFiled?.(report);
    } catch (caught) { setError(reportErrorMessage(caught)); }
    finally { setBusy(false); }
  }

  const title = `Report ${target.name}`;
  if (filed) return <div className="report-form" role="region" aria-label={title}>
    <Message kind="success">Thank you. Your report was sent and an administrator will review it.</Message>
    {onClose && <div className="actions"><button type="button" onClick={onClose}>Close</button></div>}
  </div>;

  return <form className="report-form field-grid" onSubmit={submit} aria-label={title}>
    <p><strong>{title}</strong> <small>({targetLabels[target.type]})</small><br />
      <small>Your name is not shown to the person you report.</small></p>
    <label>Reason<select value={category} onChange={event => setCategory(event.target.value as ReportCategory)}>
      {(Object.keys(categoryLabels) as ReportCategory[]).map(value =>
        <option key={value} value={value}>{categoryLabels[value]}</option>)}
    </select></label>
    <label>What happened<textarea required minLength={10} maxLength={1000} value={description}
      onChange={event => setDescription(event.target.value)} aria-describedby="report-description-hint" /></label>
    <small id="report-description-hint">Between 10 and 1000 characters.</small>
    {error && <Message kind="error">{error}</Message>}
    <div className="actions">
      <button className="danger" disabled={busy || description.trim().length < 10}>{busy ? "Sending…" : "Send report"}</button>
      {onClose && <button type="button" onClick={onClose} disabled={busy}>Cancel</button>}
    </div>
  </form>;
}
