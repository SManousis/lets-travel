import * as React from "react";
import { api, errorMessage, type ApiContext } from "../../lib/api";
import type { ManagerPage } from "../../lib/types";
import { Empty, Message } from "../../components/Shared";
import { stars } from "../feedback/format";
import { ReportForm } from "../reports/ReportForm";

/** The public Travel Manager page: statistics, past ratings (authors hidden) and the upheld report count. */
export function ManagerProfile({ context, managerId }: { context: ApiContext; managerId: string }) {
  const [page, setPage] = React.useState<ManagerPage | null>(null);
  const [reporting, setReporting] = React.useState(false);
  const [error, setError] = React.useState("");

  React.useEffect(() => {
    let current = true;
    setPage(null); setError("");
    api<ManagerPage>(context, `/api/managers/${encodeURIComponent(managerId)}`)
      .then(found => { if (current) setPage(found); })
      .catch(caught => { if (current) setError(errorMessage(caught)); });
    return () => { current = false; };
  }, [context, managerId]);

  if (error) return <Message kind="error">{error}</Message>;
  if (!page) return <p>Loading organiser…</p>;
  return <div aria-label="Travel manager page">
    <h4>{page.displayName}</h4>
    <dl className="account-details">
      <dt>Organiser since</dt><dd>{new Date(page.memberSince).toLocaleDateString()}</dd>
      <dt>Completed travels</dt><dd>{page.completedTravels} of {page.totalTravels}</dd>
      <dt>Average rating</dt><dd>{page.averageRating === null ? "No ratings yet"
        : `${page.averageRating.toFixed(1)} of 5 (${page.ratingCount} ratings)`}</dd>
      <dt>Upheld reports</dt><dd>{page.reportCount}</dd>
    </dl>
    <h4>Recent ratings</h4>
    {page.recentRatings.length === 0 ? <Empty>No ratings yet.</Empty>
      : <ul className="record-list">{page.recentRatings.map(rating => <li className="record-row" key={`${rating.createdAt}-${rating.travelTitle}`}>
        <div><strong>{rating.travelTitle}</strong>
          <span aria-label={`${rating.rating} of 5 stars`}>{stars(rating.rating)}</span>
          {rating.comment && <small>{rating.comment}</small>}
          <small>{new Date(rating.createdAt).toLocaleDateString()}</small></div></li>)}</ul>}
    {reporting ? <ReportForm context={context} target={{ type: "TRAVEL_MANAGER", id: page.managerId, name: page.displayName }}
      onClose={() => setReporting(false)} />
      : <div className="actions"><button type="button" className="danger" onClick={() => setReporting(true)}>Report this manager</button></div>}
  </div>;
}
