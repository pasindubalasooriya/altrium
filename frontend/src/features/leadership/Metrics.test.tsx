import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Metrics } from './Metrics'
import { installTokenProvider } from '../../api/client'

/**
 * The Leadership screen, and the one property it must never lose: **no way through to a
 * person**.
 *
 * Again, not a security test. The server already sends nothing a drill-down could be built
 * from, and `LeadershipMetricsTest` proves that. What is proved here is that the screen adds
 * nothing back - no link, no name - because a future change that started rendering a
 * department as a link would compile perfectly well.
 */
describe('Leadership metrics', () => {
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

  const metrics = {
    cycle: {
      cycleId: 7,
      label: 'FY2026 Q1',
      status: 'OPEN',
      startDate: '2026-01-01',
      endDate: '2026-04-30',
      openedAt: '2026-01-01T00:00:00Z',
      closedAt: null,
    },
    departments: [
      {
        departmentId: 1,
        departmentName: 'Engineering',
        participants: 9,
        selfReviewsSubmitted: 7,
        managerReviewsSubmitted: 4,
        ratingsSet: 3,
        ratingsReleased: 1,
      },
      {
        departmentId: 2,
        departmentName: 'Sales',
        participants: 6,
        selfReviewsSubmitted: 6,
        managerReviewsSubmitted: 2,
        ratingsSet: 2,
        ratingsReleased: 0,
      },
    ],
    ratingDistribution: {
      NEEDS_IMPROVEMENT: 1,
      MEETS_EXPECTATIONS: 3,
      EXCEEDS_EXPECTATIONS: 1,
    },
  }

  function serve(body: unknown) {
    fetchMock.mockImplementation(async (url: string) =>
      new Response(JSON.stringify(url.includes('/cycles') ? cycles : body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
  }

  function renderPage() {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    return render(
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <Metrics />
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

  it('shows department totals and an organisation-wide row', async () => {
    serve(metrics)
    renderPage()

    await waitFor(() => expect(screen.getByText('Engineering')).toBeInTheDocument())
    expect(screen.getByText('Sales')).toBeInTheDocument()
    // 9 + 6 participants, summed for the organisation.
    expect(screen.getByText('15')).toBeInTheDocument()
  })

  it('renders no link anywhere, so there is no route to an individual', async () => {
    serve(metrics)
    renderPage()

    await waitFor(() => expect(screen.getByText('Engineering')).toBeInTheDocument())
    // The strongest form the client can assert: not that the link points nowhere useful, but
    // that no link exists. There is no endpoint one could point at.
    expect(screen.queryAllByRole('link')).toHaveLength(0)
  })

  it('says a cycle opened with no participants, rather than showing an empty page', async () => {
    // A cycle that fired against an empty cohort is a configuration mistake, and the thing
    // Leadership most need to notice. Tidying it away as "no data" would hide it.
    serve({ ...metrics, departments: [] })
    renderPage()

    await waitFor(() =>
      expect(screen.getByText(/cohort configuration for this quadrimester is wrong/i)).toBeInTheDocument(),
    )
  })
})
