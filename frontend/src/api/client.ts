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

/**
 * How long a request will wait for the provider before giving up.
 *
 * There has to be a limit. A request that waits forever is a screen that spins forever, which
 * is harder to diagnose than a failure - but the wait itself is the point, see below.
 */
const PROVIDER_TIMEOUT_MS = 10_000

let provider: TokenProvider | null = null
let announce: (installed: TokenProvider) => void = () => {}
let firstInstall = new Promise<TokenProvider>((resolve) => {
  announce = resolve
})

/**
 * Called from the auth provider as soon as the SDK can mint tokens.
 *
 * Safe to call repeatedly. It is called on every render rather than from an effect, and the
 * reason is the bug this replaced: React runs effects child-first, so an effect in a component
 * that wraps the whole app runs <em>after</em> every screen below it has mounted and fired its
 * first query. Those queries found no provider and failed, and the failure was indistinguishable
 * from an expired session - so a refresh reliably showed "your session has ended" while the
 * session was perfectly healthy.
 */
export function installTokenProvider(next: TokenProvider): void {
  provider = next
  announce(next)
}

/**
 * The provider, waiting for it if it is not installed yet.
 *
 * Waiting rather than throwing is what makes mount order stop mattering. Installing during
 * render already fixes the ordering; this makes the client robust to it being got wrong again,
 * which is worth having, because the failure it produced looked like something else entirely
 * and cost far more to diagnose than it should have.
 */
async function currentProvider(): Promise<TokenProvider> {
  if (provider) {
    return provider
  }
  return Promise.race([
    firstInstall,
    new Promise<TokenProvider>((_, reject) =>
      setTimeout(
        () => reject(new Error('No token provider was installed; is AltriumAuthProvider mounted?')),
        PROVIDER_TIMEOUT_MS,
      ),
    ),
  ])
}

const getToken: TokenProvider = async () => (await currentProvider())()

/**
 * Puts the module back to "nothing installed yet", for the one test that needs it.
 *
 * Exported only so the race above can be reproduced: every other test installs a provider in
 * its setup, which is precisely the state that hid this bug for so long. Nothing in the app
 * calls this.
 */
export function resetTokenProviderForTest(): void {
  provider = null
  firstInstall = new Promise<TokenProvider>((resolve) => {
    announce = resolve
  })
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

/**
 * The bearer token, or a 401 that says what actually happened.
 *
 * A failure here is a session problem and never a server problem, so it is classified as 401
 * and reaches the UI as "sign in again" rather than as an outage. The two are indistinguishable
 * to a person looking at a blank screen, and only one of them is theirs to act on.
 */
async function bearerToken(): Promise<string> {
  try {
    const token = await getToken()
    if (!token) {
      // The SDK can resolve with nothing rather than rejecting, which is the same dead session
      // wearing a different shape. Treated identically, or an empty Authorization header goes
      // to the server and comes back as a 401 nobody can explain.
      console.warn('[altrium] the auth SDK returned no access token; treating as signed out')
      throw new ApiError(401, 'Your session has ended.')
    }
    return token
  } catch (cause) {
    if (cause instanceof ApiError) {
      throw cause
    }
    // Logged, because this and a 401 from the server produce the same screen and have
    // completely different causes: this one never reached the network.
    console.warn('[altrium] could not obtain an access token; treating as signed out', cause)
    throw new ApiError(401, 'Your session has ended.')
  }
}

async function request<T>(
  method: string,
  path: string,
  options: { query?: Record<string, QueryValue>; body?: unknown } = {},
): Promise<T> {
  // Obtained before the try below, and deliberately outside it. The SDK rejects when there is
  // no live session left to mint from - the refresh token has expired, or the session was
  // ended in another tab - and while this call sat inside the fetch guard, that rejection was
  // reported as "could not reach the Altrium API". The backend was invariably running; the
  // person had simply been away for an hour, and was sent to go and check a server.
  const token = await bearerToken()

  let response: Response
  try {
    response = await fetch(url(path, options.query), {
      method,
      headers: {
        Authorization: `Bearer ${token}`,
        ...(options.body === undefined ? {} : { 'Content-Type': 'application/json' }),
      },
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    })
  } catch (cause) {
    // The browser refuses to say whether this was the network, DNS or a CORS rejection, so
    // neither do we. Guessing "CORS" here would be wrong most of the time it was shown.
    console.warn('[altrium] fetch itself failed - network, DNS or CORS', method, path, cause)
    throw new ApiError(0, 'Could not reach the Altrium API.')
  }

  if (!response.ok) {
    if (response.status === 401) {
      // The other route to the same screen: a token was obtained and the server rejected it.
      // Distinguished here only in the log, because to the person looking at it the situation
      // is identical - they have to sign in again either way.
      console.warn('[altrium] the server rejected the access token (401)', method, path)
    }
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

  /**
   * Fetches a file rather than JSON, for exports.
   *
   * A plain `<a href>` cannot be used for these: the endpoint needs an `Authorization` header
   * and a link sends none, so the browser would be answered with a 401 and show a broken
   * download. The bytes are fetched with the same token as everything else and handed back as
   * a blob for the caller to save.
   *
   * The filename comes from the server's `Content-Disposition`, because the server is what
   * knows which cycle and which day the file covers. Falling back to a name invented here
   * would produce a plausible file with a wrong label, which is worse than an ugly one.
   */
  download: async (path: string, query?: Record<string, QueryValue>) => {
    const token = await bearerToken()

    let response: Response
    try {
      response = await fetch(url(path, query), {
        method: 'GET',
        headers: { Authorization: `Bearer ${token}` },
      })
    } catch (cause) {
      console.warn('[altrium] fetch itself failed - network, DNS or CORS', 'GET', path, cause)
      throw new ApiError(0, 'Could not reach the Altrium API.')
    }

    if (!response.ok) {
      throw new ApiError(response.status, await describe(response))
    }

    const disposition = response.headers.get('Content-Disposition') ?? ''
    const match = /filename="?([^";]+)"?/i.exec(disposition)

    return { blob: await response.blob(), filename: match?.[1] ?? 'altrium-export' }
  },
}
