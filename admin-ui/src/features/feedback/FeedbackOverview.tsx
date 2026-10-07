import * as React from "react";
import { api, errorMessage, type ApiContext, type Page } from "../../lib/api";
import type { ContractPage, Feedback, TravelOffer } from "../../lib/types";
import { Empty, Message } from "../../components/Shared";
import { stars } from "./format";

/**
 * Quality control for managers (feedback on their own travels) and admins (all feedback).
 * engagement-service applies the scope; a manager asking for another manager's travel gets 404.
 */
export function FeedbackOverview({ context, admin = false }: { context: ApiContext; admin?: boolean }) {
  const [travels, setTravels] = React.useState<TravelOffer[]>([]);
  const [travelId, setTravelId] = React.useState("");
  const [page, setPage] = React.useState(0);
  const [result, setResult] = React.useState<ContractPage<Feedback> | null>(null);
  const [error, setError] = React.useState("");

  React.useEffect(() => {
    let current = true;
    api<Page<TravelOffer>>(context, "/api/travels?page=0&size=100")
      .then(found => { if (current) setTravels(found.items); })
      .catch(() => { /* The filter is optional; the list still loads. */ });
    return () => { current = false; };
  }, [context]);

  const load = React.useCallback(async () => {
    setError("");
    const filter = travelId ? `travelId=${encodeURIComponent(travelId)}&` : "";
    try { setResult(await api<ContractPage<Feedback>>(context, `/api/feedback?${filter}page=${page}&size=20`)); }
    catch (caught) { setError(errorMessage(caught)); }
  }, [context, travelId, page]);

  React.useEffect(() => { void load(); }, [load]);

  const average = result && result.items.length
    ? (result.items.reduce((sum, item) => sum + item.rating, 0) / result.items.length).toFixed(1) : null;

  return <section className="workspace-card" aria-labelledby="feedback-overview-title">
    <div className="card-heading"><div><h3 id="feedback-overview-title">{admin ? "All feedback" : "Feedback on your travels"}</h3>
      <p className="record-meta">{admin ? "Ratings and comments from travelers across every travel."
        : "What travelers said about the travels you organised."}</p></div>
      <button type="button" onClick={() => void load()}>Refresh</button></div>
    {error && <Message kind="error">{error}</Message>}
    <form className="quick-form" onSubmit={event => event.preventDefault()}>
      <label>Travel<select value={travelId} onChange={event => { setTravelId(event.target.value); setPage(0); }}>
        <option value="">All travels</option>
        {travels.map(travel => <option key={travel.id} value={travel.id}>{travel.name} · {travel.startDate}</option>)}
      </select></label></form>
    {!result ? <p>Loading feedback…</p> : result.items.length === 0 ? <Empty>No feedback yet.</Empty> : <>
      {average && <p className="record-meta">Average on this page: {average} of 5 ({result.totalElements} in total)</p>}
      <ul className="record-list">{result.items.map(item => <li className="record-row" key={item.feedbackId}>
        <div><strong>{item.travelTitle}</strong>
          <span aria-label={`${item.rating} of 5 stars`}>{stars(item.rating)}</span>
          {item.comment ? <small>{item.comment}</small> : <small>No comment</small>}
          <small>{item.authorDisplayName} · {new Date(item.createdAt).toLocaleDateString()}</small></div>
      </li>)}</ul></>}
    {result && result.totalPages > 1 && <div className="page-controls" aria-label="Feedback pages">
      <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
      <span>Page {page + 1} of {result.totalPages}</span>
      <button type="button" disabled={page + 1 >= result.totalPages} onClick={() => setPage(page + 1)}>Next</button>
    </div>}
  </section>;
}
