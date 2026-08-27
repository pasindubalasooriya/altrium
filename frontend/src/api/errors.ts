/**
 * The four outcomes the backend distinguishes, kept distinct here.
 *
 * Collapsing these into one "something went wrong" toast would throw away the exact
 * distinction the backend went to trouble to make. A peer submitting a second review, or a
 * manager rating an employee whose rating HR has already calibrated, is **not** being denied
 * access - they hold the permission and it is the record's state that refuses. Showing that
 * person an access-denied screen would be a lie about their own authority.
 */
export type ApiFailure =
  /** No credentials, or expired ones. The caller has not identified themselves yet. */
  | { kind: 'unauthenticated' }
  /**
   * Access denied. Deliberately indistinguishable from "no such record": the backend
   * returns the same body either way, so the client must not try to tell them apart.
   */
  | { kind: 'forbidden' }
  /** The caller may do this; the record's state says not now. Renders on the form. */
  | { kind: 'conflict'; detail: string }
  /** Failed validation. Renders against the fields. */
  | { kind: 'invalid'; detail: string }
  /** Anything else, including the network being down. */
  | { kind: 'unknown'; status: number; detail: string }

export class ApiError extends Error {
  readonly failure: ApiFailure
  readonly status: number

  constructor(status: number, detail: string) {
    super(detail || `Request failed with ${status}`)
    this.name = 'ApiError'
    this.status = status
    this.failure = classify(status, detail)
  }

  get kind(): ApiFailure['kind'] {
    return this.failure.kind
  }
}

function classify(status: number, detail: string): ApiFailure {
  switch (status) {
    case 401:
      return { kind: 'unauthenticated' }
    case 403:
      return { kind: 'forbidden' }
    case 409:
      return { kind: 'conflict', detail }
    case 400:
    case 422:
      return { kind: 'invalid', detail }
    default:
      return { kind: 'unknown', status, detail }
  }
}

/**
 * A 404 is treated as `unknown` and stays one, because a not-found screen must never stand in
 * for a denial: the backend answers "not permitted" and "not there" identically on purpose,
 * and a client that had a general not-found screen would eventually show it where the server
 * had meant the other.
 *
 * {@link isNotFound} is the one narrow exception, and it is narrow on purpose. See its note.
 */
export function isForbidden(error: unknown): boolean {
  return error instanceof ApiError && error.kind === 'forbidden'
}

export function isUnauthenticated(error: unknown): boolean {
  return error instanceof ApiError && error.kind === 'unauthenticated'
}

/** The message to put next to a form control after a failed write. */
export function writeMessage(error: unknown): string | null {
  if (!(error instanceof ApiError)) {
    return error ? 'Something went wrong. Try again.' : null
  }
  switch (error.failure.kind) {
    case 'conflict':
      return error.failure.detail || 'This is no longer possible in the record’s current state.'
    case 'invalid':
      return error.failure.detail || 'Check the values and try again.'
    case 'forbidden':
      return 'You do not have access to do this.'
    case 'unauthenticated':
      return 'Your session has expired. Sign in again.'
    default:
      return 'Something went wrong. Try again.'
  }
}

/**
 * A 404, for the handful of screens where it cannot be a denial in disguise.
 *
 * **Use this only where the subject is the caller themselves.** `readRecord` makes its access
 * decision first and looks for the record second, so a 404 is reachable only by somebody
 * already permitted to see that person (P-0.5). Where the caller is asking about themselves
 * the decision is never in doubt, which leaves exactly one meaning: they are not under review
 * in this cycle. Nothing is disclosed by saying so - it is their own participation.
 *
 * On any screen pointed at somebody else this would be a leak waiting to happen, which is why
 * it is a named helper with this note attached rather than a status check inline.
 */
export function isNotFound(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404
}
