import { config } from '../config'
import { ApiError } from './errors'

/**
 * The one place a request leaves the app.
 *
 * Nothing else calls `fetch`. One place to attach the bearer token is one place to get it
 * wrong, and a screen that quietly fetched without one would fail as a 401 that looked like
 * a session problem rather than the bug it was.
 *
 * The token provider is injected rather than imported from the Asgardeo SDK, because this
 * module is also used by tests, where there is no identity provider and no browser session.
 */

type TokenProvider = () => Promise<string>

let getToken: TokenProvider = async () => {
  throw new Error('API client used before a token provider was installed')
}

/** Called once, from the auth provider, as soon as the SDK can mint tokens. */
export function installTokenProvider(provider: TokenProvider): void {
  getToken = provider
}

export type QueryValue = string | number | boolean | undefined | null

function url(path: string, query?: Record<string, QueryValue>): string {
  const target = new URL(path, config.apiBaseUrl)
  for (const [key, value] of Object.entries(query ?? {})) {
    if (value !== undefined && value !== null) {
      target.searchParams.set(key, String(value))
    }
  }
  return target.toString()
}

/**
 * Pulls a message out of the backend's RFC 7807 problem response.
 *
 * A 403 body is deliberately identical everywhere and says nothing useful, so its detail is
 * never surfaced - `<Forbidden/>` has one wording of its own. It is the 400 and 409 bodies
 * that are worth reading, because those are the ones that tell the user something they can
 * act on.
 */
async function describe(response: Response): Promise<string> {
  try {
    const body = await response.json()
    return typeof body?.detail === 'string' ? body.detail : ''
  } catch {
    return ''
  }
}

async function request<T>(
  method: string,
  path: string,
  options: { query?: Record<string, QueryValue>; body?: unknown } = {},
): Promise<T> {
  let response: Response
  try {
    response = await fetch(url(path, options.query), {
      method,
      headers: {
        Authorization: `Bearer ${await getToken()}`,
        ...(options.body === undefined ? {} : { 'Content-Type': 'application/json' }),
      },
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    })
  } catch {
    // The browser refuses to say whether this was the network, DNS or a CORS rejection, so
    // neither do we. Guessing "CORS" here would be wrong most of the time it was shown.
    throw new ApiError(0, 'Could not reach the Altrium API.')
  }

  if (!response.ok) {
    throw new ApiError(response.status, await describe(response))
  }

  if (response.status === 204) {
    return undefined as T
  }

  const text = await response.text()
  return (text ? JSON.parse(text) : undefined) as T
}

export const api = {
  get: <T>(path: string, query?: Record<string, QueryValue>) =>
    request<T>('GET', path, { query }),

  post: <T>(path: string, body?: unknown, query?: Record<string, QueryValue>) =>
    request<T>('POST', path, { body, query }),

  put: <T>(path: string, body?: unknown, query?: Record<string, QueryValue>) =>
    request<T>('PUT', path, { body, query }),

  del: <T>(path: string, query?: Record<string, QueryValue>) =>
    request<T>('DELETE', path, { query }),
}
