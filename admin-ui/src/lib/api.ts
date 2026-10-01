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
  method?: "GET" | "POST" | "PATCH" | "DELETE";
  body?: unknown;
  headers?: Record<string, string>;
};

export async function apiResult<T>(context: ApiContext, path: string, options: ApiOptions = {}): Promise<{ status: number; data: T }> {
  let response: Response;
  try {
    response = await fetch(path, {
      method: options.method ?? "GET",
      credentials: "same-origin",
      headers: {
        Authorization: `Bearer ${context.token}`,
        ...(options.body === undefined ? {} : { "Content-Type": "application/json" }),
        ...options.headers,
      },
      ...(options.body === undefined ? {} : { body: JSON.stringify(options.body) }),
    });
  } catch {
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
    const message = response.status === 403 ? "Administrator access is required."
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
          : response.status === 401 ? "Invalid administrator credentials."
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
