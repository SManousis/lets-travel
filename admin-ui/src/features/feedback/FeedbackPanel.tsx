import * as React from "react";
import { api, errorMessage, newKey, type ApiContext } from "../../lib/api";
import type { ContractPage, Feedback, Subscription } from "../../lib/types";
import { Empty, Message } from "../../components/Shared";
import { mayLeaveFeedback, stars } from "./format";

/** Traveler view: rate finished trips and see the feedback already given. */
export function FeedbackPanel({ context }: { context: ApiContext }) {
  const [trips, setTrips] = React.useState<Subscription[] | null>(null);
  const [given, setGiven] = React.useState<Feedback[] | null>(null);
  const [rating, setRating] = React.useState<Subscription | null>(null);
  const [score, setScore] = React.useState(5);
  const [comment, setComment] = React.useState("");
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState("");
  const [notice, setNotice] = React.useState("");
  // One key per travel until the submit succeeds, so a retried request cannot create two ratings.
  const keys = React.useRef(new Map<string, string>());

  const load = React.useCallback(async () => {
    try {
      const [mine, feedback] = await Promise.all([
        api<ContractPage<Subscription>>(context, "/api/subscriptions?page=0&size=100"),
        api<ContractPage<Feedback>>(context, "/api/feedback/mine?page=0&size=100"),
      ]);
      setTrips(mine.items); setGiven(feedback.items);
    } catch (caught) { setError(errorMessage(caught)); }
  }, [context]);

  React.useEffect(() => { void load(); }, [load]);

  const rated = new Set(given?.map(item => item.travelId));
  const eligible = (trips ?? []).filter(trip => mayLeaveFeedback(trip) && !rated.has(trip.travelId));

  function start(trip: Subscription) {
    setRating(trip); setScore(5); setComment(""); setError(""); setNotice("");
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!rating) return;
    setBusy(true); setError(""); setNotice("");
    const key = keys.current.get(rating.travelId) ?? newKey();
    keys.current.set(rating.travelId, key);
    try {
      await api<Feedback>(context, "/api/feedback", {
        method: "POST", headers: { "Idempotency-Key": key },
        body: { travelId: rating.travelId, rating: score, ...(comment.trim() ? { comment: comment.trim() } : {}) },
      });
      keys.current.delete(rating.travelId);
      setNotice(`Thank you. Your feedback on ${rating.travelTitle} was sent.`);
      setRating(null);
      await load();
    } catch (caught) { setError(errorMessage(caught)); }
    finally { setBusy(false); }
  }

  return <section className="workspace-card" aria-labelledby="feedback-title">
    <div className="card-heading"><div><h3 id="feedback-title">Feedback on your trips</h3>
      <p className="record-meta">You can rate a trip once, after it has ended.</p></div>
      <button type="button" onClick={() => void load()}>Refresh</button></div>
    {error && <Message kind="error">{error}</Message>}
    {notice && <Message kind="success">{notice}</Message>}
    {!trips ? <p>Loading trips…</p> : eligible.length === 0 ? <Empty>No finished trips are waiting for your feedback.</Empty>
      : <ul className="record-list">{eligible.map(trip => <li className="record-row" key={trip.subscriptionId}>
        <div><strong>{trip.travelTitle}</strong><span>{trip.startDate} – {trip.endDate}</span></div>
        <div className="actions"><button type="button" className="primary" onClick={() => start(trip)}>Leave feedback</button></div>
      </li>)}</ul>}
    {rating && <form className="field-grid" onSubmit={submit} aria-label={`Feedback on ${rating.travelTitle}`}>
      <label>Rating<select value={score} onChange={event => setScore(Number(event.target.value))}>
        {[5, 4, 3, 2, 1].map(value => <option key={value} value={value}>{stars(value)} ({value} of 5)</option>)}
      </select></label>
      <label>Comment (optional)<textarea maxLength={1000} value={comment} onChange={event => setComment(event.target.value)} />
        <small>{comment.length}/1000</small></label>
      <div className="actions"><button className="primary" disabled={busy}>{busy ? "Sending…" : "Send feedback"}</button>
        <button type="button" onClick={() => setRating(null)}>Cancel</button></div>
    </form>}
    <h4>Feedback you gave</h4>
    {!given ? <p>Loading feedback…</p> : given.length === 0 ? <Empty>You have not given feedback yet.</Empty>
      : <ul className="record-list">{given.map(item => <li className="record-row" key={item.feedbackId}>
        <div><strong>{item.travelTitle}</strong>
          <span aria-label={`${item.rating} of 5 stars`}>{stars(item.rating)}</span>
          {item.comment && <small>{item.comment}</small>}
          <small>{new Date(item.createdAt).toLocaleDateString()}</small></div>
      </li>)}</ul>}
  </section>;
}
