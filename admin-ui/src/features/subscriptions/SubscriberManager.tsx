import * as React from "react";
import { api, errorMessage, newKey, type ApiContext, type Page } from "../../lib/api";
import type { ContractPage, Subscriber, TravelOffer } from "../../lib/types";
import { Empty, Message } from "../../components/Shared";
import { isActive, statusClass, statusLabels } from "./format";

/**
 * Manager/admin subscriber lists. travel-service only lists a manager's own travels and
 * engagement-service answers 404 for anyone else's, so the screen never sees other managers' data.
 */
export function SubscriberManager({ context }: { context: ApiContext }) {
  const [travels, setTravels] = React.useState<TravelOffer[] | null>(null);
  const [travelId, setTravelId] = React.useState("");
  const [page, setPage] = React.useState(0);
  const [subscribers, setSubscribers] = React.useState<ContractPage<Subscriber> | null>(null);
  const [profile, setProfile] = React.useState<Subscriber | null>(null);
  const [removing, setRemoving] = React.useState<Subscriber | null>(null);
  const [reason, setReason] = React.useState("");
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState("");
  const [notice, setNotice] = React.useState("");
  const removalKey = React.useRef<string | null>(null);

  React.useEffect(() => {
    let current = true;
    api<Page<TravelOffer>>(context, "/api/travels?page=0&size=100")
      .then(result => { if (current) setTravels(result.items); })
      .catch(caught => { if (current) setError(errorMessage(caught)); });
    return () => { current = false; };
  }, [context]);

  const loadSubscribers = React.useCallback(async () => {
    if (!travelId) { setSubscribers(null); return; }
    try {
      setSubscribers(await api<ContractPage<Subscriber>>(context,
        `/api/managed-travels/${travelId}/subscribers?page=${page}&size=20`));
    } catch (caught) { setError(errorMessage(caught)); }
  }, [context, travelId, page]);

  React.useEffect(() => { void loadSubscribers(); }, [loadSubscribers]);

  function chooseTravel(id: string) {
    setTravelId(id); setPage(0); setProfile(null); setRemoving(null); setError(""); setNotice("");
  }

  function startRemoval(subscriber: Subscriber) {
    setRemoving(subscriber); setReason(""); setProfile(null); removalKey.current = newKey();
  }

  async function remove(event: React.FormEvent) {
    event.preventDefault();
    if (!removing) return;
    setBusy(true); setError(""); setNotice("");
    try {
      await api(context, `/api/managed-travels/${travelId}/subscribers/${removing.subscriptionId}/removal`, {
        method: "POST", body: { reason: reason.trim() }, headers: { "Idempotency-Key": removalKey.current ?? newKey() },
      });
      setNotice(`${removing.displayName} was unsubscribed.`);
      setRemoving(null); setReason("");
      await loadSubscribers();
    } catch (caught) { setError(errorMessage(caught)); }
    finally { setBusy(false); }
  }

  return <section className="workspace-card" aria-labelledby="subscribers-title">
    <div className="card-heading"><div><h3 id="subscribers-title">Subscribers</h3>
      <p className="record-meta">Choose one of your travels to see who has subscribed.</p></div>
      {travelId && <button type="button" onClick={() => void loadSubscribers()}>Refresh</button>}</div>
    {error && <Message kind="error">{error}</Message>}
    {notice && <Message kind="success">{notice}</Message>}
    {!travels ? <p>Loading travels…</p> : travels.length === 0 ? <Empty>You have no travels yet.</Empty>
      : <form className="quick-form" onSubmit={event => event.preventDefault()}>
        <label>Travel<select value={travelId} onChange={event => chooseTravel(event.target.value)}>
          <option value="">Choose travel</option>
          {travels.map(travel => <option key={travel.id} value={travel.id}>{travel.name} · {travel.startDate}</option>)}
        </select></label></form>}
    {travelId && subscribers && (subscribers.items.length === 0 ? <Empty>No one has subscribed to this travel yet.</Empty>
      : <ul className="record-list">{subscribers.items.map(subscriber => <li className="record-row" key={subscriber.subscriptionId}>
        <div><strong>{subscriber.displayName}</strong>
          <span className={statusClass(subscriber.status)}>{statusLabels[subscriber.status]}</span>
          <small>Subscribed {new Date(subscriber.subscribedAt).toLocaleDateString()}</small></div>
        <div className="actions">
          <button type="button" onClick={() => setProfile(profile?.subscriptionId === subscriber.subscriptionId ? null : subscriber)}>
            View profile</button>
          {isActive(subscriber.status) && <button type="button" className="danger" disabled={busy}
            onClick={() => startRemoval(subscriber)}>Unsubscribe</button>}
        </div></li>)}</ul>)}
    {subscribers && subscribers.totalPages > 1 && <div className="page-controls" aria-label="Subscriber pages">
      <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
      <span>Page {page + 1} of {subscribers.totalPages}</span>
      <button type="button" disabled={page + 1 >= subscribers.totalPages} onClick={() => setPage(page + 1)}>Next</button>
    </div>}
    {profile && <div aria-label="Traveler profile">
      <h4>{profile.displayName}</h4>
      <dl className="account-details">
        <dt>Member since</dt><dd>{profile.memberSince ?? "Account removed"}</dd>
        <dt>Completed trips</dt><dd>{profile.completedTrips}</dd>
        <dt>Upheld reports</dt><dd>{profile.reportCount}</dd>
        <dt>Status</dt><dd>{statusLabels[profile.status]}</dd>
        {profile.cancellationReason && <><dt>Removal reason</dt><dd>{profile.cancellationReason}</dd></>}
      </dl>
    </div>}
    {removing && <form className="field-grid" onSubmit={remove} aria-label="Unsubscribe traveler">
      <p>Unsubscribe {removing.displayName}? Their place is released. The reason is recorded in the audit trail.</p>
      <label>Reason<textarea required minLength={3} maxLength={500} value={reason}
        onChange={event => setReason(event.target.value)} /></label>
      <div className="actions"><button className="danger" disabled={busy || reason.trim().length < 3}>
        {busy ? "Unsubscribing…" : "Confirm unsubscribe"}</button>
        <button type="button" onClick={() => setRemoving(null)}>Keep subscriber</button></div>
    </form>}
  </section>;
}
