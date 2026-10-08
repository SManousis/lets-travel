import * as React from "react";
import { api, type ApiContext } from "../../lib/api";
import type {
  AdminDashboard, DashboardPage, ManagerDashboard, ManagerRanking, Money, MonthlyIncome, RatingSummary, Reconciliation,
  TravelSummary, TravelerDashboard,
} from "../../lib/types";
import { useResource } from "../../lib/useResource";
import { Empty, Loaded, Stat } from "../../components/Shared";
import { formatMoney } from "../subscriptions/format";

// The rules behind every figure are in docs/dashboards.md.

export function money(amounts: Money[]): string {
  return amounts.length === 0 ? formatMoney(0, "EUR") : amounts.map(item => formatMoney(item.amountMinor, item.currency)).join(" + ");
}

export function rating(summary: RatingSummary): string {
  return summary.average === null || summary.count === 0 ? "No ratings yet"
    : `${summary.average.toFixed(1)} of 5 (${summary.count} ${summary.count === 1 ? "rating" : "ratings"})`;
}

const percent = (value: number) => `${Math.round(value * 100)}%`;
const asOf = (at: string) => <p className="record-meta">Figures as of {new Date(at).toLocaleString()}. Months are UTC calendar months.</p>;
const providers: Record<string, string> = { STRIPE: "Card (Stripe)", PAYPAL: "PayPal", TEST: "Test card" };

function MonthsPicker({ months, onChange }: { months: number; onChange: (months: number) => void }) {
  return <label className="inline-filter">Period
    <select value={months} onChange={event => onChange(Number(event.target.value))}>
      {[3, 6, 12, 24].map(value => <option key={value} value={value}>Last {value} months</option>)}
    </select></label>;
}

function IncomeTable({ months, caption }: { months: MonthlyIncome[]; caption: string }) {
  return <div className="table-scroll" tabIndex={0} role="region" aria-label={caption}>
    <table><caption>{caption}</caption>
      <thead><tr><th scope="col">Month</th><th scope="col">Net income</th></tr></thead>
      <tbody>{months.map(month => <tr key={month.month}><th scope="row">{month.month}</th><td>{money(month.amounts)}</td></tr>)}</tbody>
    </table></div>;
}

function TravelTable({ travels, caption, showManager = false }: { travels: TravelSummary[]; caption: string; showManager?: boolean }) {
  if (travels.length === 0) return <Empty>No travels with travelers yet.</Empty>;
  return <div className="table-scroll" tabIndex={0} role="region" aria-label={caption}>
    <table><caption>{caption}</caption>
      <thead><tr><th scope="col">Travel</th>{showManager && <th scope="col">Organiser</th>}<th scope="col">Dates</th>
        <th scope="col">Travelers</th><th scope="col">Cancellations</th><th scope="col">Income</th><th scope="col">Rating</th></tr></thead>
      <tbody>{travels.map(travel => <tr key={travel.travelId}><th scope="row">{travel.title}</th>
        {showManager && <td>{travel.managerDisplayName ?? "Unknown"}</td>}
        <td>{travel.startDate} – {travel.endDate}</td><td>{travel.travelers}</td><td>{travel.cancellations}</td>
        <td>{money(travel.income)}</td><td>{rating(travel.rating)}</td></tr>)}</tbody>
    </table></div>;
}

function RankingTable({ rankings, caption }: { rankings: ManagerRanking[]; caption: string }) {
  if (rankings.length === 0) return <Empty>No manager has activity in the last 12 months.</Empty>;
  return <div className="table-scroll" tabIndex={0} role="region" aria-label={caption}>
    <table><caption>{caption}</caption>
      <thead><tr><th scope="col">Rank</th><th scope="col">Manager</th><th scope="col">Score</th><th scope="col">Rating</th>
        <th scope="col">Income</th><th scope="col">Completed travels</th><th scope="col">Cancellation rate</th>
        <th scope="col">Upheld reports</th></tr></thead>
      <tbody>{rankings.map(row => <tr key={row.managerId}><td>{row.rank}</td><th scope="row">{row.displayName}</th>
        <td>{row.score.toFixed(3)}</td>
        <td>{rating(row.rating)}<small> ({row.components.rating.toFixed(2)})</small></td>
        <td>{money(row.income)}<small> ({row.components.income.toFixed(2)})</small></td>
        <td>{row.organizedTravels}<small> ({row.components.completedTravels.toFixed(2)})</small></td>
        <td>{percent(row.components.cancellationRate)}</td>
        <td>{row.upheldReports}<small> ({row.components.upheldReports.toFixed(2)})</small></td></tr>)}</tbody>
    </table></div>;
}

/** Every role: the caller's own trips, cancellations, feedback and reports. */
export function MyStatistics({ context }: { context: ApiContext }) {
  const load = React.useCallback((signal: AbortSignal) => api<TravelerDashboard>(context, "/api/dashboards/me", { signal }), [context]);
  const [resource, retry] = useResource(load);
  return <section className="workspace-card" aria-labelledby="my-stats-title">
    <div className="card-heading"><h2 id="my-stats-title">My statistics</h2><button type="button" onClick={retry}>Refresh</button></div>
    <Loaded resource={resource} retry={retry} label="statistics">{data => <>
      <dl className="stat-grid">
        <Stat label="Completed trips" value={data.completedTrips} />
        <Stat label="Upcoming trips" value={data.upcomingTrips} />
        <Stat label="Cancellations" value={data.cancellations} />
        <Stat label="Feedback given" value={data.feedbackGiven} />
        <Stat label="Reports filed" value={data.reportsFiled} />
        <Stat label="Upheld reports about you" value={data.upheldReportsAgainstMe} />
      </dl>
      <h3>Preferred payment methods</h3>
      {data.preferredPaymentMethods.length === 0 ? <Empty>No completed payments yet.</Empty>
        : <ol className="plain-list">{data.preferredPaymentMethods.map(item => <li key={item.provider}>
          {providers[item.provider] ?? item.provider}: {item.payments} {item.payments === 1 ? "payment" : "payments"}</li>)}</ol>}
      {asOf(data.asOf)}
    </>}</Loaded>
  </section>;
}

/** Travel managers: income, trips and satisfaction for their own travels. */
export function ManagerAnalytics({ context }: { context: ApiContext }) {
  const [months, setMonths] = React.useState(6);
  const load = React.useCallback((signal: AbortSignal) =>
    api<ManagerDashboard>(context, `/api/dashboards/manager?months=${months}`, { signal }), [context, months]);
  const [resource, retry] = useResource(load);
  return <section className="workspace-card" aria-labelledby="analytics-title">
    <div className="card-heading"><h2 id="analytics-title">My analytics</h2><MonthsPicker months={months} onChange={setMonths} /></div>
    <Loaded resource={resource} retry={retry} label="analytics">{data => <>
      <dl className="stat-grid">
        <Stat label="Trips with travelers" value={data.totalTrips} />
        <Stat label="Upcoming trips" value={data.upcomingTrips} />
        <Stat label="Travelers" value={data.totalTravelers} />
        <Stat label="Cancellation rate" value={percent(data.cancellationRate)} hint="Paid places later refunded" />
        <Stat label="Rating" value={rating(data.rating)} />
      </dl>
      <IncomeTable months={data.incomeByMonth} caption="Net income by month" />
      <h3>My travels</h3>
      <TravelTable travels={data.travels} caption="Travelers, cancellations, income and rating per travel" />
      {asOf(data.asOf)}
    </>}</Loaded>
  </section>;
}

/** Admins: platform-wide figures and whether dashboard income agrees with payment-service. */
export function AdminOverview({ context }: { context: ApiContext }) {
  const [months, setMonths] = React.useState(6);
  const load = React.useCallback((signal: AbortSignal) =>
    api<AdminDashboard>(context, `/api/dashboards/admin?months=${months}&top=5`, { signal }), [context, months]);
  const [resource, retry] = useResource(load);
  return <div className="workflow-stack">
    <section className="workspace-card" aria-labelledby="overview-title">
      <div className="card-heading"><h2 id="overview-title">Overview</h2><MonthsPicker months={months} onChange={setMonths} /></div>
      <Loaded resource={resource} retry={retry} label="platform figures">{data => <>
        <dl className="stat-grid">
          <Stat label="Organised travels" value={data.organizedTravels} hint="Started in the period, with travelers" />
          <Stat label="Active travelers" value={data.activeTravelers} />
          <Stat label="Average rating" value={rating(data.averageRating)} />
          <Stat label="Open reports" value={data.openReports} />
        </dl>
        <IncomeTable months={data.incomeByMonth} caption="Platform net income by month" />
        <h3>Top managers</h3>
        <RankingTable rankings={data.topManagers} caption="Best performing travel managers" />
        <h3>Best rated travels</h3>
        <TravelTable travels={data.topTravels} caption="Best rated travels" showManager />
        {asOf(data.asOf)}
      </>}</Loaded>
    </section>
    <IncomeCheck context={context} />
  </div>;
}

function IncomeCheck({ context }: { context: ApiContext }) {
  const load = React.useCallback((signal: AbortSignal) =>
    api<Reconciliation[]>(context, "/api/dashboards/admin/reconciliation", { signal }), [context]);
  const [resource, retry] = useResource(load);
  return <section className="workspace-card" aria-labelledby="income-check-title">
    <div className="card-heading"><div><h3 id="income-check-title">Income check</h3>
      <p className="record-meta">Dashboard income compared with the payments payment-service holds.</p></div>
      <button type="button" onClick={retry}>Check again</button></div>
    <Loaded resource={resource} retry={retry} label="payment totals">{rows => rows.length === 0 ? <Empty>No payments yet.</Empty>
      : <ul className="record-list">{rows.map(row => <li className="record-row" key={row.currency}>
        <div><strong>{row.currency}: {row.status === "MATCH" ? "matches" : "differs"}</strong>
          <span>Subscriptions {formatMoney(row.subscriptionNetMinor, row.currency)} · payments {formatMoney(row.paymentsSucceededMinor, row.currency)}</span>
          {row.status === "DIFFERENT" && <small>Difference {formatMoney(row.difference, row.currency)}. A short-lived difference is normal while
            a payment or refund is still being delivered; check again in a minute.</small>}
          {row.refundPendingMinor > 0 && <small>Refunds in progress: {formatMoney(row.refundPendingMinor, row.currency)}</small>}</div>
        <span className={row.status === "MATCH" ? "status active" : "status pending"}>{row.status === "MATCH" ? "OK" : "Check"}</span>
      </li>)}</ul>}</Loaded>
  </section>;
}

/** Admins: every ranked manager with the normalized score components (docs/dashboards.md section 4). */
export function ManagerRankings({ context }: { context: ApiContext }) {
  const [page, setPage] = React.useState(0);
  const load = React.useCallback((signal: AbortSignal) =>
    api<DashboardPage<ManagerRanking>>(context, `/api/dashboards/admin/managers?page=${page}&size=20`, { signal }), [context, page]);
  const [resource, retry] = useResource(load);
  return <section className="workspace-card" aria-labelledby="rankings-title">
    <div className="card-heading"><div><h2 id="rankings-title">Manager rankings</h2>
      <p className="record-meta">Last 12 months. Score = 0.40 rating + 0.30 income + 0.20 completed travels + 0.10 (1 − cancellation rate)
        − 0.15 upheld reports, each scaled against the best manager (shown in brackets).</p></div>
      <button type="button" onClick={retry}>Refresh</button></div>
    <Loaded resource={resource} retry={retry} label="rankings">{data => <>
      <RankingTable rankings={data.items} caption="Travel managers by performance score" />
      <Pages page={page} totalPages={data.totalPages} onPage={setPage} />
      {asOf(data.asOf)}
    </>}</Loaded>
  </section>;
}

/** Admins: past travels with their satisfaction figures. */
export function TravelHistory({ context }: { context: ApiContext }) {
  const [page, setPage] = React.useState(0);
  const load = React.useCallback((signal: AbortSignal) =>
    api<DashboardPage<TravelSummary>>(context, `/api/dashboards/admin/travels?page=${page}&size=20`, { signal }), [context, page]);
  const [resource, retry] = useResource(load);
  return <section className="workspace-card" aria-labelledby="history-title">
    <div className="card-heading"><h2 id="history-title">Travel history</h2><button type="button" onClick={retry}>Refresh</button></div>
    <Loaded resource={resource} retry={retry} label="travel history">{data => <>
      <TravelTable travels={data.items} caption="Started travels, most recent first" showManager />
      <Pages page={page} totalPages={data.totalPages} onPage={setPage} />
      {asOf(data.asOf)}
    </>}</Loaded>
  </section>;
}

function Pages({ page, totalPages, onPage }: { page: number; totalPages: number; onPage: (page: number) => void }) {
  if (totalPages <= 1) return null;
  return <div className="page-controls" aria-label="Pages">
    <button type="button" disabled={page === 0} onClick={() => onPage(page - 1)}>Previous</button>
    <span>Page {page + 1} of {totalPages}</span>
    <button type="button" disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}>Next</button>
  </div>;
}
