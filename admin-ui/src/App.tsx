import * as React from "react";
import { api, errorMessage, fetchMe, login, type ApiContext, type Me } from "./lib/api";
import { AccountHome, RegisterPage, SetPasswordPage, roleLabel } from "./features/account/AccountPages";
import { UsersPanel } from "./features/users/UsersPanel";
import { TravelsPanel } from "./features/travels/TravelsPanel";
import { PaymentsPanel } from "./features/payments/PaymentsPanel";
import { TravelerTrips } from "./features/subscriptions/TravelerTrips";
import { SubscriberManager } from "./features/subscriptions/SubscriberManager";

type Section = "users" | "travels" | "subscriptions" | "payments";
type Session = { token: string; expiresAt: number };

const sections: { id: Section; label: string }[] = [
  { id: "users", label: "Users and travelers" },
  { id: "travels", label: "Travels and itineraries" },
  { id: "subscriptions", label: "Subscribers" },
  { id: "payments", label: "Payments and gateways" },
];

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

function App() {
  const [session, setSession] = React.useState<Session | null>(storedSession);
  const [section, setSection] = React.useState<Section>("users");
  const [visited, setVisited] = React.useState<Section[]>(["users"]);
  const [username, setUsername] = React.useState("admin");
  const [password, setPassword] = React.useState("");
  const [error, setError] = React.useState("");
  const [busy, setBusy] = React.useState(false);
  const [me, setMe] = React.useState<Me | null>(null);
  const [notice, setNotice] = React.useState("");
  const [route, setRoute] = React.useState(() => window.location.hash);

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

  React.useEffect(() => {
    if (!context || me) return;
    let current = true;
    fetchMe(context).then(found => { if (current) setMe(found); })
      .catch(caught => { if (current) setError(errorMessage(caught)); });
    return () => { current = false; };
  }, [context, me]);

  function leavePublicPage(message: string) {
    setNotice(message);
    window.location.hash = "#/";
    setRoute("#/");
  }

  async function signIn(event: React.FormEvent) {
    event.preventDefault(); setBusy(true); setError("");
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
    finally { clearSession(); setBusy(false); }
  }

  function chooseSection(next: Section) {
    setSection(next);
    setVisited(current => current.includes(next) ? current : [...current, next]);
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
      <p className="eyebrow">Travel Plan</p>
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

  if (!me) return <main className="login-shell"><p role="status">{error || "Loading your account…"}</p></main>;

  return <div className="app-shell">
    <header className="site-header">
      <a className="brand" href="#main-content" aria-label="Travel Plan Admin home">
        <span className="brand-mark"><CompassMark /></span>
        <span>Travel Plan Admin</span>
      </a>
      <div className="header-actions"><span className="phase-label">{roleLabel(me.role)}</span>
        <button type="button" onClick={() => void signOut()} disabled={busy}>Sign out</button></div>
    </header>
    {me.role !== "ADMIN" ? <main id="main-content"><AccountHome me={me} />
      {me.role === "TRAVEL_MANAGER" && <SubscriberManager context={context} />}
      <TravelerTrips context={context} /></main> : <main id="main-content">
      <div className="workspace-intro"><p className="eyebrow">Operations</p><h1>Plan and manage every journey.</h1>
        <p>Choose an area to work with live records from the administration services.</p></div>
      <nav aria-label="Admin sections" className="section-nav">
        {sections.map(item => <button key={item.id} type="button" className={section === item.id ? "active" : ""}
          aria-current={section === item.id ? "page" : undefined} onClick={() => chooseSection(item.id)}>{item.label}</button>)}
      </nav>
      {visited.includes("users") && <div hidden={section !== "users"}><UsersPanel context={context} active={section === "users"} /></div>}
      {visited.includes("travels") && <div hidden={section !== "travels"}><TravelsPanel context={context} active={section === "travels"} /></div>}
      {visited.includes("subscriptions") && <div hidden={section !== "subscriptions"}><SubscriberManager context={context} /></div>}
      {visited.includes("payments") && <div hidden={section !== "payments"}><PaymentsPanel context={context} active={section === "payments"} /></div>}
    </main>}
    <footer><span>Travel Plan</span><span>Operations administration</span></footer>
  </div>;
}

export default App;
