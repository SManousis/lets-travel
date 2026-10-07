import * as React from "react";
import { ApiError, api, autocompletePath, errorMessage, searchPath, type ApiContext, type Page } from "../../lib/api";
import type { SearchFilters, SearchPage, Suggestion, TravelHit, TravelOffer } from "../../lib/types";
import { Empty, Message } from "../../components/Shared";
import { formatPrice } from "../subscriptions/format";

/** Wait this long after the last keystroke before asking for suggestions. */
export const AUTOCOMPLETE_DELAY_MS = 250;
const PAGE_SIZE = 10;
const noFilters: SearchFilters = {
  country: "", transportMode: "", startFrom: "", startTo: "", minPrice: "", maxPrice: "", currency: "EUR", sort: "",
};
const typeLabels: Record<Suggestion["type"], string> = {
  TRAVEL: "Travel", DESTINATION: "Destination", COUNTRY: "Country", ACTIVITY: "Activity",
};

type Results =
  | { mode: "search"; page: SearchPage }
  /** Elasticsearch is down: the plain travel list from travel-service, matched by name here in the browser. */
  | { mode: "browse"; items: TravelOffer[] };

/**
 * Search box with debounced autocomplete, filters that match the index, and a results list.
 * Opening a result hands its ID to the parent, which shows the travel and the Subscribe button.
 */
export function TravelSearch({ context, onOpen }: { context: ApiContext; onOpen: (travelId: string) => void }) {
  const [text, setText] = React.useState("");
  const [filters, setFilters] = React.useState<SearchFilters>(noFilters);
  const [suggestions, setSuggestions] = React.useState<Suggestion[]>([]);
  const [results, setResults] = React.useState<Results | null>(null);
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState("");
  // Only the answer to the latest keystroke may update the suggestions.
  const latestTyping = React.useRef(0);
  // A picked suggestion fills the box; that change should not open the suggestions again.
  const filledBySuggestion = React.useRef(false);

  React.useEffect(() => {
    const typed = text.trim();
    const request = ++latestTyping.current;
    if (filledBySuggestion.current || typed.length < 2) { filledBySuggestion.current = false; setSuggestions([]); return; }
    const timer = window.setTimeout(() => {
      api<{ items: Suggestion[] }>(context, autocompletePath(typed))
        .then(found => { if (request === latestTyping.current) setSuggestions(found.items); })
        .catch(() => { if (request === latestTyping.current) setSuggestions([]); });
    }, AUTOCOMPLETE_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [text, context]);

  const run = React.useCallback(async (query: string, page: number) => {
    setBusy(true); setError(""); setSuggestions([]);
    latestTyping.current++;
    const price = filters.minPrice || filters.maxPrice;
    const params: Record<string, string> = {
      country: filters.country, transportMode: filters.transportMode, startFrom: filters.startFrom,
      startTo: filters.startTo, minPrice: filters.minPrice, maxPrice: filters.maxPrice,
      currency: price ? filters.currency : "", sort: filters.sort,
    };
    try {
      setResults({ mode: "search", page: await api<SearchPage>(context, searchPath(query, params, page, PAGE_SIZE)) });
    } catch (caught) {
      // 503 from search-service (Elasticsearch down), or the gateway cannot reach search-service at all.
      if (caught instanceof ApiError && (caught.status === 0 || caught.status >= 502)) {
        try {
          const all = await api<Page<TravelOffer>>(context, "/api/travels?page=0&size=100");
          const needle = query.trim().toLowerCase();
          setResults({ mode: "browse", items: all.items.filter(travel => !needle
            || `${travel.name} ${travel.description}`.toLowerCase().includes(needle)) });
        } catch (fallback) { setError(errorMessage(fallback)); setResults(null); }
      } else { setError(errorMessage(caught)); }
    } finally { setBusy(false); }
  }, [context, filters]);

  function submit(event: React.FormEvent) {
    event.preventDefault();
    void run(text, 0);
  }

  function choose(suggestion: Suggestion) {
    setSuggestions([]);
    latestTyping.current++;
    if (suggestion.type === "TRAVEL" && suggestion.travelId) { onOpen(suggestion.travelId); return; }
    filledBySuggestion.current = true;
    setText(suggestion.text);
    void run(suggestion.text, 0);
  }

  const set = (name: keyof SearchFilters) => (event: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setFilters(current => ({ ...current, [name]: event.target.value }));

  return <section className="workspace-card" aria-labelledby="search-title">
    <div className="card-heading"><h3 id="search-title">Search travels</h3></div>
    <form className="search-form" role="search" onSubmit={submit}>
      <div className="search-box">
        <label>Search by place, activity, hotel or travel name
          <input type="search" value={text} maxLength={100} autoComplete="off" placeholder="Try Crete, kayaking or train"
            aria-autocomplete="list" aria-controls="search-suggestions" aria-expanded={suggestions.length > 0}
            onChange={event => setText(event.target.value)}
            onKeyDown={event => { if (event.key === "Escape") setSuggestions([]); }} />
        </label>
        {suggestions.length > 0 && <ul id="search-suggestions" className="suggestions" role="listbox" aria-label="Suggestions">
          {suggestions.map(item => <li key={`${item.type}:${item.travelId ?? item.text}`} role="option" aria-selected="false">
            <button type="button" onClick={() => choose(item)}>
              <strong>{item.text}</strong><small>{typeLabels[item.type]} · {item.context}</small>
            </button></li>)}
        </ul>}
      </div>
      <div className="field-grid search-filters">
        <label>Country<input value={filters.country} maxLength={60} onChange={set("country")} /></label>
        <label>Transport
          <select value={filters.transportMode} onChange={set("transportMode")}>
            <option value="">Any</option>
            {["PLANE", "TRAIN", "BUS", "FERRY", "CAR", "BOAT"].map(mode => <option key={mode} value={mode}>{mode}</option>)}
          </select></label>
        <label>Starts from<input type="date" value={filters.startFrom} onChange={set("startFrom")} /></label>
        <label>Starts by<input type="date" value={filters.startTo} onChange={set("startTo")} /></label>
        <label>Min price<input type="number" min="0" value={filters.minPrice} onChange={set("minPrice")} /></label>
        <label>Max price<input type="number" min="0" value={filters.maxPrice} onChange={set("maxPrice")} /></label>
        <label>Currency<input value={filters.currency} maxLength={3} onChange={set("currency")} /></label>
        <label>Sort
          <select value={filters.sort} onChange={set("sort")}>
            <option value="">Best match</option>
            <option value="START_DATE">Start date</option>
            <option value="PRICE_ASC">Price, low to high</option>
            <option value="PRICE_DESC">Price, high to low</option>
          </select></label>
      </div>
      <div className="actions"><button className="primary" disabled={busy}>{busy ? "Searching…" : "Search"}</button>
        <button type="button" onClick={() => { setText(""); setFilters(noFilters); setResults(null); }}>Clear</button></div>
    </form>
    {error && <Message kind="error">{error}</Message>}
    {results?.mode === "browse" && <>
      <Message>Search is temporarily limited. Showing available travels whose name or description matches.</Message>
      {results.items.length === 0 ? <Empty>No available travel matches.</Empty>
        : <ul className="record-list">{results.items.map(travel => <li key={travel.id}>
          <ResultRow title={travel.name} dates={`${travel.startDate} – ${travel.endDate}`}
            details={`${formatPrice(travel.price, travel.currency)} · ${travel.remainingCapacity} places left`}
            onOpen={() => onOpen(travel.id)} /></li>)}</ul>}
    </>}
    {results?.mode === "search" && <SearchResults page={results.page} onOpen={onOpen}
      onPage={page => void run(text, page)} />}
  </section>;
}

function SearchResults({ page, onOpen, onPage }: { page: SearchPage; onOpen: (id: string) => void; onPage: (page: number) => void }) {
  if (page.items.length === 0) return <Empty>No travels match. Try fewer words or filters.</Empty>;
  return <>
    <p className="record-meta" role="status">{page.totalElements} {page.totalElements === 1 ? "travel" : "travels"} found</p>
    <ul className="record-list">{page.items.map((hit: TravelHit) => <li key={hit.travelId}>
      <ResultRow title={hit.title} dates={`${hit.startDate} – ${hit.endDate} · ${hit.destinations.join(", ")}`}
        details={`${formatPrice(hit.price, hit.currency)} · ${hit.remainingPlaces} places left${hit.managerName ? ` · with ${hit.managerName}` : ""}`}
        highlight={hit.highlights[0]} onOpen={() => onOpen(hit.travelId)} /></li>)}</ul>
    {page.totalPages > 1 && <div className="page-controls" aria-label="Result pages">
      <button type="button" disabled={page.page === 0} onClick={() => onPage(page.page - 1)}>Previous</button>
      <span>Page {page.page + 1} of {page.totalPages}</span>
      <button type="button" disabled={page.page + 1 >= page.totalPages} onClick={() => onPage(page.page + 1)}>Next</button>
    </div>}
  </>;
}

function ResultRow({ title, dates, details, highlight, onOpen }: {
  title: string; dates: string; details: string; highlight?: string; onOpen: () => void;
}) {
  return <div className="record-row">
    <div><strong>{title}</strong><span>{dates}</span><small>{details}</small>
      {highlight && <small className="highlight">“{highlight}”</small>}</div>
    <div className="actions"><button type="button" onClick={onOpen} aria-label={`View and subscribe: ${title}`}>View and subscribe</button></div>
  </div>;
}
