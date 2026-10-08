import * as React from "react";
import { allPages, api, errorMessage, type ApiContext } from "../../lib/api";
import { Message } from "../../components/Shared";

/** Everything the services hold about the signed-in person, as they return it to that person (docs/privacy.md). */
export async function collectMyData(context: ApiContext) {
  const contract = async <T,>(path: string) => {
    const items: T[] = [];
    for (let page = 0; page < 100; page++) {
      const result = await api<{ items: T[]; totalPages: number }>(context, `${path}${path.includes("?") ? "&" : "?"}page=${page}&size=100`);
      items.push(...result.items);
      if (page + 1 >= result.totalPages) break;
    }
    return items;
  };
  const [account, travelerProfiles, subscriptions, feedback, reports, payments, statistics] = await Promise.all([
    api<unknown>(context, "/api/me"),
    allPages<unknown>(context, "/api/travelers"),
    contract<unknown>("/api/subscriptions"),
    contract<unknown>("/api/feedback/mine"),
    contract<unknown>("/api/reports"),
    api<unknown[]>(context, "/api/subscription-payments"),
    api<unknown>(context, "/api/dashboards/me"),
  ]);
  return { exportedAt: new Date().toISOString(), account, travelerProfiles, subscriptions, feedback, reports, payments, statistics };
}

export function MyData({ context }: { context: ApiContext }) {
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState("");
  const [done, setDone] = React.useState(false);

  async function download() {
    if (busy) return;
    setBusy(true); setError(""); setDone(false);
    try {
      const data = await collectMyData(context);
      const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }));
      const link = document.createElement("a");
      link.href = url;
      link.download = `lets-travel-my-data-${data.exportedAt.slice(0, 10)}.json`;
      link.click();
      URL.revokeObjectURL(url);
      setDone(true);
    } catch (caught) { setError(errorMessage(caught)); }
    finally { setBusy(false); }
  }

  return <section className="workspace-card" aria-labelledby="my-data-title">
    <h2 id="my-data-title">Your data</h2>
    <p>Download a copy of everything Let's Travel keeps about you: your account, traveler profiles, subscriptions,
      payments (no card details are stored), feedback and reports. To delete your account, ask an administrator;
      what is removed and what is kept is explained in the privacy notice.</p>
    {error && <Message kind="error">{error}</Message>}
    {done && <Message kind="success">Your data was downloaded.</Message>}
    <div className="actions"><button type="button" className="primary" disabled={busy} onClick={() => void download()}>
      {busy ? "Preparing…" : "Download my data"}</button></div>
  </section>;
}
