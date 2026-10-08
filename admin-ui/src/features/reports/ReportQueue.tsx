import * as React from "react";
import { api, type ApiContext } from "../../lib/api";
import type { ContractPage, ReportDetail, ReportStatus, ReportTargetType } from "../../lib/types";
import { Empty, Message } from "../../components/Shared";
import { categoryLabels, isClosed, reportErrorMessage, statusLabels, targetLabels } from "./format";

const nextStatuses: ReportStatus[] = ["IN_REVIEW", "RESOLVED", "DISMISSED"];

/**
 * Admin moderation queue, oldest open reports first. Every decision needs a note and is kept
 * in the report's history; "Upheld" and "Dismissed" are final.
 */
export function ReportQueue({ context }: { context: ApiContext }) {
  const [status, setStatus] = React.useState<ReportStatus | "">("OPEN");
  const [targetType, setTargetType] = React.useState<ReportTargetType | "">("");
  const [page, setPage] = React.useState(0);
  const [result, setResult] = React.useState<ContractPage<ReportDetail> | null>(null);
  const [selected, setSelected] = React.useState<ReportDetail | null>(null);
  const [nextStatus, setNextStatus] = React.useState<ReportStatus>("IN_REVIEW");
  const [note, setNote] = React.useState("");
  const [assignToSelf, setAssignToSelf] = React.useState(true);
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState("");
  const [notice, setNotice] = React.useState("");

  const load = React.useCallback(async () => {
    setError("");
    const filters = `${status ? `status=${status}&` : ""}${targetType ? `targetType=${targetType}&` : ""}`;
    try { setResult(await api<ContractPage<ReportDetail>>(context, `/api/admin/reports?${filters}page=${page}&size=20`)); }
    catch (caught) { setError(reportErrorMessage(caught)); }
  }, [context, status, targetType, page]);

  React.useEffect(() => { void load(); }, [load]);

  function open(report: ReportDetail) {
    setSelected(report); setNextStatus("IN_REVIEW"); setNote(""); setNotice(""); setError("");
  }

  async function moderate(event: React.FormEvent) {
    event.preventDefault();
    if (!selected) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const updated = await api<ReportDetail>(context, `/api/admin/reports/${selected.reportId}`, {
        method: "PATCH", body: { status: nextStatus, note: note.trim(), assignToSelf },
      });
      setSelected(updated); setNote("");
      setNotice(`Report marked ${statusLabels[updated.status].toLowerCase()}.`);
      await load();
    } catch (caught) { setError(reportErrorMessage(caught)); }
    finally { setBusy(false); }
  }

  return <section className="workspace-card" aria-labelledby="report-queue-title">
    <div className="card-heading"><div><h3 id="report-queue-title">Reports</h3>
      <p className="record-meta">Reports filed by travelers. Upheld reports count on the person's public page.</p></div>
      <button type="button" onClick={() => void load()}>Refresh</button></div>
    {error && <Message kind="error">{error}</Message>}
    {notice && <Message kind="success">{notice}</Message>}
    <form className="quick-form" onSubmit={event => event.preventDefault()} aria-label="Filter reports">
      <label>Status<select value={status} onChange={event => { setStatus(event.target.value as ReportStatus | ""); setPage(0); }}>
        <option value="">All</option>
        {(Object.keys(statusLabels) as ReportStatus[]).map(value => <option key={value} value={value}>{statusLabels[value]}</option>)}
      </select></label>
      <label>About<select value={targetType} onChange={event => { setTargetType(event.target.value as ReportTargetType | ""); setPage(0); }}>
        <option value="">Anyone or anything</option>
        {(Object.keys(targetLabels) as ReportTargetType[]).map(value => <option key={value} value={value}>{targetLabels[value]}</option>)}
      </select></label>
    </form>
    {!result ? <p>Loading reports…</p> : result.items.length === 0 ? <Empty>No reports match these filters.</Empty>
      : <ul className="record-list">{result.items.map(report => <li className="record-row" key={report.reportId}>
        <div><strong>{report.targetDisplayName}</strong> <small>{targetLabels[report.targetType]}</small>
          <small>{categoryLabels[report.category]} · {statusLabels[report.status]} · {new Date(report.createdAt).toLocaleDateString()}</small></div>
        <button type="button" onClick={() => open(report)}
          aria-label={`Open report about ${report.targetDisplayName}`}>Open</button>
      </li>)}</ul>}
    {result && result.totalPages > 1 && <div className="page-controls" aria-label="Report pages">
      <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
      <span>Page {page + 1} of {result.totalPages}</span>
      <button type="button" disabled={page + 1 >= result.totalPages} onClick={() => setPage(page + 1)}>Next</button>
    </div>}

    {selected && <div className="report-detail" role="region" aria-label="Report details">
      <h4>Report about {selected.targetDisplayName}</h4>
      <dl className="account-details">
        <dt>About</dt><dd>{targetLabels[selected.targetType]} · {selected.targetId}</dd>
        <dt>Reason</dt><dd>{categoryLabels[selected.category]}</dd>
        <dt>Status</dt><dd>{statusLabels[selected.status]}</dd>
        <dt>Reported by</dt><dd>{selected.reporterId}</dd>
        <dt>Filed</dt><dd>{new Date(selected.createdAt).toLocaleString()}</dd>
      </dl>
      <p className="report-description">{selected.description}</p>
      <h5>History</h5>
      {selected.history.length === 0 ? <Empty>No decisions yet.</Empty>
        : <ol className="record-list">{selected.history.map((entry, index) => <li className="record-row" key={index}>
          <div><strong>{statusLabels[entry.status]}</strong> <small>{new Date(entry.at).toLocaleString()} · admin {entry.adminId.slice(0, 8)}</small>
            <small>{entry.note}</small></div></li>)}</ol>}
      {isClosed(selected.status) ? <Message>This report is closed. Its decision is final.</Message>
        : <form className="field-grid" onSubmit={moderate} aria-label="Moderate report">
          <label>Decision<select value={nextStatus} onChange={event => setNextStatus(event.target.value as ReportStatus)}>
            {nextStatuses.map(value => <option key={value} value={value}>{statusLabels[value]}</option>)}
          </select></label>
          <label>Note<textarea required minLength={3} maxLength={1000} value={note} onChange={event => setNote(event.target.value)} /></label>
          <label className="checkbox"><input type="checkbox" checked={assignToSelf}
            onChange={event => setAssignToSelf(event.target.checked)} /> Assign to me</label>
          <div className="actions">
            <button className="primary" disabled={busy || note.trim().length < 3}>{busy ? "Saving…" : "Save decision"}</button>
            <button type="button" onClick={() => setSelected(null)} disabled={busy}>Close</button>
          </div>
        </form>}
    </div>}
  </section>;
}
