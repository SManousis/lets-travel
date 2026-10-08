import * as React from "react";
import { completePasswordSetup, errorMessage, register, type Me } from "../../lib/api";

const roleLabels: Record<Me["role"], string> = {
  ADMIN: "Administrator", TRAVEL_MANAGER: "Travel manager", TRAVELER: "Traveler",
};

export function roleLabel(role: Me["role"]): string {
  return roleLabels[role];
}

export function RegisterPage({ onDone }: { onDone: (email: string) => void }) {
  const [form, setForm] = React.useState({ email: "", firstName: "", lastName: "", password: "" });
  const [error, setError] = React.useState("");
  const [busy, setBusy] = React.useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault(); setBusy(true); setError("");
    try {
      await register(form);
      onDone(form.email);
    } catch (caught) { setError(errorMessage(caught)); }
    finally { setBusy(false); }
  }

  const field = (name: keyof typeof form) => (event: React.ChangeEvent<HTMLInputElement>) =>
    setForm(current => ({ ...current, [name]: event.target.value }));

  return <form className="login-card" onSubmit={submit}>
    <h1>Create your account</h1>
    <p className="login-intro">Sign up as a traveler to browse and book trips.</p>
    <label>Email<input required type="email" autoComplete="email" value={form.email} onChange={field("email")} /></label>
    <label>First name<input required autoComplete="given-name" value={form.firstName} onChange={field("firstName")} /></label>
    <label>Last name<input required autoComplete="family-name" value={form.lastName} onChange={field("lastName")} /></label>
    <label>Password<input required type="password" minLength={10} autoComplete="new-password" value={form.password}
      onChange={field("password")} aria-describedby="password-hint" /></label>
    <p id="password-hint" className="field-hint">At least 10 characters.</p>
    {error && <p className="message error" role="alert">{error}</p>}
    <button className="primary" disabled={busy}>{busy ? "Creating account…" : "Create account"}</button>
    <a href="#/">Back to sign in</a>
  </form>;
}

export function SetPasswordPage({ token, onDone }: { token: string; onDone: () => void }) {
  const [password, setPassword] = React.useState("");
  const [confirm, setConfirm] = React.useState("");
  const [error, setError] = React.useState("");
  const [busy, setBusy] = React.useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault(); setError("");
    if (password !== confirm) { setError("The passwords do not match."); return; }
    setBusy(true);
    try {
      await completePasswordSetup(token, password);
      onDone();
    } catch (caught) { setError(errorMessage(caught)); }
    finally { setBusy(false); }
  }

  return <form className="login-card" onSubmit={submit}>
    <h1>Choose your password</h1>
    <label>New password<input required type="password" minLength={10} autoComplete="new-password" value={password}
      onChange={event => setPassword(event.target.value)} /></label>
    <label>Repeat password<input required type="password" minLength={10} autoComplete="new-password" value={confirm}
      onChange={event => setConfirm(event.target.value)} /></label>
    {error && <p className="message error" role="alert">{error}</p>}
    <button className="primary" disabled={busy}>{busy ? "Saving…" : "Set password"}</button>
  </form>;
}

/** The home screen's account summary for travelers and travel managers. */
export function AccountHome({ me }: { me: Me }) {
  const name = [me.firstName, me.lastName].filter(Boolean).join(" ");
  return <section className="panel" aria-labelledby="account-heading">
    <h2 id="account-heading">Your account</h2>
    <dl className="account-details">
      {name && <><dt>Name</dt><dd>{name}</dd></>}
      {me.email && <><dt>Email</dt><dd>{me.email}</dd></>}
      <dt>Role</dt><dd>{roleLabel(me.role)}</dd>
      {me.memberSince && <><dt>Member since</dt><dd>{new Date(me.memberSince).toLocaleDateString()}</dd></>}
    </dl>
    <p><a href="#/travels">Find and book a travel</a> · <a href="#/statistics">See your statistics</a></p>
  </section>;
}
