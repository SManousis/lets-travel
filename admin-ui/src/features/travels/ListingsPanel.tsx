import * as React from "react";
import { allPages, api, ApiError, errorMessage, type ApiContext, type Page } from "../../lib/api";
import type { Destination, Travel, User } from "../../lib/types";
import { useResource } from "../../lib/useResource";
import { Empty, Loaded, Message } from "../../components/Shared";
import { formatPrice } from "../subscriptions/format";
import { blankTravel, fromTravel, ItineraryEditor, travelPayload, type TravelDraft } from "./ItineraryEditor";

const PAGE_SIZE = 10;
const statusLabels: Record<Travel["status"], string> = {
  DRAFT: "Draft", PUBLISHED: "Published", UNPUBLISHED: "Unpublished", CANCELLED: "Cancelled",
};

/** Explains the travel-service conflict codes in the editor's own words. */
export function listingError(error: unknown): string {
  if (!(error instanceof ApiError) || error.status !== 409) return errorMessage(error);
  switch (error.code) {
    case "PUBLICATION_NOT_READY": return "This travel cannot be published yet: it needs a future start date, a description, at least one place and a destination stop.";
    case "CAPACITY_BELOW_PARTICIPANTS": return "More travelers are already subscribed than that. Raise the number of places.";
    case "TRAVEL_IN_USE": return "Travelers are subscribed to this travel, so it cannot be deleted. Unpublish it instead.";
    case "TRAVEL_NOT_EDITABLE": return "A cancelled travel can no longer be changed.";
    case "MANAGER_REQUIRED": return "Choose the travel manager who organises this travel.";
    case "MANAGER_NOT_ACTIVE": return "That travel manager's account is not active.";
    default: return error.message;
  }
}

/**
 * Listing editor. Travel managers see and edit only their own travels; admins see every travel, choose the
 * organiser of a new one and also maintain the destination list.
 */
export function ListingsPanel({ context, admin = false }: { context: ApiContext; admin?: boolean }) {
  const [page, setPage] = React.useState(0);
  const [editing, setEditing] = React.useState<{ id: string | null; draft: TravelDraft } | null>(null);
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState("");
  const [notice, setNotice] = React.useState("");

  const loadList = React.useCallback((signal: AbortSignal) =>
    api<Page<Travel>>(context, `/api/travels?page=${page}&size=${PAGE_SIZE}`, { signal }), [context, page]);
  const [list, reloadList] = useResource(loadList);
  const loadDestinations = React.useCallback(() => allPages<Destination>(context, "/api/destinations"), [context]);
  const [destinations, reloadDestinations] = useResource(loadDestinations);
  const loadManagers = React.useCallback(async () => admin
    ? (await allPages<User>(context, "/api/users")).filter(user => user.role === "TRAVEL_MANAGER" && user.status === "ACTIVE")
      .map(user => ({ id: user.id, name: `${user.firstName} ${user.lastName} (${user.email})` }))
    : [], [context, admin]);
  const [managers] = useResource(loadManagers);

  async function act(work: () => Promise<unknown>, done: string): Promise<boolean> {
    setBusy(true); setError(""); setNotice("");
    try { await work(); setNotice(done); reloadList(); return true; }
    catch (caught) { setError(listingError(caught)); return false; }
    finally { setBusy(false); }
  }

  async function save(event: React.FormEvent) {
    event.preventDefault();
    if (!editing || busy) return;
    const { id, draft } = editing;
    const saved = await act(() => api<Travel>(context, id ? `/api/travels/${id}` : "/api/travels",
      { method: id ? "PUT" : "POST", body: travelPayload(draft) }),
    id ? `${draft.name} was saved.` : `${draft.name} was created as a draft. Publish it when it is ready.`);
    if (saved) setEditing(null);
  }

  async function edit(travel: Travel) {
    setError(""); setNotice("");
    try {
      const latest = await api<Travel>(context, `/api/travels/${travel.id}`);
      setEditing({ id: latest.id, draft: fromTravel(latest) });
    } catch (caught) { setError(listingError(caught)); }
  }

  function remove(travel: Travel) {
    if (!window.confirm(`Delete ${travel.name}? This cannot be undone.`)) return;
    void act(() => api<void>(context, `/api/travels/${travel.id}`, { method: "DELETE" }), `${travel.name} was deleted.`);
  }

  function publication(travel: Travel, publish: boolean) {
    void act(() => api<Travel>(context, `/api/travels/${travel.id}/publication`, { method: publish ? "POST" : "DELETE" }),
      publish ? `${travel.name} is published and open for booking.` : `${travel.name} is no longer listed.`);
  }

  const managerChoices = admin && managers.state === "ready" ? managers.data : undefined;

  return <div className="workflow-stack">
    <section className="workspace-card" aria-labelledby="listings-title">
      <div className="card-heading"><div><h2 id="listings-title">{admin ? "All travels" : "My travel listings"}</h2>
        <p className="record-meta">Travelers only see a travel after it is published.</p></div>
        <div className="actions">
          <button type="button" onClick={reloadList}>Refresh</button>
          <button type="button" className="primary" disabled={busy || editing?.id === null}
            onClick={() => { setError(""); setNotice(""); setEditing({ id: null, draft: blankTravel() }); }}>New travel</button>
        </div></div>
      {notice && <Message kind="success">{notice}</Message>}
      {error && !editing && <Message kind="error">{error}</Message>}
      <Loaded resource={list} retry={reloadList} label="travels">{result => result.items.length === 0
        ? <Empty>{admin ? "No travels yet." : "You have no travels yet. Create one with New travel."}</Empty>
        : <>
          <ul className="record-list">{result.items.map(travel => <li className="record-row" key={travel.id}>
            <div><strong>{travel.name}</strong>
              <span>{travel.startDate} – {travel.endDate} · {formatPrice(travel.price, travel.currency)}</span>
              <small>{statusLabels[travel.status]} · {travel.participantCount} of {travel.capacity} places taken</small></div>
            <div className="actions">
              <button type="button" disabled={busy || travel.status === "CANCELLED"} onClick={() => void edit(travel)}
                aria-label={`Edit ${travel.name}`}>Edit</button>
              {travel.status === "PUBLISHED"
                ? <button type="button" disabled={busy} onClick={() => publication(travel, false)}
                  aria-label={`Unpublish ${travel.name}`}>Unpublish</button>
                : <button type="button" className="primary" disabled={busy || travel.status === "CANCELLED"}
                  onClick={() => publication(travel, true)} aria-label={`Publish ${travel.name}`}>Publish</button>}
              <button type="button" className="danger" disabled={busy} onClick={() => remove(travel)}
                aria-label={`Delete ${travel.name}`}>Delete</button>
            </div></li>)}</ul>
          {result.total > PAGE_SIZE && <div className="page-controls" aria-label="Listing pages">
            <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
            <span>Page {page + 1} of {Math.ceil(result.total / PAGE_SIZE)}</span>
            <button type="button" disabled={(page + 1) * PAGE_SIZE >= result.total} onClick={() => setPage(page + 1)}>Next</button>
          </div>}
        </>}</Loaded>
    </section>
    {editing && <section className="workspace-card" aria-labelledby="listing-editor-title">
      <h3 id="listing-editor-title">{editing.id ? `Edit ${editing.draft.name}` : "New travel"}</h3>
      <Loaded resource={destinations} retry={reloadDestinations} label="destinations">{found =>
        <ItineraryEditor draft={editing.draft} onChange={draft => setEditing({ ...editing, draft })} destinations={found}
          managers={editing.id ? undefined : managerChoices} busy={busy} error={error}
          onSubmit={event => void save(event)} onCancel={() => { setEditing(null); setError(""); }} />}</Loaded>
    </section>}
    {admin && <DestinationsEditor context={context} onChanged={reloadDestinations} />}
  </div>;
}

/** Admin-only list of the places travels can stop at. */
function DestinationsEditor({ context, onChanged }: { context: ApiContext; onChanged: () => void }) {
  const load = React.useCallback(() => allPages<Destination>(context, "/api/destinations"), [context]);
  const [destinations, reload] = useResource(load);
  const [draft, setDraft] = React.useState({ name: "", country: "" });
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState("");

  async function change(work: () => Promise<unknown>) {
    setBusy(true); setError("");
    try { await work(); reload(); onChanged(); return true; }
    catch (caught) { setError(errorMessage(caught)); return false; }
    finally { setBusy(false); }
  }

  async function add(event: React.FormEvent) {
    event.preventDefault();
    if (busy) return;
    if (await change(() => api<Destination>(context, "/api/destinations",
      { method: "POST", body: { name: draft.name.trim(), country: draft.country.trim() } }))) setDraft({ name: "", country: "" });
  }

  function remove(destination: Destination) {
    if (!window.confirm(`Delete ${destination.name}?`)) return;
    void change(() => api<void>(context, `/api/destinations/${destination.id}`, { method: "DELETE" }));
  }

  return <section className="workspace-card" aria-labelledby="destinations-title">
    <h3 id="destinations-title">Destinations</h3>
    <form className="quick-form" onSubmit={event => void add(event)}>
      <label>Place<input required value={draft.name} onChange={event => setDraft({ ...draft, name: event.target.value })} /></label>
      <label>Country<input required value={draft.country} onChange={event => setDraft({ ...draft, country: event.target.value })} /></label>
      <button className="primary" disabled={busy}>{busy ? "Adding…" : "Add destination"}</button>
    </form>
    {error && <Message kind="error">{error}</Message>}
    <Loaded resource={destinations} retry={reload} label="destinations">{found => found.length === 0
      ? <Empty>No destinations yet.</Empty>
      : <ul className="record-list two-column">{found.map(destination => <li className="record-row" key={destination.id}>
        <div><strong>{destination.name}</strong><small>{destination.country}</small></div>
        <button type="button" className="danger" disabled={busy} onClick={() => remove(destination)}
          aria-label={`Delete ${destination.name}`}>Delete</button></li>)}</ul>}</Loaded>
  </section>;
}
