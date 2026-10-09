import * as React from "react";
import type { AccommodationCategory, ActivityCategory, Destination, Travel } from "../../lib/types";
import { Message } from "../../components/Shared";

type StopDraft = { destinationId: string; arrivalDate: string; departureDate: string };
type ActivityDraft = { name: string; description: string; date: string; category: ActivityCategory | "" };
type StayDraft = { name: string; address: string; checkIn: string; checkOut: string; category: AccommodationCategory | "" };

// Categories and transport modes are what personalized recommendations match on.
const activityCategories: Record<ActivityCategory, string> = { CULTURE: "Culture", NATURE: "Nature", ADVENTURE: "Adventure",
  BEACH: "Beach", FOOD: "Food", NIGHTLIFE: "Nightlife", WELLNESS: "Wellness", SPORT: "Sport" };
const stayCategories: Record<AccommodationCategory, string> = { HOTEL: "Hotel", HOSTEL: "Hostel", APARTMENT: "Apartment",
  RESORT: "Resort", CAMPING: "Camping" };
const transportModes = ["PLANE", "TRAIN", "BUS", "CAR", "BOAT"];
type TransportDraft = { mode: string; departure: string; arrival: string; date: string };

export type TravelDraft = {
  name: string; description: string; startDate: string; endDate: string; capacity: string; price: string;
  currency: string; timeZone: string; managerId: string; stops: StopDraft[];
  activities: ActivityDraft[]; accommodations: StayDraft[]; transports: TransportDraft[];
};

export function blankTravel(destinationId = ""): TravelDraft {
  return { name: "", description: "", startDate: "", endDate: "", capacity: "10", price: "", currency: "EUR",
    timeZone: "UTC", managerId: "", stops: [{ destinationId, arrivalDate: "", departureDate: "" }],
    activities: [], accommodations: [], transports: [] };
}

export function fromTravel(travel: Travel): TravelDraft {
  return { name: travel.name, description: travel.description, startDate: travel.startDate, endDate: travel.endDate,
    capacity: String(travel.capacity), price: String(travel.price), currency: travel.currency, timeZone: travel.timeZone,
    managerId: travel.managerId,
    stops: [...travel.stops].sort((a, b) => a.position - b.position).map(stop => ({
      destinationId: stop.destinationId, arrivalDate: stop.arrivalDate ?? "", departureDate: stop.departureDate ?? "",
    })),
    activities: travel.activities.map(activity => ({ name: activity.name, description: activity.description ?? "", date: activity.date,
      category: activity.category ?? "" })),
    accommodations: travel.accommodations.map(stay => ({ name: stay.name, address: stay.address, checkIn: stay.checkIn,
      checkOut: stay.checkOut, category: stay.category ?? "" })),
    transports: travel.transports.map(transport => ({ mode: transport.mode, departure: transport.departure, arrival: transport.arrival, date: transport.date })),
  };
}

export function travelPayload(draft: TravelDraft) {
  return { name: draft.name.trim(), description: draft.description.trim(), startDate: draft.startDate, endDate: draft.endDate,
    capacity: Number(draft.capacity), price: draft.price, currency: draft.currency.trim().toUpperCase(),
    timeZone: draft.timeZone.trim() || "UTC", ...(draft.managerId ? { managerId: draft.managerId } : {}),
    stops: draft.stops.map((stop, position) => ({ destinationId: stop.destinationId, position,
      arrivalDate: stop.arrivalDate || null, departureDate: stop.departureDate || null })),
    activities: draft.activities.map(activity => ({ ...activity, category: activity.category || null })),
    accommodations: draft.accommodations.map(stay => ({ ...stay, category: stay.category || null })),
    transports: draft.transports };
}

export function ItineraryEditor({ draft, onChange, destinations, onSubmit, onCancel, busy, error, managers, booked = 0 }:
  { draft: TravelDraft; onChange: (next: TravelDraft) => void; destinations: Destination[];
    onSubmit: (event: React.FormEvent) => void; onCancel: () => void; busy: boolean; error: string;
    /** Only admins choose the organiser; managers always own what they create. */
    managers?: { id: string; name: string }[];
    /** Places already taken. Once travelers have booked, the terms they paid for stay fixed (docs/roles.md). */
    booked?: number }) {
  const locked = booked > 0;
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
      <label>Start date<input required type="date" disabled={locked} aria-describedby={locked ? "booked-terms" : undefined} value={draft.startDate} onChange={event => onChange({ ...draft, startDate: event.target.value })} /></label>
      <label>End date<input required type="date" disabled={locked} aria-describedby={locked ? "booked-terms" : undefined} min={draft.startDate || undefined} value={draft.endDate} onChange={event => onChange({ ...draft, endDate: event.target.value })} /></label>
      <label>Places<input required type="number" min={Math.max(1, booked)} step="1" value={draft.capacity} onChange={event => onChange({ ...draft, capacity: event.target.value })} /></label>
      <label>Price per traveler<input required type="number" disabled={locked} aria-describedby={locked ? "booked-terms" : undefined} min="0" step="0.01" value={draft.price} onChange={event => onChange({ ...draft, price: event.target.value })} /></label>
      <label>Currency<input required disabled={locked} aria-describedby={locked ? "booked-terms" : undefined} pattern="[A-Za-z]{3}" maxLength={3} value={draft.currency} onChange={event => onChange({ ...draft, currency: event.target.value })} /></label>
      <label>Time zone<input disabled={locked} aria-describedby={locked ? "booked-terms" : undefined} maxLength={64} value={draft.timeZone} placeholder="Europe/Athens" onChange={event => onChange({ ...draft, timeZone: event.target.value })} /></label>
      {managers && <label>Organiser<select required value={draft.managerId} onChange={event => onChange({ ...draft, managerId: event.target.value })}>
        <option value="">Choose a travel manager</option>
        {managers.map(manager => <option key={manager.id} value={manager.id}>{manager.name}</option>)}
      </select></label>}
    </div>
    {locked && <Message kind="info"><span id="booked-terms">{`${booked} ${booked === 1 ? "place is" : "places are"} booked, so the dates, `
      + `price, currency and time zone travelers paid for can no longer change. You can still add places (at least ${booked}) `
      + "and update the description and itinerary details."}</span></Message>}
    <label className="wide-field">Description<textarea required maxLength={4000} value={draft.description}
      onChange={event => onChange({ ...draft, description: event.target.value })} /></label>
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
      activities: [...draft.activities, { name: "", description: "", date: draft.startDate, category: "" }] })}>Add activity</button></div>
    {draft.activities.map((activity, index) => <div className="nested-row" key={`activity-${index}`}>
      <label>Name<input required value={activity.name} onChange={event => updateRow("activities", index, { ...activity, name: event.target.value })} /></label>
      <label>Description<input value={activity.description} onChange={event => updateRow("activities", index, { ...activity, description: event.target.value })} /></label>
      <label>Kind<select value={activity.category} onChange={event => updateRow("activities", index, { ...activity, category: event.target.value as ActivityDraft["category"] })}>
        <option value="">Not set</option>
        {(Object.keys(activityCategories) as ActivityCategory[]).map(value => <option key={value} value={value}>{activityCategories[value]}</option>)}
      </select></label>
      <label>Date<input required type="date" min={draft.startDate || undefined} max={draft.endDate || undefined} value={activity.date} onChange={event => updateRow("activities", index, { ...activity, date: event.target.value })} /></label>
      <button type="button" className="danger" onClick={() => removeRow("activities", index)}>Remove</button>
    </div>)}
    <div className="subsection-heading"><h4>Accommodation</h4><button type="button" onClick={() => onChange({ ...draft,
      accommodations: [...draft.accommodations, { name: "", address: "", checkIn: draft.startDate, checkOut: draft.endDate, category: "" }] })}>Add stay</button></div>
    {draft.accommodations.map((stay, index) => <div className="nested-row" key={`stay-${index}`}>
      <label>Name<input required value={stay.name} onChange={event => updateRow("accommodations", index, { ...stay, name: event.target.value })} /></label>
      <label>Address<input required value={stay.address} onChange={event => updateRow("accommodations", index, { ...stay, address: event.target.value })} /></label>
      <label>Type<select value={stay.category} onChange={event => updateRow("accommodations", index, { ...stay, category: event.target.value as StayDraft["category"] })}>
        <option value="">Not set</option>
        {(Object.keys(stayCategories) as AccommodationCategory[]).map(value => <option key={value} value={value}>{stayCategories[value]}</option>)}
      </select></label>
      <label>Check in<input required type="date" min={draft.startDate || undefined} max={draft.endDate || undefined} value={stay.checkIn} onChange={event => updateRow("accommodations", index, { ...stay, checkIn: event.target.value })} /></label>
      <label>Check out<input required type="date" min={draft.startDate || undefined} max={draft.endDate || undefined} value={stay.checkOut} onChange={event => updateRow("accommodations", index, { ...stay, checkOut: event.target.value })} /></label>
      <button type="button" className="danger" onClick={() => removeRow("accommodations", index)}>Remove</button>
    </div>)}
    <datalist id="transport-modes">{transportModes.map(mode => <option key={mode} value={mode} />)}</datalist>
    <div className="subsection-heading"><h4>Transportation</h4><button type="button" onClick={() => onChange({ ...draft,
      transports: [...draft.transports, { mode: "", departure: "", arrival: "", date: draft.startDate }] })}>Add transport</button></div>
    {draft.transports.map((transport, index) => <div className="nested-row" key={`transport-${index}`}>
      <label>Mode<input required list="transport-modes" value={transport.mode} onChange={event => updateRow("transports", index, { ...transport, mode: event.target.value })} /></label>
      <label>Departure<input required value={transport.departure} onChange={event => updateRow("transports", index, { ...transport, departure: event.target.value })} /></label>
      <label>Arrival<input required value={transport.arrival} onChange={event => updateRow("transports", index, { ...transport, arrival: event.target.value })} /></label>
      <label>Date<input required type="date" min={draft.startDate || undefined} max={draft.endDate || undefined} value={transport.date} onChange={event => updateRow("transports", index, { ...transport, date: event.target.value })} /></label>
      <button type="button" className="danger" onClick={() => removeRow("transports", index)}>Remove</button>
    </div>)}
    <div className="actions form-actions"><button className="primary" disabled={busy || destinations.length === 0}>{busy ? "Saving…" : "Save travel"}</button>
      <button type="button" onClick={onCancel}>Cancel</button></div>
  </form>;
}
