import * as React from "react";
import { api, errorMessage, fetchMe, login, type ApiContext, type Me } from "./lib/api";
import { homeFor, href, resolveRoute, routes, routesFor, type RouteId } from "./lib/routes";
import { AccountHome, RegisterPage, SetPasswordPage, roleLabel } from "./features/account/AccountPages";
import { MyData } from "./features/account/MyData";
import { UsersPanel } from "./features/users/UsersPanel";
import { ListingsPanel } from "./features/travels/ListingsPanel";
import { PaymentsPanel } from "./features/payments/PaymentsPanel";
import { TravelerTrips } from "./features/subscriptions/TravelerTrips";
import { SubscriberManager } from "./features/subscriptions/SubscriberManager";
import { FeedbackPanel } from "./features/feedback/FeedbackPanel";
import { FeedbackOverview } from "./features/feedback/FeedbackOverview";
import { MyReports } from "./features/reports/MyReports";
import { Recommendations } from "./features/recommendations/Recommendations";
import { ReportQueue } from "./features/reports/ReportQueue";
import { AdminOverview, ManagerAnalytics, ManagerRankings, MyStatistics, TravelHistory } from "./features/dashboards/Dashboards";

type Session = { token: string; expiresAt: number };

function storedSession(): Session | null {
  const token = sessionStorage.getItem("travelPlanToken");
  const expiresAt = Number(sessionStorage.getItem("travelPlanTokenExpiresAt"));
  if (!token || !expiresAt || expiresAt * 1000 <= Date.now()) {
    sessionStorage.removeItem("travelPlanToken");
    sessionStorage.removeItem("travelPlanTokenExpiresAt");
    return null;
  }
  return { token, expiresAt };
}

function CompassMark() {
  return <svg aria-hidden="true" viewBox="0 0 32 32">
    <circle cx="16" cy="16" r="12.5" />
    <path d="m20.8 10.8-2.9 7.1-7.1 2.9 2.9-7.1 7.1-2.9Z" />
    <circle cx="16" cy="16" r="1.35" className="mark-dot" />
  </svg>;
}

/** The screen for one route; every one of them loads its own data when it opens. */
function Screen({ route, me, context }: { route: RouteId; me: Me; context: ApiContext }) {
  switch (route) {
    case "home": return <div className="workflow-stack"><AccountHome me={me} /><Recommendations context={context} /></div>;
    case "travels": return <TravelerTrips context={context} />;
    case "feedback": return <div className="workflow-stack"><FeedbackPanel context={context} /></div>;
    case "reports": return <div className="workflow-stack"><MyReports context={context} /></div>;
    case "statistics": return <div className="workflow-stack"><MyStatistics context={context} /><MyData context={context} /></div>;
    case "listings": return <ListingsPanel context={context} admin={me.role === "ADMIN"} />;
    case "subscribers": return <div className="workflow-stack"><SubscriberManager context={context} /></div>;
    case "travel-feedback": return <div className="workflow-stack"><FeedbackOverview context={context} admin={me.role === "ADMIN"} /></div>;
    case "analytics": return <div className="workflow-stack"><ManagerAnalytics context={context} /></div>;
    case "overview": return <AdminOverview context={context} />;
    case "rankings": return <div className="workflow-stack"><ManagerRankings context={context} /></div>;
    case "history": return <div className="workflow-stack"><TravelHistory context={context} /></div>;
    case "users": return <UsersPanel context={context} active />;
    case "moderation": return <div className="workflow-stack"><ReportQueue context={context} /></div>;
    case "payments": return <PaymentsPanel context={context} active />;
  }
}

function App() {
  const [session, setSession] = React.useState<Session | null>(storedSession);
  const [username, setUsername] = React.useState("");
  const [password, setPassword] = React.useState("");
  const [error, setError] = React.useState("");
  const [busy, setBusy] = React.useState(false);
  const [me, setMe] = React.useState<Me | null>(null);
  const [notice, setNotice] = React.useState("");
  const [route, setRoute] = React.useState(() => window.location.hash);
  const main = React.useRef<HTMLElement>(null);
  const firstScreen = React.useRef(true);

  React.useEffect(() => {
    const follow = () => setRoute(window.location.hash);
    window.addEventListener("hashchange", follow);
    return () => window.removeEventListener("hashchange", follow);
  }, []);

  const clearSession = React.useCallback(() => {
    sessionStorage.removeItem("travelPlanToken");
    sessionStorage.removeItem("travelPlanTokenExpiresAt");
    setSession(null);
    setMe(null);
  }, []);

  React.useEffect(() => {
    if (!session) return;
    const remaining = session.expiresAt * 1000 - Date.now();
    if (remaining <= 0) { clearSession(); return; }
    const timer = window.setTimeout(clearSession, Math.min(remaining, 2_147_483_647));
    return () => window.clearTimeout(timer);
  }, [session, clearSession]);

  const context = React.useMemo<ApiContext | null>(() => session
    ? { token: session.token, onUnauthorized: clearSession }
    : null, [session, clearSession]);

  const loadMe = React.useCallback((current: { live: boolean }) => {
    if (!context) return;
    setError("");
    fetchMe(context).then(found => { if (current.live) setMe(found); })
      .catch(caught => { if (current.live) setError(errorMessage(caught)); });
  }, [context]);

  React.useEffect(() => {
    if (!context || me) return;
    const current = { live: true };
    loadMe(current);
    return () => { current.live = false; };
  }, [context, me, loadMe]);

  const resolved = me ? resolveRoute(route, me.role) : null;
  const screenKey = resolved?.kind === "page" ? resolved.route : resolved?.kind;

  // A new screen is announced by moving focus to the main region, as a page load would.
  React.useEffect(() => {
    if (!screenKey) return;
    if (firstScreen.current) { firstScreen.current = false; return; }
    main.current?.focus();
  }, [screenKey]);

  function leavePublicPage(message: string) {
    setNotice(message);
    window.location.hash = "#/";
    setRoute("#/");
  }

  async function signIn(event: React.FormEvent) {
    event.preventDefault();
    if (busy) return;
    setBusy(true); setError("");
    try {
      const result = await login(username, password);
      if (result.expiresAt * 1000 <= Date.now()) throw new Error("Expired token");
      sessionStorage.setItem("travelPlanToken", result.accessToken);
      sessionStorage.setItem("travelPlanTokenExpiresAt", String(result.expiresAt));
      setSession({ token: result.accessToken, expiresAt: result.expiresAt });
      setPassword("");
    } catch (caught) { setError(errorMessage(caught)); }
    finally { setBusy(false); }
  }

  async function signOut() {
    if (!context) return;
    setBusy(true);
    try { await api<void>(context, "/api/auth/logout", { method: "POST" }); }
    catch { /* Local session is cleared even if the service is unavailable. */ }
    finally { clearSession(); setBusy(false); firstScreen.current = true; }
  }

  const setupToken = route.startsWith("#/set-password") ? new URLSearchParams(route.split("?")[1] ?? "").get("token") : null;
  if (!context && setupToken) return <main className="login-shell">
    <SetPasswordPage token={setupToken} onDone={() => leavePublicPage("Password set. You can sign in now.")} />
  </main>;
  if (!context && route === "#/register") return <main className="login-shell">
    <RegisterPage onDone={email => { setUsername(email); leavePublicPage("Account created. Sign in with your new password."); }} />
  </main>;

  if (!context) return <main className="login-shell">
    <form className="login-card" onSubmit={signIn}>
      <span className="brand-mark"><CompassMark /></span>
      <p className="eyebrow">Let's Travel</p>
      <h1>Sign in</h1>
      <p className="login-intro">Sign in with your email and password.</p>
      {notice && <p className="message success" role="status">{notice}</p>}
      <label>Username<input required autoComplete="username" value={username} onChange={event => setUsername(event.target.value)} /></label>
      <label>Password<input required type="password" autoComplete="current-password" value={password} onChange={event => setPassword(event.target.value)} /></label>
      {error && <p className="message error" role="alert">{error}</p>}
      <button className="primary" disabled={busy}>{busy ? "Signing in…" : "Sign in"}</button>
      <a href="#/register">Create an account</a>
    </form>
  </main>;

  if (!me || !resolved) return <main className="login-shell">
    {error ? <div className="message error" role="alert"><p>{error}</p>
      <div className="actions"><button type="button" onClick={() => loadMe({ live: true })}>Try again</button>
        <button type="button" onClick={clearSession}>Sign out</button></div></div>
      : <p role="status">Loading your account…</p>}
  </main>;

  const current = resolved.kind === "page" ? resolved.route : null;
  return <div className="app-shell">
    <a className="skip-link" href="#main-content" onClick={event => { event.preventDefault(); main.current?.focus(); }}>Skip to content</a>
    <header className="site-header">
      <a className="brand" href={href(homeFor(me.role))}>
        <span className="brand-mark"><CompassMark /></span>
        <span>Let's Travel</span>
      </a>
      <div className="header-actions"><span className="phase-label">{roleLabel(me.role)}</span>
        <button type="button" onClick={() => void signOut()} disabled={busy}>Sign out</button></div>
    </header>
    <nav aria-label="Main navigation" className="section-nav">
      <ul>{routesFor(me.role).map(id => <li key={id}>
        <a href={href(id)} className={current === id ? "active" : undefined} aria-current={current === id ? "page" : undefined}>
          {routes[id].label}</a></li>)}</ul>
    </nav>
    <main id="main-content" ref={main} tabIndex={-1}>
      {resolved.kind === "page" ? <Screen key={resolved.route} route={resolved.route} me={me} context={context} />
        : <section className="workspace-card" aria-labelledby="unavailable-title">
          <h2 id="unavailable-title">{resolved.kind === "forbidden" ? "Not available for your role" : "Page not found"}</h2>
          <p>{resolved.kind === "forbidden"
            ? `${resolved.label} is not part of the ${roleLabel(me.role).toLowerCase()} workspace.`
            : "This address does not match any page."}</p>
          <a className="primary button-link" href={href(homeFor(me.role))}>Go to {routes[homeFor(me.role)].label}</a>
        </section>}
    </main>
    <footer><span>Let's Travel</span><span>Signed in as {me.email ?? roleLabel(me.role)}</span></footer>
  </div>;
}

export default App;
