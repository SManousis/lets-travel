import * as React from "react";
import { api, apiResult, errorMessage, newKey, type ApiContext, type Page } from "../../lib/api";
import type { Traveler, User } from "../../lib/types";
import { Empty, Message, PageControls } from "../../components/Shared";

const emptyUser = { email: "", firstName: "", lastName: "", role: "OPERATOR" as User["role"] };
const emptyTraveler = { passportNumber: "", nationality: "" };

export function UsersPanel({ context, active }: { context: ApiContext; active: boolean }) {
  const [usersPage, setUsersPage] = React.useState(0);
  const [userList, setUserList] = React.useState<Page<User> | null>(null);
  const [selected, setSelected] = React.useState<User | null>(null);
  const [newUser, setNewUser] = React.useState(emptyUser);
  const createAttempt = React.useRef<{ body: string; key: string } | null>(null);
  const [editUser, setEditUser] = React.useState({ ...emptyUser, status: "ACTIVE" as User["status"] });
  const [userBusy, setUserBusy] = React.useState(false);
  const [userError, setUserError] = React.useState("");
  const [userNotice, setUserNotice] = React.useState("");
  const [travelerPage, setTravelerPage] = React.useState(0);
  const [travelerList, setTravelerList] = React.useState<Page<Traveler> | null>(null);
  const [newTraveler, setNewTraveler] = React.useState(emptyTraveler);
  const [editingTraveler, setEditingTraveler] = React.useState<Traveler | null>(null);
  const [travelerBusy, setTravelerBusy] = React.useState(false);
  const [travelerError, setTravelerError] = React.useState("");
  const [travelerNotice, setTravelerNotice] = React.useState("");
  const selectedUserId = React.useRef<string | null>(null);
  const travelerRequest = React.useRef(0);

  const loadUsers = React.useCallback(async () => {
    try {
      const result = await api<Page<User>>(context, `/api/users?page=${usersPage}&size=10`);
      setUserList(result);
      setSelected(current => current ? result.items.find(user => user.id === current.id) ?? current : null);
    } catch (error) { setUserError(errorMessage(error)); }
  }, [context, usersPage]);

  const loadTravelers = React.useCallback(async () => {
    const request = ++travelerRequest.current;
    const userId = selected?.id;
    if (!userId) { setTravelerList(null); return; }
    try {
      const result = await api<Page<Traveler>>(context,
        `/api/travelers?userId=${userId}&page=${travelerPage}&size=10`);
      if (request === travelerRequest.current && selectedUserId.current === userId) setTravelerList(result);
    } catch (error) {
      if (request === travelerRequest.current && selectedUserId.current === userId) setTravelerError(errorMessage(error));
    }
  }, [context, selected, travelerPage]);

  React.useEffect(() => { if (active) void loadUsers(); }, [active, loadUsers]);
  React.useEffect(() => { if (active) void loadTravelers(); }, [active, loadTravelers]);

  function chooseUser(user: User) {
    selectedUserId.current = user.id;
    ++travelerRequest.current;
    setSelected(user);
    setTravelerList(null);
    setEditUser({ email: user.email, firstName: user.firstName, lastName: user.lastName,
      role: user.role, status: user.status });
    setTravelerPage(0);
    setEditingTraveler(null);
    setTravelerError("");
  }

  async function createUser(event: React.FormEvent) {
    event.preventDefault(); setUserBusy(true); setUserError(""); setUserNotice("");
    try {
      const body = JSON.stringify(newUser);
      if (createAttempt.current?.body !== body) createAttempt.current = { body, key: newKey() };
      const user = await api<User>(context, "/api/users", {
        method: "POST", headers: { "Idempotency-Key": createAttempt.current.key }, body: newUser,
      });
      createAttempt.current = null;
      setNewUser(emptyUser); setUserNotice("User created. Arrange their initial credentials through the operator process.");
      chooseUser(user); await loadUsers();
    } catch (error) { setUserError(errorMessage(error)); }
    finally { setUserBusy(false); }
  }

  async function saveUser(event: React.FormEvent) {
    event.preventDefault(); if (!selected) return;
    setUserBusy(true); setUserError(""); setUserNotice("");
    try {
      const user = await api<User>(context, `/api/users/${selected.id}`, { method: "PATCH", body: editUser });
      chooseUser(user); setUserNotice("User updated."); await loadUsers();
    } catch (error) { setUserError(errorMessage(error)); }
    finally { setUserBusy(false); }
  }

  async function deleteUser() {
    if (!selected || !window.confirm(`Delete ${selected.email}? Their access will be revoked and cleanup may need retries.`)) return;
    setUserBusy(true); setUserError(""); setUserNotice("");
    try {
      const result = await apiResult<void>(context, `/api/users/${selected.id}`, { method: "DELETE" });
      setUserNotice(result.status === 202
        ? "Cleanup is still in progress. Use Delete user again after resolving any payment failure."
        : "User access and linked records were cleaned up. The disabled profile remains for audit.");
      const refreshed = await api<User>(context, `/api/users/${selected.id}`);
      chooseUser(refreshed); await loadUsers(); await loadTravelers();
    } catch (error) { setUserError(errorMessage(error)); }
    finally { setUserBusy(false); }
  }

  async function createTraveler(event: React.FormEvent) {
    event.preventDefault(); if (!selected) return;
    setTravelerBusy(true); setTravelerError(""); setTravelerNotice("");
    try {
      await api<Traveler>(context, "/api/travelers", { method: "POST", body: { ...newTraveler, userId: selected.id } });
      setNewTraveler(emptyTraveler); setTravelerNotice("Traveler created."); await loadTravelers();
    } catch (error) { setTravelerError(errorMessage(error)); }
    finally { setTravelerBusy(false); }
  }

  async function saveTraveler(event: React.FormEvent) {
    event.preventDefault(); if (!editingTraveler) return;
    setTravelerBusy(true); setTravelerError(""); setTravelerNotice("");
    try {
      await api<Traveler>(context, `/api/travelers/${editingTraveler.id}`, {
        method: "PATCH", body: { passportNumber: editingTraveler.passportNumber, nationality: editingTraveler.nationality },
      });
      setEditingTraveler(null); setTravelerNotice("Traveler updated."); await loadTravelers();
    } catch (error) { setTravelerError(errorMessage(error)); }
    finally { setTravelerBusy(false); }
  }

  async function deleteTraveler(traveler: Traveler) {
    if (!window.confirm(`Delete traveler ${traveler.passportNumber}? Travel participation will be removed.`)) return;
    setTravelerBusy(true); setTravelerError(""); setTravelerNotice("");
    try {
      await api<void>(context, `/api/travelers/${traveler.id}`, { method: "DELETE" });
      setEditingTraveler(null); setTravelerNotice("Traveler deleted."); await loadTravelers();
    } catch (error) { setTravelerError(errorMessage(error)); }
    finally { setTravelerBusy(false); }
  }

  return <div className="workflow-stack">
    <div className="panel-heading"><div><p className="eyebrow">People</p><h2>Users and travelers</h2></div>
      <p>Manage account details and the traveler profiles attached to each user.</p></div>
    <div className="workspace-grid">
      <section className="workspace-card" aria-labelledby="user-list-title">
        <div className="card-heading"><h3 id="user-list-title">Users</h3><button type="button" onClick={() => void loadUsers()}>Refresh</button></div>
        {userError && <Message kind="error">{userError}</Message>}
        {!userList ? <p>Loading users…</p> : userList.items.length === 0 ? <Empty>No users found.</Empty>
          : <ul className="record-list">{userList.items.map(user => <li key={user.id}>
            <button type="button" className={selected?.id === user.id ? "record selected" : "record"} onClick={() => chooseUser(user)}>
              <strong>{user.firstName} {user.lastName}</strong><span>{user.email}</span>
              <small>{user.role} · {user.status}</small>
            </button></li>)}</ul>}
        <PageControls result={userList} onPage={setUsersPage} />
      </section>
      <section className="workspace-card" aria-labelledby="new-user-title">
        <h3 id="new-user-title">Create user</h3>
        <form className="field-grid" onSubmit={createUser}>
          <label>Email<input required type="email" value={newUser.email} onChange={event => { createAttempt.current = null; setNewUser({ ...newUser, email: event.target.value }); }} /></label>
          <label>First name<input required value={newUser.firstName} onChange={event => { createAttempt.current = null; setNewUser({ ...newUser, firstName: event.target.value }); }} /></label>
          <label>Last name<input required value={newUser.lastName} onChange={event => { createAttempt.current = null; setNewUser({ ...newUser, lastName: event.target.value }); }} /></label>
          <label>Role<select value={newUser.role} onChange={event => { createAttempt.current = null; setNewUser({ ...newUser, role: event.target.value as User["role"] }); }}>
            <option value="OPERATOR">Operator</option><option value="ADMIN">Administrator</option></select></label>
          <button className="primary" disabled={userBusy}>{userBusy ? "Saving…" : "Create user"}</button>
        </form>
        <p className="field-hint">Initial credentials are managed through the operator process.</p>
      </section>
    </div>
    {userNotice && <Message kind="success">{userNotice}</Message>}
    {selected && <>
      <section className="workspace-card" aria-labelledby="edit-user-title">
        <div className="card-heading"><div><h3 id="edit-user-title">{selected.firstName} {selected.lastName}</h3>
          <p className="record-meta">ID: {selected.id} · Created {new Date(selected.createdAt).toLocaleDateString()}</p></div>
          <button className="danger" type="button" disabled={userBusy} onClick={() => void deleteUser()}>
            Delete user</button></div>
        <form className="field-grid columns" onSubmit={saveUser}>
          <label>Email<input required type="email" value={editUser.email} onChange={event => setEditUser({ ...editUser, email: event.target.value })} /></label>
          <label>First name<input required value={editUser.firstName} onChange={event => setEditUser({ ...editUser, firstName: event.target.value })} /></label>
          <label>Last name<input required value={editUser.lastName} onChange={event => setEditUser({ ...editUser, lastName: event.target.value })} /></label>
          <label>Role<select value={editUser.role} onChange={event => setEditUser({ ...editUser, role: event.target.value as User["role"] })}>
            <option value="OPERATOR">Operator</option><option value="ADMIN">Administrator</option></select></label>
          <label>Status<select value={editUser.status} onChange={event => setEditUser({ ...editUser, status: event.target.value as User["status"] })}>
            <option value="ACTIVE">Active</option><option value="DISABLED">Disabled</option><option value="PENDING">Pending</option><option value="FAILED">Failed</option></select></label>
          <button className="primary" disabled={userBusy}>{userBusy ? "Saving…" : "Save user"}</button>
        </form>
      </section>
      <div className="workspace-grid">
        <section className="workspace-card" aria-labelledby="traveler-list-title">
          <div className="card-heading"><h3 id="traveler-list-title">Travelers for {selected.email}</h3>
            <button type="button" onClick={() => void loadTravelers()}>Refresh</button></div>
          {travelerError && <Message kind="error">{travelerError}</Message>}
          {!travelerList ? <p>Loading travelers…</p> : travelerList.items.length === 0 ? <Empty>No traveler profiles yet.</Empty>
            : <ul className="record-list">{travelerList.items.map(traveler => <li className="record-row" key={traveler.id}>
              <div><strong>{traveler.passportNumber}</strong><span>{traveler.nationality}</span></div>
              <div className="actions"><button type="button" onClick={() => setEditingTraveler({ ...traveler })}>Edit</button>
                <button type="button" className="danger" disabled={travelerBusy} onClick={() => void deleteTraveler(traveler)}>Delete</button></div>
            </li>)}</ul>}
          <PageControls result={travelerList} onPage={setTravelerPage} />
        </section>
        <section className="workspace-card" aria-labelledby="traveler-form-title">
          <h3 id="traveler-form-title">{editingTraveler ? "Edit traveler" : "Add traveler"}</h3>
          {travelerNotice && <Message kind="success">{travelerNotice}</Message>}
          {editingTraveler ? <form className="field-grid" onSubmit={saveTraveler}>
            <label>Passport number<input required value={editingTraveler.passportNumber} onChange={event => setEditingTraveler({ ...editingTraveler, passportNumber: event.target.value })} /></label>
            <label>Nationality<input required value={editingTraveler.nationality} onChange={event => setEditingTraveler({ ...editingTraveler, nationality: event.target.value })} /></label>
            <div className="actions"><button className="primary" disabled={travelerBusy}>{travelerBusy ? "Saving…" : "Save traveler"}</button>
              <button type="button" onClick={() => setEditingTraveler(null)}>Cancel</button></div>
          </form> : <form className="field-grid" onSubmit={createTraveler}>
            <label>Passport number<input required value={newTraveler.passportNumber} onChange={event => setNewTraveler({ ...newTraveler, passportNumber: event.target.value })} /></label>
            <label>Nationality<input required value={newTraveler.nationality} onChange={event => setNewTraveler({ ...newTraveler, nationality: event.target.value })} /></label>
            <button className="primary" disabled={travelerBusy || selected.status !== "ACTIVE"}>{travelerBusy ? "Saving…" : "Add traveler"}</button>
          </form>}
        </section>
      </div>
    </>}
  </div>;
}
