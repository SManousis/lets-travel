import * as React from "react";
import { allPages, api, apiResult, errorMessage, newKey, type ApiContext } from "../../lib/api";
import type { Gateway, PaymentMethod, User } from "../../lib/types";
import { Empty, Message } from "../../components/Shared";

const emptyGateway = { provider: "STRIPE" as Gateway["provider"], displayName: "" };
const emptyMethod = { userId: "", gatewayId: "", providerReference: "", displayLabel: "", isDefault: false };

export function PaymentsPanel({ context, active }: { context: ApiContext; active: boolean }) {
  const [gateways, setGateways] = React.useState<Gateway[]>([]);
  const [methods, setMethods] = React.useState<PaymentMethod[]>([]);
  const [users, setUsers] = React.useState<User[]>([]);
  const [loading, setLoading] = React.useState(true);
  const [gatewayDraft, setGatewayDraft] = React.useState(emptyGateway);
  const [editingGateway, setEditingGateway] = React.useState<Gateway | null>(null);
  const [gatewayBusy, setGatewayBusy] = React.useState(false);
  const [gatewayError, setGatewayError] = React.useState("");
  const [gatewayNotice, setGatewayNotice] = React.useState("");
  const [methodDraft, setMethodDraft] = React.useState(emptyMethod);
  const [methodPending, setMethodPending] = React.useState(false);
  const methodAttempt = React.useRef<{ body: string; key: string } | null>(null);
  const deleteKeys = React.useRef<Record<string, string>>({});
  const [editingMethod, setEditingMethod] = React.useState<PaymentMethod | null>(null);
  const [methodBusy, setMethodBusy] = React.useState(false);
  const [methodError, setMethodError] = React.useState("");
  const [methodNotice, setMethodNotice] = React.useState("");
  const [userFilter, setUserFilter] = React.useState("");

  const loadGateways = React.useCallback(async () => {
    try { setGateways(await api<Gateway[]>(context, "/api/payment-gateways")); }
    catch (error) { setGatewayError(errorMessage(error)); }
  }, [context]);
  const loadMethods = React.useCallback(async () => {
    try { setMethods(await api<PaymentMethod[]>(context, "/api/payment-methods")); }
    catch (error) { setMethodError(errorMessage(error)); }
  }, [context]);
  const loadUsers = React.useCallback(async () => {
    try { setUsers(await allPages<User>(context, "/api/users")); }
    catch (error) { setMethodError(errorMessage(error)); }
  }, [context]);

  React.useEffect(() => {
    if (!active) return;
    setLoading(true);
    void Promise.all([loadGateways(), loadMethods(), loadUsers()]).finally(() => setLoading(false));
  }, [active, loadGateways, loadMethods, loadUsers]);

  async function saveGateway(event: React.FormEvent) {
    event.preventDefault(); setGatewayBusy(true); setGatewayError(""); setGatewayNotice("");
    try {
      await api<Gateway>(context, editingGateway ? `/api/payment-gateways/${editingGateway.id}` : "/api/payment-gateways",
        { method: editingGateway ? "PATCH" : "POST", body: gatewayDraft });
      setGatewayNotice(editingGateway ? "Gateway updated." : "Gateway created.");
      setGatewayDraft(emptyGateway); setEditingGateway(null); await loadGateways();
    } catch (error) { setGatewayError(errorMessage(error)); }
    finally { setGatewayBusy(false); }
  }

  async function deleteGateway(gateway: Gateway) {
    if (!window.confirm(`Delete gateway ${gateway.displayName}? Linked methods must be removed first.`)) return;
    setGatewayBusy(true); setGatewayError(""); setGatewayNotice("");
    try {
      await api<void>(context, `/api/payment-gateways/${gateway.id}`, { method: "DELETE" });
      setGatewayNotice("Gateway deleted."); await loadGateways();
    } catch (error) { setGatewayError(errorMessage(error)); }
    finally { setGatewayBusy(false); }
  }

  async function createMethod(event: React.FormEvent) {
    event.preventDefault(); setMethodBusy(true); setMethodError(""); setMethodNotice("");
    try {
      const body = JSON.stringify(methodDraft);
      if (methodAttempt.current?.body !== body) methodAttempt.current = { body, key: newKey() };
      const result = await apiResult<PaymentMethod>(context, "/api/payment-methods", {
        method: "POST", headers: { "Idempotency-Key": methodAttempt.current.key }, body: methodDraft,
      });
      await loadMethods();
      if (result.status === 202) {
        setMethodPending(true);
        setMethodNotice("Provider inspection is pending. The inputs are locked so retry uses the same key and reference after the claim window.");
      } else {
        setMethodPending(false);
        setMethodDraft(emptyMethod); methodAttempt.current = null;
        setMethodNotice("Payment method connected. Only safe display details are shown below.");
      }
    } catch (error) {
      setMethodError(errorMessage(error));
      await loadMethods();
    } finally { setMethodBusy(false); }
  }

  async function saveLabel(event: React.FormEvent) {
    event.preventDefault(); if (!editingMethod) return;
    setMethodBusy(true); setMethodError(""); setMethodNotice("");
    try {
      await api<PaymentMethod>(context, `/api/payment-methods/${editingMethod.id}`, {
        method: "PATCH", body: { displayLabel: editingMethod.displayLabel },
      });
      setEditingMethod(null); setMethodNotice("Display label updated."); await loadMethods();
    } catch (error) { setMethodError(errorMessage(error)); }
    finally { setMethodBusy(false); }
  }

  async function selectDefault(method: PaymentMethod) {
    setMethodBusy(true); setMethodError(""); setMethodNotice("");
    try {
      await api<PaymentMethod>(context, `/api/payment-methods/${method.id}`, {
        method: "PATCH", body: { isDefault: true },
      });
      setMethodNotice(`${method.displayLabel} is now the default payment method.`);
      await loadMethods();
    } catch (error) { setMethodError(errorMessage(error)); }
    finally { setMethodBusy(false); }
  }

  async function deleteMethod(method: PaymentMethod) {
    if (!window.confirm(`Remove ${method.displayLabel}? The provider reference will be detached or deleted.`)) return;
    setMethodBusy(true); setMethodError(""); setMethodNotice("");
    const key = deleteKeys.current[method.id] ?? (deleteKeys.current[method.id] = newKey());
    try {
      const result = await apiResult<void>(context, `/api/payment-methods/${method.id}`, {
        method: "DELETE", headers: { "Idempotency-Key": key },
      });
      if (result.status === 204) {
        delete deleteKeys.current[method.id]; setEditingMethod(null);
        setMethodNotice("Payment method removed.");
      } else setMethodNotice("Provider cleanup is pending. Refresh and retry this method later.");
      await loadMethods();
    } catch (error) {
      setMethodError(errorMessage(error));
      await loadMethods();
    } finally { setMethodBusy(false); }
  }

  const visibleMethods = userFilter ? methods.filter(method => method.userId === userFilter) : methods;
  const userName = (id: string) => users.find(user => user.id === id)?.email ?? id;
  const gatewayName = (id: string) => gateways.find(gateway => gateway.id === id)?.displayName ?? id;

  return <div className="workflow-stack">
    <div className="panel-heading"><div><p className="eyebrow">Provider connections</p><h2>Payments and gateways</h2></div>
      <p>Manage sandbox references and monitor provider cleanup without storing card details.</p></div>
    {loading && <p role="status">Loading payment records…</p>}
    <div className="workspace-grid">
      <section className="workspace-card" aria-labelledby="gateway-list-title">
        <div className="card-heading"><h3 id="gateway-list-title">Gateways</h3><button type="button" onClick={() => void loadGateways()}>Refresh</button></div>
        {gatewayError && <Message kind="error">{gatewayError}</Message>}
        {gateways.length === 0 ? <Empty>No gateways configured.</Empty> : <ul className="record-list">{gateways.map(gateway =>
          <li className="record-row" key={gateway.id}><div><strong>{gateway.displayName}</strong>
            <span>{gateway.provider} · {gateway.enabled ? "Enabled" : "Disabled"}</span></div>
            <div className="actions"><button type="button" onClick={() => {
              setEditingGateway(gateway); setGatewayDraft({ provider: gateway.provider, displayName: gateway.displayName });
            }}>Edit</button><button type="button" className="danger" disabled={gatewayBusy} onClick={() => void deleteGateway(gateway)}>Delete</button></div>
          </li>)}</ul>}
      </section>
      <section className="workspace-card" aria-labelledby="gateway-form-title">
        <h3 id="gateway-form-title">{editingGateway ? "Edit gateway" : "Add gateway"}</h3>
        {gatewayNotice && <Message kind="success">{gatewayNotice}</Message>}
        <form className="field-grid" onSubmit={saveGateway}>
          <label>Provider<select disabled={!!editingGateway} value={gatewayDraft.provider} onChange={event => setGatewayDraft({ ...gatewayDraft, provider: event.target.value as Gateway["provider"] })}>
            <option value="STRIPE">Stripe sandbox</option><option value="PAYPAL">PayPal sandbox</option></select></label>
          <label>Display name<input required value={gatewayDraft.displayName} onChange={event => setGatewayDraft({ ...gatewayDraft, displayName: event.target.value })} /></label>
          <div className="actions"><button className="primary" disabled={gatewayBusy}>{gatewayBusy ? "Saving…" : "Save gateway"}</button>
            {editingGateway && <button type="button" onClick={() => { setEditingGateway(null); setGatewayDraft(emptyGateway); }}>Cancel</button>}</div>
        </form>
        <p className="field-hint">Provider selection cannot be changed after creation.</p>
      </section>
    </div>
    <div className="workspace-grid">
      <section className="workspace-card" aria-labelledby="method-list-title">
        <div className="card-heading"><h3 id="method-list-title">Payment methods</h3>
          <button type="button" onClick={() => void loadMethods()}>Refresh</button></div>
        {methodError && <Message kind="error">{methodError}</Message>}
        {methodNotice && <Message kind="success">{methodNotice}</Message>}
        <label className="inline-filter">Filter by user<select value={userFilter} onChange={event => setUserFilter(event.target.value)}>
          <option value="">All users</option>{users.map(user => <option key={user.id} value={user.id}>{user.email}</option>)}</select></label>
        {visibleMethods.length === 0 ? <Empty>No payment methods found.</Empty> : <ul className="record-list">{visibleMethods.map(method =>
          <li className="record-row" key={method.id}><div><strong>{method.displayLabel}</strong>
            <span>{method.displayMetadata || method.provider} · {userName(method.userId)}</span>
            <small>{gatewayName(method.gatewayId)} · <span className={`status ${method.status.toLowerCase()}`}>{method.status}</span>
              {method.isDefault && <> · <span>Default</span></>}
              {method.failureCode && <> · {method.failureCode}</>}</small></div>
            <div className="actions"><button type="button" onClick={() => setEditingMethod({ ...method })}>Label</button>
              {method.status === "ACTIVE" && !method.isDefault && <button type="button" disabled={methodBusy}
                onClick={() => void selectDefault(method)}>Make default</button>}
              <button type="button" className="danger" disabled={methodBusy} onClick={() => void deleteMethod(method)}>Remove</button></div>
          </li>)}</ul>}
      </section>
      <section className="workspace-card" aria-labelledby="method-form-title">
        <h3 id="method-form-title">Connect sandbox method</h3>
        <p className="field-hint">Finish tokenization and approval with the provider first, then enter its reusable reference here. Never enter a card number or security code.</p>
        <form className="field-grid" onSubmit={createMethod}>
          <label>User<select required disabled={methodPending} value={methodDraft.userId} onChange={event => setMethodDraft({ ...methodDraft, userId: event.target.value })}>
            <option value="">Choose active user</option>{users.filter(user => user.status === "ACTIVE").map(user =>
              <option key={user.id} value={user.id}>{user.email}</option>)}</select></label>
          <label>Gateway<select required disabled={methodPending} value={methodDraft.gatewayId} onChange={event => setMethodDraft({ ...methodDraft, gatewayId: event.target.value })}>
            <option value="">Choose gateway</option>{gateways.filter(gateway => gateway.enabled).map(gateway =>
              <option key={gateway.id} value={gateway.id}>{gateway.displayName} · {gateway.provider}</option>)}</select></label>
          <label>Provider reference<input required disabled={methodPending} autoComplete="off" value={methodDraft.providerReference}
            onChange={event => setMethodDraft({ ...methodDraft, providerReference: event.target.value })} /></label>
          <label>Display label<input required disabled={methodPending} value={methodDraft.displayLabel} onChange={event => setMethodDraft({ ...methodDraft, displayLabel: event.target.value })} /></label>
          <label>Set as default after connection<input type="checkbox" disabled={methodPending} checked={methodDraft.isDefault}
            onChange={event => setMethodDraft({ ...methodDraft, isDefault: event.target.checked })} /></label>
          <button className="primary" disabled={methodBusy || users.every(user => user.status !== "ACTIVE") || gateways.length === 0}>
            {methodBusy ? "Checking provider…" : "Connect method"}</button>
        </form>
        {editingMethod && <form className="field-grid edit-label" onSubmit={saveLabel}>
          <h4>Edit display label</h4><label>Label<input required value={editingMethod.displayLabel}
            onChange={event => setEditingMethod({ ...editingMethod, displayLabel: event.target.value })} /></label>
          <div className="actions"><button className="primary" disabled={methodBusy}>{methodBusy ? "Saving…" : "Save label"}</button>
            <button type="button" onClick={() => setEditingMethod(null)}>Cancel</button></div>
        </form>}
      </section>
    </div>
  </div>;
}
