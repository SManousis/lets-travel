import * as React from "react";
import { api, errorMessage, newKey, type ApiContext, type Page } from "../../lib/api";
import type { ContractPage, Subscription, SubscriptionPayment, TravelOffer } from "../../lib/types";
import { Empty, Message } from "../../components/Shared";
import { cutoffDate, formatMoney, formatPrice, isActive, statusClass, statusLabels } from "./format";
import { PayForSubscription } from "./PayForSubscription";
import { paymentClass, paymentSummary } from "./paymentFormat";
import { TravelSearch } from "../search/TravelSearch";
import { ManagerProfile } from "../managers/ManagerProfile";
import { ReportForm } from "../reports/ReportForm";

/** Traveler view: browse bookable travels, subscribe, and manage "my subscriptions". */
export function TravelerTrips({ context }: { context: ApiContext }) {
  const [offers, setOffers] = React.useState<TravelOffer[] | null>(null);
  const [subscriptions, setSubscriptions] = React.useState<Subscription[] | null>(null);
  const [payments, setPayments] = React.useState<Map<string, SubscriptionPayment>>(new Map());
  const [paymentsUnavailable, setPaymentsUnavailable] = React.useState(false);
  const [payingFor, setPayingFor] = React.useState<string | null>(null);
  const [selected, setSelected] = React.useState<TravelOffer | null>(null);
  // Extra panel under the selected travel: its organiser's page or a report form.
  const [extra, setExtra] = React.useState<{ travelId: string; view: "organiser" | "report" } | null>(null);
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState("");
  const [notice, setNotice] = React.useState("");
  // One key per travel until the subscribe call succeeds, so a retry after a timeout cannot book twice.
  const subscribeKeys = React.useRef(new Map<string, string>());

  const load = React.useCallback(async () => {
    try {
      const [travelPage, mine] = await Promise.all([
        api<Page<TravelOffer>>(context, "/api/travels?page=0&size=50"),
        api<ContractPage<Subscription>>(context, "/api/subscriptions?page=0&size=100"),
      ]);
      setOffers(travelPage.items); setSubscriptions(mine.items);
    } catch (caught) { setError(errorMessage(caught)); }
    // Payment state lives in payment-service; if it is down the subscriptions still show.
    try {
      const paid = await api<SubscriptionPayment[]>(context, "/api/subscription-payments");
      setPayments(new Map(paid.map(payment => [payment.subscriptionId, payment])));
      setPaymentsUnavailable(false);
    } catch { setPaymentsUnavailable(true); }
  }, [context]);

  React.useEffect(() => { void load(); }, [load]);

  const activeFor = (travelId: string) => subscriptions?.find(item => item.travelId === travelId && isActive(item.status));

  async function subscribe(travel: TravelOffer) {
    setBusy(true); setError(""); setNotice("");
    const key = subscribeKeys.current.get(travel.id) ?? newKey();
    subscribeKeys.current.set(travel.id, key);
    try {
      await api<Subscription>(context, "/api/subscriptions", {
        method: "POST", body: { travelId: travel.id }, headers: { "Idempotency-Key": key },
      });
      subscribeKeys.current.delete(travel.id);
      setNotice(`Your place on ${travel.name} is reserved. Pay within 30 minutes to confirm it.`);
      await load();
    } catch (caught) { setError(errorMessage(caught)); }
    finally { setBusy(false); }
  }

  async function cancel(subscription: Subscription) {
    const paid = subscription.status === "CONFIRMED" ? " Your payment will be refunded in full." : "";
    if (!window.confirm(`Cancel your place on ${subscription.travelTitle}? This cannot be undone.${paid}`)) return;
    setBusy(true); setError(""); setNotice("");
    try {
      await api<Subscription>(context, `/api/subscriptions/${subscription.subscriptionId}/cancellation`, {
        method: "POST", headers: { "Idempotency-Key": newKey() },
      });
      setNotice(`Your subscription to ${subscription.travelTitle} is cancelled.${subscription.status === "CONFIRMED"
        ? " A full refund has been requested." : ""}`);
      await load();
    } catch (caught) { setError(errorMessage(caught)); }
    finally { setBusy(false); }
  }

  /** Opens a search result: the travel's full details come from travel-service, the source of truth. */
  async function open(travelId: string) {
    setError("");
    const known = offers?.find(travel => travel.id === travelId);
    if (known) { setSelected(known); return; }
    try { setSelected(await api<TravelOffer>(context, `/api/travels/${encodeURIComponent(travelId)}`)); }
    catch (caught) { setError(errorMessage(caught)); }
  }

  function toggleExtra(travelId: string, view: "organiser" | "report") {
    setExtra(extra?.travelId === travelId && extra.view === view ? null : { travelId, view });
  }

  const current = selected ? activeFor(selected.id) : undefined;

  return <div className="workflow-stack">
    {error && <Message kind="error">{error}</Message>}
    {notice && <Message kind="success">{notice}</Message>}
    <TravelSearch context={context} onOpen={travelId => void open(travelId)} />
    <div className="workspace-grid">
      <section className="workspace-card" aria-labelledby="offers-title">
        <div className="card-heading"><h3 id="offers-title">Available travels</h3>
          <button type="button" onClick={() => void load()}>Refresh</button></div>
        {!offers ? <p>Loading travels…</p> : offers.length === 0 ? <Empty>No travels are open for booking right now.</Empty>
          : <ul className="record-list">{offers.map(travel => <li key={travel.id}>
            <button type="button" className={selected?.id === travel.id ? "record selected" : "record"} onClick={() => setSelected(travel)}>
              <strong>{travel.name}</strong><span>{travel.startDate} – {travel.endDate}</span>
              <small>{formatPrice(travel.price, travel.currency)} · {travel.remainingCapacity} places left</small>
            </button></li>)}</ul>}
      </section>
      {selected && <section className="workspace-card" aria-labelledby="offer-detail-title">
        <h3 id="offer-detail-title">{selected.name}</h3>
        <p>{selected.description}</p>
        <dl className="account-details">
          <dt>Dates</dt><dd>{selected.startDate} to {selected.endDate} ({selected.durationDays} days)</dd>
          <dt>Price</dt><dd>{formatPrice(selected.price, selected.currency)}</dd>
          <dt>Places left</dt><dd>{selected.remainingCapacity} of {selected.capacity}</dd>
          <dt>Free cancellation</dt><dd>Until {cutoffDate(selected.startDate)} ({selected.timeZone} time)</dd>
        </dl>
        {current ? <div className="actions">
          <span className={statusClass(current.status)}>{statusLabels[current.status]}</span>
          {current.travelerMayCancel && <button type="button" className="danger" disabled={busy}
            onClick={() => void cancel(current)}>Cancel subscription</button>}
        </div> : <div className="actions"><button type="button" className="primary" disabled={busy || !selected.bookable}
          onClick={() => void subscribe(selected)}>{busy ? "Reserving…" : "Subscribe"}</button></div>}
        <div className="actions">
          <button type="button" onClick={() => toggleExtra(selected.id, "organiser")}>About the organiser</button>
          <button type="button" onClick={() => toggleExtra(selected.id, "report")}>Report this travel</button>
        </div>
        {extra?.travelId === selected.id && extra.view === "organiser"
          && <ManagerProfile context={context} managerId={selected.managerId} />}
        {extra?.travelId === selected.id && extra.view === "report" && <ReportForm context={context}
          target={{ type: "TRAVEL", id: selected.id, name: selected.name }} onClose={() => setExtra(null)} />}
      </section>}
    </div>
    <section className="workspace-card" aria-labelledby="my-subscriptions-title">
      <div className="card-heading"><h3 id="my-subscriptions-title">My subscriptions</h3>
        <button type="button" onClick={() => void load()}>Refresh</button></div>
      {paymentsUnavailable && <Message>Payment status is unavailable right now. Try Refresh in a moment.</Message>}
      {!subscriptions ? <p>Loading subscriptions…</p> : subscriptions.length === 0 ? <Empty>You have no subscriptions yet.</Empty>
        : <ul className="record-list">{subscriptions.map(item => {
          const payment = payments.get(item.subscriptionId);
          const summary = paymentsUnavailable ? null : paymentSummary(payment, item.status);
          const canPay = item.status === "PENDING_PAYMENT" && !payment && !paymentsUnavailable;
          return <li key={item.subscriptionId}><div className="record-row">
            <div><strong>{item.travelTitle}</strong>
              <span>{item.startDate} – {item.endDate} · {formatMoney(item.price.amountMinor, item.price.currency)}</span>
              <small className={statusClass(item.status)}>{statusLabels[item.status]}</small>
              {summary && <small className={payment ? paymentClass(payment.state) : undefined}>{summary}</small>}
              {canPay && item.reservationExpiresAt && <small>Pay by {new Date(item.reservationExpiresAt).toLocaleTimeString()} or the place is released.</small>}
              {isActive(item.status) && <small>{item.travelerMayCancel
                ? `Free cancellation until ${item.cancellationCutoff} (${item.timeZone} time), full refund if paid`
                : `Cancellation closed on ${item.cancellationCutoff} (3 days before the start)`}</small>}
              {item.cancellationReason && <small>Reason: {item.cancellationReason}</small>}
            </div>
            <div className="actions">
              {canPay && payingFor !== item.subscriptionId && <button type="button" className="primary"
                onClick={() => setPayingFor(item.subscriptionId)}>Pay now</button>}
              {item.travelerMayCancel && <button type="button" className="danger" disabled={busy}
                onClick={() => void cancel(item)}>Cancel</button>}
            </div></div>
            {payingFor === item.subscriptionId && <PayForSubscription context={context} subscription={item}
              onFinished={() => void load()} />}
          </li>;
        })}</ul>}
    </section>
  </div>;
}
