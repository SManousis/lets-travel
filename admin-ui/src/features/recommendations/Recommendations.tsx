import * as React from "react";
import { api, ApiError, errorMessage, type ApiContext } from "../../lib/api";
import { Empty, Message } from "../../components/Shared";

type Reason = { field: "DESTINATION" | "ACTIVITY_CATEGORY" | "TRANSPORT_MODE" | "ACCOMMODATION_CATEGORY"; value: string };
type Item = {
  travelId: string; title: string; startDate: string; endDate: string;
  price: { amountMinor: number; currency: string } | null; score: number; reasons: Reason[];
};
type Result = { strategy: "PERSONALIZED" | "POPULAR"; items: Item[] };

function readable(value: string): string {
  const lower = value.toLowerCase().replaceAll("_", " ");
  return lower.charAt(0).toUpperCase() + lower.slice(1);
}

/** Explains a match using only the traveler's own past trips. */
export function reasonText(reason: Reason): string {
  switch (reason.field) {
    case "DESTINATION": return `You travelled to ${readable(reason.value)}`;
    case "ACTIVITY_CATEGORY": return `You enjoyed ${readable(reason.value).toLowerCase()} activities`;
    case "TRANSPORT_MODE": return `You liked travelling by ${readable(reason.value).toLowerCase()}`;
    case "ACCOMMODATION_CATEGORY": return `You stayed in a ${readable(reason.value).toLowerCase()}`;
  }
}

function money(price: Item["price"]): string {
  if (!price) return "";
  const digits = new Intl.NumberFormat("en", { style: "currency", currency: price.currency }).resolvedOptions().maximumFractionDigits ?? 2;
  return new Intl.NumberFormat(undefined, { style: "currency", currency: price.currency })
    .format(price.amountMinor / 10 ** digits);
}

/** Travels picked for the traveler from their own trips and ratings, or popular ones for newcomers. */
export function Recommendations({ context }: { context: ApiContext }) {
  const [result, setResult] = React.useState<Result | null>(null);
  const [error, setError] = React.useState("");
  const [unavailable, setUnavailable] = React.useState(false);

  const load = React.useCallback(async () => {
    setError(""); setUnavailable(false);
    try { setResult(await api<Result>(context, "/api/recommendations?limit=6")); }
    catch (caught) {
      // Recommendations are optional: when the graph is down the rest of the page keeps working.
      if (caught instanceof ApiError && caught.status === 503) setUnavailable(true);
      else setError(errorMessage(caught));
    }
  }, [context]);

  React.useEffect(() => { void load(); }, [load]);

  const personalized = result?.strategy === "PERSONALIZED";
  return <section className="workspace-card" aria-labelledby="recommendations-title">
    <div className="card-heading"><div><h3 id="recommendations-title">{personalized ? "Recommended for you" : "Popular upcoming travels"}</h3>
      <p className="record-meta">{personalized ? "Based on the trips you took and how you rated them."
        : "Book and rate a trip to get recommendations picked for you."}</p></div>
      <button type="button" onClick={() => void load()}>Refresh</button></div>
    {error && <Message kind="error">{error}</Message>}
    {unavailable ? <Message>Recommendations are not available right now. Search and browsing still work.</Message>
      : !result ? <p>Finding travels for you…</p>
        : result.items.length === 0 ? <Empty>No upcoming travels to suggest right now.</Empty>
          : <ul className="record-list">{result.items.map(item => <li className="record-row" key={item.travelId}>
            <div><strong>{item.title}</strong>
              <small>{item.startDate} to {item.endDate}{item.price ? ` · ${money(item.price)}` : ""}</small>
              {item.reasons.length > 0 && <ul className="reason-list" aria-label={`Why ${item.title} is recommended`}>
                {item.reasons.map(reason => <li key={`${reason.field}:${reason.value}`}>{reasonText(reason)}</li>)}</ul>}
            </div>
          </li>)}</ul>}
  </section>;
}
