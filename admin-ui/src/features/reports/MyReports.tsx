import * as React from "react";
import { api, type ApiContext } from "../../lib/api";
import type { ContractPage, Report, ReportTargetType } from "../../lib/types";
import { Empty, Message } from "../../components/Shared";
import { categoryLabels, reportErrorMessage, statusLabels, targetLabels } from "./format";
import { ReportForm, type ReportTarget } from "./ReportForm";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** A traveler's own reports and their status, plus a form to report someone or a travel by ID. */
export function MyReports({ context }: { context: ApiContext }) {
  const [page, setPage] = React.useState(0);
  const [result, setResult] = React.useState<ContractPage<Report> | null>(null);
  const [error, setError] = React.useState("");
  const [targetType, setTargetType] = React.useState<ReportTargetType>("TRAVEL_MANAGER");
  const [targetId, setTargetId] = React.useState("");
  const [target, setTarget] = React.useState<ReportTarget | null>(null);

  const load = React.useCallback(async () => {
    setError("");
    try { setResult(await api<ContractPage<Report>>(context, `/api/reports?page=${page}&size=20`)); }
    catch (caught) { setError(reportErrorMessage(caught)); }
  }, [context, page]);

  React.useEffect(() => { void load(); }, [load]);

  function choose(event: React.FormEvent) {
    event.preventDefault();
    const id = targetId.trim();
    setTarget({ type: targetType, id, name: `${targetLabels[targetType].toLowerCase()} ${id.slice(0, 8)}` });
  }

  return <section className="workspace-card" aria-labelledby="my-reports-title">
    <div className="card-heading"><div><h3 id="my-reports-title">My reports</h3>
      <p className="record-meta">Reports you sent and what the administrators decided. Only you can see them.</p></div>
      <button type="button" onClick={() => void load()}>Refresh</button></div>
    {error && <Message kind="error">{error}</Message>}
    {!result ? <p>Loading your reports…</p> : result.items.length === 0 ? <Empty>You have not filed any reports.</Empty>
      : <ul className="record-list">{result.items.map(report => <li className="record-row" key={report.reportId}>
        <div><strong>{report.targetDisplayName}</strong> <small>{targetLabels[report.targetType]}</small>
          <small>{categoryLabels[report.category]} · {statusLabels[report.status]} · {new Date(report.createdAt).toLocaleDateString()}</small>
          <small>{report.description}</small></div>
      </li>)}</ul>}
    {result && result.totalPages > 1 && <div className="page-controls" aria-label="Report pages">
      <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
      <span>Page {page + 1} of {result.totalPages}</span>
      <button type="button" disabled={page + 1 >= result.totalPages} onClick={() => setPage(page + 1)}>Next</button>
    </div>}

    <h4>Report something else</h4>
    <p className="record-meta">Most reports start from a travel or a manager page. You can also report by ID.</p>
    {target ? <ReportForm key={`${target.type}:${target.id}`} context={context} target={target}
      onFiled={() => void load()} onClose={() => { setTarget(null); setTargetId(""); }} />
      : <form className="quick-form" onSubmit={choose} aria-label="Choose what to report">
        <label>What<select value={targetType} onChange={event => setTargetType(event.target.value as ReportTargetType)}>
          {(Object.keys(targetLabels) as ReportTargetType[]).map(value =>
            <option key={value} value={value}>{targetLabels[value]}</option>)}
        </select></label>
        <label>ID<input value={targetId} onChange={event => setTargetId(event.target.value)}
          placeholder="00000000-0000-0000-0000-000000000000" /></label>
        <button type="submit" disabled={!UUID.test(targetId.trim())}>Continue</button>
      </form>}
  </section>;
}
