import * as React from "react";
import type { Destination, Travel } from "../../lib/types";
import { Message } from "../../components/Shared";

type StopDraft = { destinationId: string; arrivalDate: string; departureDate: string };
type ActivityDraft = { name: string; description: string; date: string };
type StayDraft = { name: string; address: string; checkIn: string; checkOut: string };
type TransportDraft = { mode: string; departure: string; arrival: string; date: string };

export type TravelDraft = {
  name: string; startDate: string; endDate: string; stops: StopDraft[];
  activities: ActivityDraft[]; accommodations: StayDraft[]; transports: TransportDraft[];
};

export function blankTravel(destinationId = ""): TravelDraft {
  return { name: "", startDate: "", endDate: "", stops: [{ destinationId, arrivalDate: "", departureDate: "" }],
    activities: [], accommodations: [], transports: [] };
}

export function fromTravel(travel: Travel): TravelDraft {
  return { name: travel.name, startDate: travel.startDate, endDate: travel.endDate,
    stops: [...travel.stops].sort((a, b) => a.position - b.position).map(stop => ({
      destinationId: stop.destinationId, arrivalDate: stop.arrivalDate ?? "", departureDate: stop.departureDate ?? "",
    })),
    activities: travel.activities.map(activity => ({ name: activity.name, description: activity.description ?? "", date: activity.date })),
    accommodations: travel.accommodations.map(stay => ({ name: stay.name, address: stay.address, checkIn: stay.checkIn, checkOut: stay.checkOut })),
    transports: travel.transports.map(transport => ({ mode: transport.mode, departure: transport.departure, arrival: transport.arrival, date: transport.date })),
  };
}

export function travelPayload(draft: TravelDraft) {
  return { name: draft.name, startDate: draft.startDate, endDate: draft.endDate,
    stops: draft.stops.map((stop, position) => ({ destinationId: stop.destinationId, position,
      arrivalDate: stop.arrivalDate || null, departureDate: stop.departureDate || null })),
    activities: draft.activities, accommodations: draft.accommodations, transports: draft.transports };
}

export function ItineraryEditor({ draft, onChange, destinations, onSubmit, onCancel, busy, error }:
  { draft: TravelDraft; onChange: (next: TravelDraft) => void; destinations: Destination[];
    onSubmit: (event: React.FormEvent) => void; onCancel: () => void; busy: boolean; error: string }) {
  function updateRow<K extends "stops" | "activities" | "accommodations" | "transports">(
    key: K, index: number, value: TravelDraft[K][number]) {
    const rows = [...draft[key]] as Array<TravelDraft[K][number]>;
    rows[index] = value;
    onChange({ ...draft, [key]: rows });
  }
  function removeRow<K extends "stops" | "activities" | "accommodations" | "transports">(key: K, index: number) {
    onChange({ ...draft, [key]: draft[key].filter((_, row) => row !== index) });
  }
  function moveStop(index: number, direction: number) {
    const next = index + direction;
    if (next < 0 || next >= draft.stops.length) return;
    const rows = [...draft.stops];
    [rows[index], rows[next]] = [rows[next], rows[index]];
    onChange({ ...draft, stops: rows });
  }

  return <form className="itinerary-form" onSubmit={onSubmit}>
    {error && <Message kind="error">{error}</Message>}
    <div className="field-grid columns">
      <label>Travel name<input required value={draft.name} onChange={event => onChange({ ...draft, name: event.target.value })} /></label>
      <label>Start date<input required type="date" value={draft.startDate} onChange={event => onChange({ ...draft, startDate: event.target.value })} /></label>
      <label>End date<input required type="date" min={draft.startDate || undefined} value={draft.endDate} onChange={event => onChange({ ...draft, endDate: event.target.value })} /></label>
    </div>
    <div className="subsection-heading"><h4>Destination stops</h4>
      <button type="button" onClick={() => onChange({ ...draft, stops: [...draft.stops,
        { destinationId: destinations[0]?.id ?? "", arrivalDate: "", departureDate: "" }] })}>Add stop</button></div>
    {destinations.length === 0 && <Message kind="info">Create a destination before saving a travel.</Message>}
    {draft.stops.map((stop, index) => <div className="nested-row" key={`stop-${index}`}>
      <span className="row-number">{index + 1}</span>
      <label>Destination<select required value={stop.destinationId} onChange={event => updateRow("stops", index, { ...stop, destinationId: event.target.value })}>
        <option value="">Choose destination</option>{destinations.map(destination => <option key={destination.id} value={destination.id}>{destination.name}, {destination.country}</option>)}
      </select></label>
      <label>Arrival<input type="date" value={stop.arrivalDate} onChange={event => updateRow("stops", index, { ...stop, arrivalDate: event.target.value })} /></label>
      <label>Departure<input type="date" value={stop.departureDate} onChange={event => updateRow("stops", index, { ...stop, departureDate: event.target.value })} /></label>
      <div className="actions compact"><button type="button" disabled={index === 0} onClick={() => moveStop(index, -1)} aria-label={`Move stop ${index + 1} up`}>↑</button>
        <button type="button" disabled={index === draft.stops.length - 1} onClick={() => moveStop(index, 1)} aria-label={`Move stop ${index + 1} down`}>↓</button>
        <button type="button" className="danger" disabled={draft.stops.length === 1} onClick={() => removeRow("stops", index)}>Remove</button></div>
    </div>)}
    <div className="subsection-heading"><h4>Activities</h4><button type="button" onClick={() => onChange({ ...draft,
      activities: [...draft.activities, { name: "", description: "", date: draft.startDate }] })}>Add activity</button></div>
    {draft.activities.map((activity, index) => <div className="nested-row" key={`activity-${index}`}>
      <label>Name<input required value={activity.name} onChange={event => updateRow("activities", index, { ...activity, name: event.target.value })} /></label>
      <label>Description<input value={activity.description} onChange={event => updateRow("activities", index, { ...activity, description: event.target.value })} /></label>
      <label>Date<input required type="date" min={draft.startDate || undefined} max={draft.endDate || undefined} value={activity.date} onChange={event => updateRow("activities", index, { ...activity, date: event.target.value })} /></label>
      <button type="button" className="danger" onClick={() => removeRow("activities", index)}>Remove</button>
    </div>)}
    <div className="subsection-heading"><h4>Accommodation</h4><button type="button" onClick={() => onChange({ ...draft,
      accommodations: [...draft.accommodations, { name: "", address: "", checkIn: draft.startDate, checkOut: draft.endDate }] })}>Add stay</button></div>
    {draft.accommodations.map((stay, index) => <div className="nested-row" key={`stay-${index}`}>
      <label>Name<input required value={stay.name} onChange={event => updateRow("accommodations", index, { ...stay, name: event.target.value })} /></label>
      <label>Address<input required value={stay.address} onChange={event => updateRow("accommodations", index, { ...stay, address: event.target.value })} /></label>
      <label>Check in<input required type="date" min={draft.startDate || undefined} max={draft.endDate || undefined} value={stay.checkIn} onChange={event => updateRow("accommodations", index, { ...stay, checkIn: event.target.value })} /></label>
      <label>Check out<input required type="date" min={draft.startDate || undefined} max={draft.endDate || undefined} value={stay.checkOut} onChange={event => updateRow("accommodations", index, { ...stay, checkOut: event.target.value })} /></label>
      <button type="button" className="danger" onClick={() => removeRow("accommodations", index)}>Remove</button>
    </div>)}
    <div className="subsection-heading"><h4>Transportation</h4><button type="button" onClick={() => onChange({ ...draft,
      transports: [...draft.transports, { mode: "", departure: "", arrival: "", date: draft.startDate }] })}>Add transport</button></div>
    {draft.transports.map((transport, index) => <div className="nested-row" key={`transport-${index}`}>
      <label>Mode<input required value={transport.mode} onChange={event => updateRow("transports", index, { ...transport, mode: event.target.value })} /></label>
      <label>Departure<input required value={transport.departure} onChange={event => updateRow("transports", index, { ...transport, departure: event.target.value })} /></label>
      <label>Arrival<input required value={transport.arrival} onChange={event => updateRow("transports", index, { ...transport, arrival: event.target.value })} /></label>
      <label>Date<input required type="date" min={draft.startDate || undefined} max={draft.endDate || undefined} value={transport.date} onChange={event => updateRow("transports", index, { ...transport, date: event.target.value })} /></label>
      <button type="button" className="danger" onClick={() => removeRow("transports", index)}>Remove</button>
    </div>)}
    <div className="actions form-actions"><button className="primary" disabled={busy || destinations.length === 0}>{busy ? "Saving…" : "Save travel"}</button>
      <button type="button" onClick={onCancel}>Cancel</button></div>
  </form>;
}
