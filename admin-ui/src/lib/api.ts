export type Page<T> = { items: T[]; page: number; size: number; total: number };

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
    this.name = "ApiError";
  }
}

export type ApiContext = {
  token: string;
  onUnauthorized: () => void;
};

type ApiOptions = {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  headers?: Record<string, string>;
  /** Aborting drops the request; the promise then rejects with an AbortError, which callers ignore. */
  signal?: AbortSignal;
};

export async function apiResult<T>(context: ApiContext, path: string, options: ApiOptions = {}): Promise<{ status: number; data: T }> {
  let response: Response;
  try {
    response = await fetch(path, {
      method: options.method ?? "GET",
      credentials: "same-origin",
      signal: options.signal,
      headers: {
        Authorization: `Bearer ${context.token}`,
        ...(options.body === undefined ? {} : { "Content-Type": "application/json" }),
        ...options.headers,
      },
      ...(options.body === undefined ? {} : { body: JSON.stringify(options.body) }),
    });
  } catch (caught) {
    if (options.signal?.aborted) throw caught;
    throw new ApiError(0, "NETWORK_UNAVAILABLE", "The service could not be reached. Try again.");
  }

  if (response.status === 401) {
    context.onUnauthorized();
    throw new ApiError(401, "SESSION_EXPIRED", "Your session expired. Sign in again.");
  }
  if (!response.ok) {
    let payload: { code?: string; message?: string; fieldErrors?: Record<string, string> } = {};
    try { payload = await response.json(); } catch { /* Some gateway errors have no JSON body. */ }
    const code = payload.code ?? `HTTP_${response.status}`;
    const fieldDetails = payload.fieldErrors ? ` ${Object.values(payload.fieldErrors).join(" ")}` : "";
    const message = response.status === 403 ? "Your role does not allow this action."
      : response.status === 404 ? "This record does not exist or is not available to you."
      : response.status === 503 ? "A service is unavailable. Try again."
        : payload.message ? `${payload.message} (${code}).${fieldDetails}`
          : `Request failed (${code}).`;
    throw new ApiError(response.status, code, message);
  }
  if (response.status === 204) return { status: response.status, data: undefined as T };
  const text = await response.text();
  return { status: response.status, data: (text ? JSON.parse(text) : undefined) as T };
}

export async function api<T>(context: ApiContext, path: string, options: ApiOptions = {}): Promise<T> {
  return (await apiResult<T>(context, path, options)).data;
}

export async function login(username: string, password: string): Promise<{ accessToken: string; expiresAt: number }> {
  let response: Response;
  try {
    response = await fetch("/api/auth/login", {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password }),
    });
  } catch {
    throw new ApiError(0, "NETWORK_UNAVAILABLE", "The authentication service could not be reached.");
  }
  if (!response.ok) {
    const message = response.status === 429 ? "Too many attempts. Try again later."
      : response.status === 503 ? "The authentication service is unavailable. Try again."
        : response.status === 403 ? "Administrator access is required."
          : response.status === 401 ? "Invalid email or password."
            : `Sign-in request failed (HTTP_${response.status}).`;
    throw new ApiError(response.status, "LOGIN_FAILED", message);
  }
  return response.json();
}

export function errorMessage(error: unknown): string {
  return error instanceof ApiError ? error.message : "Something went wrong. Try again.";
}

export function newKey(): string { return crypto.randomUUID(); }

export async function allPages<T>(context: ApiContext, path: string, pageParameter = "page"): Promise<T[]> {
  const items: T[] = [];
  for (let page = 0; page < 1000; page++) {
    const separator = path.includes("?") ? "&" : "?";
    const result = await api<Page<T>>(context, `${path}${separator}${pageParameter}=${page}&size=100`);
    items.push(...result.items);
    if (items.length >= result.total || result.items.length === 0) return items;
  }
  throw new ApiError(0, "TOO_MANY_RESULTS", "The collection is too large to load here.");
}

export type Me = {
  userId: string; email: string | null; firstName: string | null; lastName: string | null;
  role: "ADMIN" | "TRAVEL_MANAGER" | "TRAVELER"; status: string; memberSince: string | null;
};

/** The signed-in user as the server sees them; the dashboard never trusts a stored role. */
export async function fetchMe(context: ApiContext): Promise<Me> {
  return api<Me>(context, "/api/me");
}

async function publicPost(path: string, body: unknown): Promise<Response> {
  try {
    return await fetch(path, {
      method: "POST", credentials: "same-origin",
      headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, "NETWORK_UNAVAILABLE", "The service could not be reached. Try again.");
  }
}

export async function register(body: { email: string; firstName: string; lastName: string; password: string }): Promise<void> {
  const response = await publicPost("/api/registrations", body);
  if (response.ok) return;
  const code = response.status === 409 ? "EMAIL_EXISTS" : `HTTP_${response.status}`;
  const message = response.status === 409 ? "An account with this email already exists."
    : response.status === 400 ? "Check the fields. Passwords need at least 10 characters."
      : "Registration failed. Try again.";
  throw new ApiError(response.status, code, message);
}

export async function completePasswordSetup(token: string, password: string): Promise<void> {
  const response = await publicPost("/api/password-setup", { token, password });
  if (response.ok) return;
  const message = response.status === 400 ? "This link is invalid, expired or already used. Ask an administrator for a new one."
    : "The password could not be set. Try again.";
  throw new ApiError(response.status, "PASSWORD_SETUP_FAILED", message);
}

/** Query string for GET /api/search/travels; empty filters are left out. */
export function searchPath(text: string, filters: Record<string, string>, page = 0, size = 10): string {
  const params = new URLSearchParams();
  if (text.trim()) params.set("q", text.trim());
  for (const [name, value] of Object.entries(filters)) if (value.trim()) params.set(name, value.trim());
  params.set("page", String(page));
  params.set("size", String(size));
  return `/api/search/travels?${params.toString()}`;
}

export function autocompletePath(text: string, limit = 8): string {
  return `/api/search/autocomplete?${new URLSearchParams({ q: text.trim(), limit: String(limit) }).toString()}`;
}

export function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === "AbortError";
}
