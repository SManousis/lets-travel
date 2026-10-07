import * as React from "react";
import { api, apiResult, errorMessage, newKey, type ApiContext } from "../../lib/api";
import type { PaymentOptions, Subscription, SubscriptionPayment, TestOutcome } from "../../lib/types";
import { Message } from "../../components/Shared";
import { formatMoney } from "./format";
import { isUnresolved } from "./paymentFormat";

const testOutcomes: { value: TestOutcome; label: string }[] = [
  { value: "APPROVE", label: "Approve the payment" },
  { value: "DECLINE", label: "Decline the payment" },
  { value: "TIMEOUT", label: "Provider timeout (answer lost, then reconciled)" },
];

/**
 * Pays for one PENDING_PAYMENT subscription with a saved method or the test provider. The browser
 * never sees card data: it sends only the subscription ID and the chosen method's ID. The amount
 * comes from the server. One Idempotency-Key is kept per choice until the server answers, so a
 * retry after a network error can never create a second payment.
 */
export function PayForSubscription({ context, subscription, onFinished }: {
  context: ApiContext; subscription: Subscription; onFinished: (payment: SubscriptionPayment) => void;
}) {
  const [options, setOptions] = React.useState<PaymentOptions | null>(null);
  const [choice, setChoice] = React.useState("");
  const [testOutcome, setTestOutcome] = React.useState<TestOutcome>("APPROVE");
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState("");
  const [payment, setPayment] = React.useState<SubscriptionPayment | null>(null);
  const keys = React.useRef(new Map<string, string>());

  React.useEffect(() => {
    let current = true;
    api<PaymentOptions>(context, "/api/payment-options").then(found => {
      if (!current) return;
      setOptions(found);
      const preferred = found.methods.find(method => method.isDefault && method.available) ?? found.methods.find(method => method.available);
      setChoice(preferred ? preferred.id : found.testProviderEnabled ? "test" : "");
    }).catch(caught => { if (current) setError(errorMessage(caught)); });
    return () => { current = false; };
  }, [context]);

  async function pay(event: React.FormEvent) {
    event.preventDefault();
    if (!choice) return;
    setBusy(true); setError("");
    const body = choice === "test"
      ? { subscriptionId: subscription.subscriptionId, testOutcome }
      : { subscriptionId: subscription.subscriptionId, paymentMethodId: choice };
    const keyId = `${subscription.subscriptionId}|${choice}|${choice === "test" ? testOutcome : ""}`;
    const key = keys.current.get(keyId) ?? newKey();
    keys.current.set(keyId, key);
    try {
      const result = await apiResult<SubscriptionPayment>(context, "/api/subscription-payments", {
        method: "POST", body, headers: { "Idempotency-Key": key },
      });
      setPayment(result.data);
      if (!isUnresolved(result.data)) onFinished(result.data);
    } catch (caught) { setError(errorMessage(caught)); }
    finally { setBusy(false); }
  }

  async function checkStatus() {
    if (!payment) return;
    setBusy(true); setError("");
    try {
      const latest = await api<SubscriptionPayment>(context, `/api/subscription-payments/${payment.paymentId}`);
      setPayment(latest);
      if (!isUnresolved(latest)) onFinished(latest);
    } catch (caught) { setError(errorMessage(caught)); }
    finally { setBusy(false); }
  }

  const amount = formatMoney(subscription.price.amountMinor, subscription.price.currency);
  if (payment) {
    const kind = payment.state === "SUCCEEDED" ? "success" : payment.state === "FAILED" ? "error" : "info";
    return <div className="payment-panel" aria-label="Payment result">
      <Message kind={kind}>{payment.message}</Message>
      {isUnresolved(payment) && <div className="actions">
        <button type="button" disabled={busy} onClick={() => void checkStatus()}>{busy ? "Checking…" : "Check payment status"}</button>
      </div>}
    </div>;
  }

  return <form className="payment-panel" aria-label={`Pay for ${subscription.travelTitle}`} onSubmit={event => void pay(event)}>
    {error && <Message kind="error">{error}</Message>}
    {!options ? (!error && <p role="status">Loading payment methods…</p>) : <>
      <fieldset className="payment-choices">
        <legend>Pay {amount} with</legend>
        {options.methods.map(method => <label key={method.id}>
          <input type="radio" name={`method-${subscription.subscriptionId}`} value={method.id} checked={choice === method.id}
            disabled={!method.available} onChange={() => setChoice(method.id)} />
          {method.displayLabel} ({method.displayMetadata ?? method.provider}){!method.available && " – provider unavailable"}
        </label>)}
        {options.testProviderEnabled && <label>
          <input type="radio" name={`method-${subscription.subscriptionId}`} value="test" checked={choice === "test"}
            onChange={() => setChoice("test")} />
          Test card (simulation, no real money)
        </label>}
        {choice === "test" && <label>Simulated result
          <select value={testOutcome} onChange={event => setTestOutcome(event.target.value as TestOutcome)}>
            {testOutcomes.map(item => <option key={item.value} value={item.value}>{item.label}</option>)}
          </select>
        </label>}
      </fieldset>
      {options.methods.length === 0 && !options.testProviderEnabled
        ? <Message>You have no saved payment method. Ask an administrator to connect one.</Message>
        : <div className="actions"><button className="primary" disabled={busy || !choice}>
          {busy ? "Processing payment…" : `Pay ${amount}`}</button></div>}
    </>}
  </form>;
}
