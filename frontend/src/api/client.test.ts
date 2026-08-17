import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, installTokenProvider } from './client'
import { ApiError } from './errors'

/**
 * The client is where the backend's four outcomes survive or are lost.
 *
 * These are not security tests and must not be described as any. The `MockMvc` denial tests
 * prove access is refused; what is proved here is that the refusal arrives in the UI as the
 * thing it actually was. A client that turned 409 into a denial screen would tell a peer who
 * had already submitted that they lack permission, which is false, and would send them to
 * ask for access they hold.
 */
describe('api client', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock)
    installTokenProvider(async () => 'stub-token')
  })

  afterEach(() => {
    fetchMock.mockReset()
    vi.unstubAllGlobals()
  })

  /** Awaits a call that is expected to fail, and hands back the typed failure. */
  async function failure(call: Promise<unknown>): Promise<ApiError> {
    try {
      await call
    } catch (error) {
      return error as ApiError
    }
    throw new Error('expected the call to fail, but it succeeded')
  }

  /**
   * A fresh Response per call. A single instance cannot be reused, because a body can only
   * be read once - which the two-request test would otherwise trip over.
   */
  function respond(status: number, body?: unknown) {
    fetchMock.mockImplementation(
      async () =>
        new Response(body === undefined ? '' : JSON.stringify(body), {
          status,
          headers: { 'Content-Type': 'application/json' },
        }),
    )
  }

  it('attaches the bearer token to every request', async () => {
    respond(200, { id: 1 })

    await api.get('/api/me')

    const [, init] = fetchMock.mock.calls[0]
    expect(init.headers.Authorization).toBe('Bearer stub-token')
  })

  it('asks for a token per request, so a refreshed one is picked up', async () => {
    let issued = 0
    installTokenProvider(async () => `token-${++issued}`)
    respond(200, {})

    await api.get('/api/me')
    await api.get('/api/me')

    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer token-1')
    expect(fetchMock.mock.calls[1][1].headers.Authorization).toBe('Bearer token-2')
  })

  it('maps 401 to unauthenticated - the caller has not identified themselves yet', async () => {
    respond(401)
    await expect(api.get('/api/me')).rejects.toMatchObject({ kind: 'unauthenticated' })
  })

  it('maps 403 to forbidden', async () => {
    respond(403, { status: 403, detail: 'Access denied' })
    await expect(api.get('/api/reviews/9')).rejects.toMatchObject({ kind: 'forbidden' })
  })

  it('maps 409 to conflict, not to a denial', async () => {
    // A peer submitting twice, or a rating already calibrated. The caller holds the
    // permission; it is the record's state that refuses, and the UI must say so.
    respond(409, { status: 409, detail: 'Already submitted' })

    const error = await failure(api.post('/api/reviews/9/peer-review', {}))

    expect(error).toBeInstanceOf(ApiError)
    expect(error.failure).toEqual({ kind: 'conflict', detail: 'Already submitted' })
  })

  it('maps 400 to invalid, carrying the message through to the form', async () => {
    respond(400, { status: 400, detail: 'Exactly two peers are required' })

    const error = await failure(api.put('/api/reviews/9/peers', { peerIds: [1] }))

    expect(error.failure).toEqual({
      kind: 'invalid',
      detail: 'Exactly two peers are required',
    })
  })

  it('does not give 404 a kind of its own', async () => {
    // The backend answers "not permitted" and "not there" identically on purpose. A client
    // with a distinct not-found screen would eventually show one where the server meant the
    // other, which is exactly the difference the backend refuses to disclose.
    respond(404)
    const error = await failure(api.get('/api/reviews/9'))

    expect(error.kind).not.toBe('forbidden')
    expect(error.kind).toBe('unknown')
  })

  it('reports an unreachable API without guessing why', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))

    const error = await failure(api.get('/api/me'))

    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(0)
  })

  it('puts query parameters on the URL rather than in a body', async () => {
    respond(200, { content: [] })

    await api.get('/api/reviews', { cycleId: 4, page: 2, size: 25 })

    const [requested] = fetchMock.mock.calls[0]
    expect(requested).toContain('cycleId=4')
    expect(requested).toContain('page=2')
    expect(requested).toContain('size=25')
  })

  it('omits undefined query parameters instead of sending the string "undefined"', async () => {
    respond(200, {})

    await api.get('/api/reviews', { cycleId: 4, departmentId: undefined })

    expect(fetchMock.mock.calls[0][0]).not.toContain('departmentId')
  })
})
