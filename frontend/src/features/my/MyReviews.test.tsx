import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MyReviews } from './MyReviews'
import { installTokenProvider } from '../../api/client'

/**
 * The employee's own record, rendered from `visibleSections`.
 *
 * **This is not a security test and must not be described as one.** The `MockMvc` denial tests
 * prove the server refuses; what is proved here is that the client cannot display peer content
 * even when handed some. That distinction matters: a React test proves a control is hidden,
 * and hiding a control is not access control.
 *
 * The last case is the point of the file. It feeds the component a response the real server
 * would never send - one carrying peer feedback for the subject - and asserts nothing appears.
 * A future refactor that reached for a peer field would fail here rather than in production.
 */
describe('MyReviews', () => {
  const fetchMock = vi.fn()

  const cycles = [
    {
      id: 7,
      label: 'FY2026 Q1',
      financialYear: 2026,
      quadrimesterNo: 1,
      status: 'OPEN',
      startDate: '2026-01-01',
      endDate: '2026-04-30',
      openedAt: '2026-01-01T00:00:00Z',
      closedAt: null,
    },
  ]

  const me = {
    id: 3,
    email: 'john@altrium.test',
    fullName: 'John Alvarez',
    departmentId: 1,
    managerId: 2,
    roles: ['EMPLOYEE'],
    landing: '/my/reviews',
  }

  function serve(record: unknown) {
    fetchMock.mockImplementation(async (url: string) => {
      const body = url.includes('/api/me')
        ? me
        : url.includes('/api/reviews/cycles')
          ? cycles
          : record
      return new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    })
  }

  /** The server's answer for somebody who is not in this cycle's cohort. */
  function serveNotAParticipant() {
    fetchMock.mockImplementation(async (url: string) => {
      if (url.includes('/api/me')) {
        return new Response(JSON.stringify(me), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      if (url.includes('/api/reviews/cycles')) {
        return new Response(JSON.stringify(cycles), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      return new Response(
        JSON.stringify({ title: 'Not Found', detail: 'No review for this person in this cycle' }),
        { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
      )
    })
  }

  function renderPage() {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    return render(
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <MyReviews />
        </MemoryRouter>
      </QueryClientProvider>,
    )
  }

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock)
    installTokenProvider(async () => 'stub-token')
  })

  afterEach(() => {
    fetchMock.mockReset()
    vi.unstubAllGlobals()
  })

  const summary = {
    subjectId: 3,
    subjectName: 'John Alvarez',
    departmentName: 'Engineering',
    managerId: 2,
    subjectActive: true,
    cycleId: 7,
    cycleLabel: 'FY2026 Q1',
  }

  it('renders only the sections the server named', async () => {
    serve({
      summary,
      selfReview: {
        achievements: 'Shipped the migration',
        challenges: null,
        goals: null,
        submittedAt: '2026-02-01T00:00:00Z',
      },
      managerReview: null,
      finalRating: null,
      visibleSections: ['SELF_REVIEW'],
    })

    renderPage()

    await waitFor(() => expect(screen.getByText('Shipped the migration')).toBeInTheDocument())
    expect(
      screen.getByText(/manager has not shared their review with you yet/i),
    ).toBeInTheDocument()
  })

  it('renders a withheld rating exactly as it renders no rating at all', async () => {
    // The server sends the same thing in both cases; this asserts the client does not
    // reintroduce the difference by saying "awaiting release" for one of them.
    serve({
      summary,
      selfReview: null,
      managerReview: null,
      finalRating: null,
      visibleSections: [],
    })

    renderPage()

    await waitFor(() =>
      expect(screen.getByText(/No rating has been shared with you for this cycle/i)).toBeInTheDocument(),
    )
    expect(screen.queryByText(/awaiting/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/withheld/i)).not.toBeInTheDocument()
  })

  it('displays no peer content even when handed some', async () => {
    serve({
      summary,
      selfReview: null,
      managerReview: null,
      finalRating: null,
      // A response the real server never sends to a subject: READ_PEER_REVIEW has no SELF
      // ground. If it somehow arrived, nothing here may render it.
      peerReviews: [{ peerId: 9, peerName: 'Mei Lin', feedback: 'Very thorough', rating: 'EXCEEDS_EXPECTATIONS' }],
      visibleSections: ['PEER_REVIEWS'],
    })

    renderPage()

    await waitFor(() => expect(screen.getByText('FY2026 Q1')).toBeInTheDocument())
    expect(screen.queryByText('Mei Lin')).not.toBeInTheDocument()
    expect(screen.queryByText('Very thorough')).not.toBeInTheDocument()
    // And no count either: knowing two peers exist is knowing something about them.
    expect(screen.queryByText(/peer/i)).not.toBeInTheDocument()
  })

  it('says you are not in this cycle rather than reporting a failure', async () => {
    serveNotAParticipant()
    renderPage()

    // Jane manages three people and is reviewed in a different quadrimester from all of them,
    // so this is her ordinary view of the open cycle. It rendered as "Something went wrong
    // loading this", which sends a manager looking for a server that is fine.
    await waitFor(() => {
      expect(screen.getByText(/not under review in the cycle/i)).toBeInTheDocument()
    })
    expect(screen.queryByText(/something went wrong/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/do not have access/i)).not.toBeInTheDocument()
  })
})
