import * as React from "react";
import { allPages, api, ApiError, errorMessage, type ApiContext, type Page } from "../../lib/api";
import type { Destination, Travel, Traveler } from "../../lib/types";
import { Empty, Message, PageControls } from "../../components/Shared";
import { blankTravel, fromTravel, ItineraryEditor, travelPayload, type TravelDraft } from "./ItineraryEditor";

export function TravelsPanel({ context, active }: { context: ApiContext; active: boolean }) {
  const [destinationPage, setDestinationPage] = React.useState(0);
  const [destinationList, setDestinationList] = React.useState<Page<Destination> | null>(null);
  const [allDestinations, setAllDestinations] = React.useState<Destination[]>([]);
  const [destinationDraft, setDestinationDraft] = React.useState({ name: "", country: "" });
  const [editingDestination, setEditingDestination] = React.useState<Destination | null>(null);
  const [destinationBusy, setDestinationBusy] = React.useState(false);
  const [destinationError, setDestinationError] = React.useState("");
  const [destinationNotice, setDestinationNotice] = React.useState("");
  const [travelPage, setTravelPage] = React.useState(0);
  const [travelList, setTravelList] = React.useState<Page<Travel> | null>(null);
  const [selectedTravel, setSelectedTravel] = React.useState<Travel | null>(null);
  const selectedTravelId = React.useRef<string | null>(null);
  const [travelDraft, setTravelDraft] = React.useState<TravelDraft>(blankTravel());
  const [editorOpen, setEditorOpen] = React.useState(false);
  const [editingTravelId, setEditingTravelId] = React.useState<string | null>(null);
  const [travelBusy, setTravelBusy] = React.useState(false);
  const [travelError, setTravelError] = React.useState("");
  const [travelNotice, setTravelNotice] = React.useState("");
  const [travelers, setTravelers] = React.useState<Traveler[]>([]);
  const [participantId, setParticipantId] = React.useState("");
  const [participantBusy, setParticipantBusy] = React.useState(false);

  const loadDestinations = React.useCallback(async () => {
    try {
      const [page, all] = await Promise.all([
        api<Page<Destination>>(context, `/api/destinations?p=${destinationPage}&size=10`),
        allPages<Destination>(context, "/api/destinations", "p"),
      ]);
      setDestinationList(page); setAllDestinations(all);
    } catch (error) { setDestinationError(errorMessage(error)); }
  }, [context, destinationPage]);

  const loadTravels = React.useCallback(async () => {
    try {
      setTravelList(await api<Page<Travel>>(context, `/api/travels?p=${travelPage}&size=10`));
      const id = selectedTravelId.current;
      if (id) {
        try {
          const detail = await api<Travel>(context, `/api/travels/${id}`);
          if (selectedTravelId.current === id) setSelectedTravel(detail);
        } catch (error) {
          if (selectedTravelId.current === id) {
            if (error instanceof ApiError && error.status === 404) {
              selectedTravelId.current = null; setSelectedTravel(null);
            } else setTravelError(errorMessage(error));
          }
        }
      }
    }
    catch (error) { setTravelError(errorMessage(error)); }
  }, [context, travelPage]);

  const loadTravelers = React.useCallback(async () => {
    try { setTravelers(await allPages<Traveler>(context, "/api/travelers")); }
    catch (error) { setTravelError(errorMessage(error)); }
  }, [context]);

  React.useEffect(() => { if (active) void loadDestinations(); }, [active, loadDestinations]);
  React.useEffect(() => { if (active) void loadTravels(); }, [active, loadTravels]);
  React.useEffect(() => { if (active) void loadTravelers(); }, [active, loadTravelers]);

  function startNewTravel() {
    setEditingTravelId(null); setTravelDraft(blankTravel(allDestinations[0]?.id));
    setEditorOpen(true); setTravelError("");
  }

  async function editTravel(travel: Travel) {
    setTravelBusy(true); setTravelError("");
    try {
      const latest = await api<Travel>(context, `/api/travels/${travel.id}`);
      if (selectedTravelId.current !== travel.id) return;
      setSelectedTravel(latest); setEditingTravelId(latest.id); setTravelDraft(fromTravel(latest));
      setEditorOpen(true);
    } catch (error) { setTravelError(errorMessage(error)); }
    finally { setTravelBusy(false); }
  }

  async function saveDestination(event: React.FormEvent) {
    event.preventDefault(); setDestinationBusy(true); setDestinationError(""); setDestinationNotice("");
    try {
      await api<Destination>(context, editingDestination ? `/api/destinations/${editingDestination.id}` : "/api/destinations",
        { method: editingDestination ? "PATCH" : "POST", body: destinationDraft });
      setDestinationNotice(editingDestination ? "Destination updated." : "Destination created.");
      setDestinationDraft({ name: "", country: "" }); setEditingDestination(null); await loadDestinations();
    } catch (error) { setDestinationError(errorMessage(error)); }
    finally { setDestinationBusy(false); }
  }

  async function deleteDestination(destination: Destination) {
    if (!window.confirm(`Delete ${destination.name}? Destinations used by a travel cannot be deleted.`)) return;
    setDestinationBusy(true); setDestinationError(""); setDestinationNotice("");
    try {
      await api<void>(context, `/api/destinations/${destination.id}`, { method: "DELETE" });
      setDestinationNotice("Destination deleted."); await loadDestinations();
    } catch (error) { setDestinationError(errorMessage(error)); }
    finally { setDestinationBusy(false); }
  }

  async function saveTravel(event: React.FormEvent) {
    event.preventDefault(); setTravelBusy(true); setTravelError(""); setTravelNotice("");
    try {
      const travel = await api<Travel>(context, editingTravelId ? `/api/travels/${editingTravelId}` : "/api/travels",
        { method: editingTravelId ? "PATCH" : "POST", body: travelPayload(travelDraft) });
      selectedTravelId.current = travel.id;
      setSelectedTravel(travel); setEditorOpen(false); setEditingTravelId(null);
      setTravelNotice("Travel saved with its itinerary."); await loadTravels();
    } catch (error) { setTravelError(errorMessage(error)); }
    finally { setTravelBusy(false); }
  }

  async function deleteTravel(travel: Travel) {
    if (!window.confirm(`Delete ${travel.name} and its itinerary? Shared destinations will remain.`)) return;
    setTravelBusy(true); setTravelError(""); setTravelNotice("");
    try {
      await api<void>(context, `/api/travels/${travel.id}`, { method: "DELETE" });
      if (selectedTravelId.current === travel.id) {
        selectedTravelId.current = null; setSelectedTravel(null);
      }
      setEditorOpen(false); setTravelNotice("Travel deleted."); await loadTravels();
    } catch (error) { setTravelError(errorMessage(error)); }
    finally { setTravelBusy(false); }
  }

  async function addParticipant(event: React.FormEvent) {
    event.preventDefault(); if (!selectedTravel || !participantId) return;
    setParticipantBusy(true); setTravelError("");
    try {
      const travel = await api<Travel>(context, `/api/travels/${selectedTravel.id}/travelers/${participantId}`, { method: "POST" });
      setSelectedTravel(travel); setParticipantId(""); setTravelNotice("Traveler added to travel."); await loadTravels();
    } catch (error) { setTravelError(errorMessage(error)); }
    finally { setParticipantBusy(false); }
  }

  async function removeParticipant(travelerId: string) {
    if (!selectedTravel || !window.confirm("Remove this traveler from the travel?")) return;
    setParticipantBusy(true); setTravelError("");
    try {
      await api<void>(context, `/api/travels/${selectedTravel.id}/travelers/${travelerId}`, { method: "DELETE" });
      setSelectedTravel(await api<Travel>(context, `/api/travels/${selectedTravel.id}`));
      setTravelNotice("Traveler removed from travel."); await loadTravels();
    } catch (error) { setTravelError(errorMessage(error)); }
    finally { setParticipantBusy(false); }
  }

  const availableTravelers = travelers.filter(traveler => !selectedTravel?.travelerIds.includes(traveler.id));
  const travelerById = new Map(travelers.map(traveler => [traveler.id, traveler]));

  return <div className="workflow-stack">
    <div className="panel-heading"><div><p className="eyebrow">Itineraries</p><h2>Travels and destinations</h2></div>
      <p>Build complete trips while preserving shared destination records.</p></div>
    <div className="workspace-grid">
      <section className="workspace-card" aria-labelledby="destination-list-title">
        <div className="card-heading"><h3 id="destination-list-title">Destinations</h3>
          <button type="button" onClick={() => void loadDestinations()}>Refresh</button></div>
        {destinationError && <Message kind="error">{destinationError}</Message>}
        {!destinationList ? <p>Loading destinations…</p> : destinationList.items.length === 0 ? <Empty>No destinations yet.</Empty>
          : <ul className="record-list">{destinationList.items.map(destination => <li className="record-row" key={destination.id}>
            <div><strong>{destination.name}</strong><span>{destination.country}</span></div>
            <div className="actions"><button type="button" onClick={() => {
              setEditingDestination(destination); setDestinationDraft({ name: destination.name, country: destination.country });
            }}>Edit</button><button type="button" className="danger" disabled={destinationBusy}
              onClick={() => void deleteDestination(destination)}>Delete</button></div></li>)}</ul>}
        <PageControls result={destinationList} onPage={setDestinationPage} />
      </section>
      <section className="workspace-card" aria-labelledby="destination-form-title">
        <h3 id="destination-form-title">{editingDestination ? "Edit destination" : "Add destination"}</h3>
        {destinationNotice && <Message kind="success">{destinationNotice}</Message>}
        <form className="field-grid" onSubmit={saveDestination}>
          <label>Name<input required value={destinationDraft.name} onChange={event => setDestinationDraft({ ...destinationDraft, name: event.target.value })} /></label>
          <label>Country<input required value={destinationDraft.country} onChange={event => setDestinationDraft({ ...destinationDraft, country: event.target.value })} /></label>
          <div className="actions"><button className="primary" disabled={destinationBusy}>{destinationBusy ? "Saving…" : "Save destination"}</button>
            {editingDestination && <button type="button" onClick={() => { setEditingDestination(null); setDestinationDraft({ name: "", country: "" }); }}>Cancel</button>}</div>
        </form>
      </section>
    </div>
    <section className="workspace-card" aria-labelledby="travel-list-title">
      <div className="card-heading"><div><h3 id="travel-list-title">Travels</h3><p className="record-meta">Select a trip to view its itinerary and participants.</p></div>
        <div className="actions"><button type="button" onClick={() => void loadTravels()}>Refresh</button>
          <button type="button" className="primary" onClick={startNewTravel}>New travel</button></div></div>
      {travelError && !editorOpen && <Message kind="error">{travelError}</Message>}
      {travelNotice && <Message kind="success">{travelNotice}</Message>}
      {!travelList ? <p>Loading travels…</p> : travelList.items.length === 0 ? <Empty>No travels yet.</Empty>
        : <ul className="record-list two-column">{travelList.items.map(travel => <li key={travel.id}>
          <button type="button" className={selectedTravel?.id === travel.id ? "record selected" : "record"}
            onClick={() => { selectedTravelId.current = travel.id; setSelectedTravel(travel); setEditorOpen(false); }}>
            <strong>{travel.name}</strong><span>{travel.startDate} – {travel.endDate}</span>
            <small>{travel.durationDays} days · {travel.stops.length} stops</small>
          </button></li>)}</ul>}
      <PageControls result={travelList} onPage={setTravelPage} />
    </section>
    {editorOpen && <section className="workspace-card" aria-labelledby="itinerary-title">
      <h3 id="itinerary-title">{editingTravelId ? "Edit travel and itinerary" : "Create travel and itinerary"}</h3>
      <ItineraryEditor draft={travelDraft} onChange={setTravelDraft} destinations={allDestinations}
        onSubmit={saveTravel} onCancel={() => setEditorOpen(false)} busy={travelBusy} error={travelError} />
    </section>}
    {selectedTravel && !editorOpen && <section className="workspace-card" aria-labelledby="travel-detail-title">
      <div className="card-heading"><div><h3 id="travel-detail-title">{selectedTravel.name}</h3>
        <p className="record-meta">{selectedTravel.startDate} to {selectedTravel.endDate} · {selectedTravel.durationDays} calendar days</p></div>
        <div className="actions"><button type="button" disabled={travelBusy} onClick={() => void editTravel(selectedTravel)}>Edit itinerary</button>
          <button type="button" className="danger" disabled={travelBusy} onClick={() => void deleteTravel(selectedTravel)}>Delete travel</button></div></div>
      <div className="detail-grid"><div><h4>Stops</h4><ol>{[...selectedTravel.stops].sort((a, b) => a.position - b.position).map(stop =>
        <li key={stop.id}>{allDestinations.find(destination => destination.id === stop.destinationId)?.name ?? stop.destinationId}
          {stop.arrivalDate && <> · {stop.arrivalDate}</>}</li>)}</ol></div>
        <div><h4>Activities</h4>{selectedTravel.activities.length ? <ul>{selectedTravel.activities.map(activity =>
          <li key={activity.id}>{activity.name} · {activity.date}</li>)}</ul> : <Empty>No activities.</Empty>}</div>
        <div><h4>Accommodation</h4>{selectedTravel.accommodations.length ? <ul>{selectedTravel.accommodations.map(stay =>
          <li key={stay.id}>{stay.name} · {stay.checkIn} to {stay.checkOut}</li>)}</ul> : <Empty>No accommodation.</Empty>}</div>
        <div><h4>Transport</h4>{selectedTravel.transports.length ? <ul>{selectedTravel.transports.map(transport =>
          <li key={transport.id}>{transport.mode}: {transport.departure} to {transport.arrival} · {transport.date}</li>)}</ul> : <Empty>No transport.</Empty>}</div></div>
      <div className="subsection-heading"><h4>Travelers</h4><button type="button" onClick={() => void loadTravelers()}>Refresh options</button></div>
      {selectedTravel.travelerIds.length === 0 ? <Empty>No travelers participating.</Empty> : <ul className="participant-list">
        {selectedTravel.travelerIds.map(id => <li key={id}><span>{travelerById.get(id)?.passportNumber ?? id}</span>
          <button type="button" className="danger" disabled={participantBusy} onClick={() => void removeParticipant(id)}>Remove</button></li>)}
      </ul>}
      <form className="quick-form" onSubmit={addParticipant}><label>Add traveler<select required value={participantId} onChange={event => setParticipantId(event.target.value)}>
        <option value="">Choose traveler</option>{availableTravelers.map(traveler => <option key={traveler.id} value={traveler.id}>
          {traveler.passportNumber} · {traveler.nationality}</option>)}</select></label>
        <button className="primary" disabled={participantBusy || !participantId}>{participantBusy ? "Saving…" : "Add to travel"}</button></form>
    </section>}
  </div>;
}
