import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { Dashboard as DashboardData } from '../../api/dashboard'

const useDashboard = vi.fn()
const useSelectedCycle = vi.fn()

vi.mock('../../api/dashboard', () => ({ useDashboard: (id?: number) => useDashboard(id) }))
vi.mock('../../components/CycleSelect', () => ({
  useSelectedCycle: () => useSelectedCycle(),
  CycleSelect: () => null,
}))
// Stubbed as an HR user, so the export control renders. Whether a manager may export is not
// this test's claim - `ExportTest` proves the endpoint refuses one.
vi.mock('../../auth/useCurrentUser', () => ({
  useCurrentUser: () => ({ data: { id: 1, fullName: 'Hana Iqbal', roles: ['EMPLOYEE', 'HR'] } }),
  hasRole: (roles: string[], role: string) => roles.includes(role),
}))

const { Dashboard } = await import('./Dashboard')

/**
 * The manager's and HR's dashboard.
 *
 * **This is not a test of who is counted.** Which people a total covers is decided in the SQL
 * and proved by `DashboardTest`, calling the endpoint with a minted token. Feeding a component
 * numbers and reading them back proves only that the mock is consistent with itself.
 *
 * What is proved here is the one judgment the client actually makes, and it is a real one: the
 * server sends the same zeroes for "you have no dashboard" and for "your team has not started",
 * distinguished only by a flag. Getting that backwards would tell a manager on the morning a
 * cycle opens that the screen is not for them.
 */
describe('Dashboard', () => {
  const data = (over: Partial<DashboardData>): DashboardData => ({
    cycleId: 1,
    cycleLabel: 'FY2026 Q2',
    cycleStatus: 'OPEN',
    scopeIsEmpty: false,
    participants: 0,
    selfReviewsSubmitted: 0,
    managerReviewsSubmitted: 0,
    peerReviewsSubmitted: 0,
    ratingsSet: 0,
    ratingsReleased: 0,
    ratingDistribution: {},
    ...over,
  })

  const show = (over: Partial<DashboardData>) => {
    useSelectedCycle.mockReturnValue({ cycles: [], cycleId: 1, setCycleId: vi.fn() })
    useDashboard.mockReturnValue({ data: data(over), isPending: false, error: null })
    // The export control below the tiles holds a mutation, so the tree needs a client even
    // though nothing here fetches.
    render(
      <QueryClientProvider client={new QueryClient()}>
        <Dashboard />
      </QueryClientProvider>,
    )
  }

  it('explains an empty scope rather than showing a wall of zeroes', () => {
    show({ scopeIsEmpty: true })

    expect(screen.getByText(/no dashboard for you/i)).toBeInTheDocument()
    expect(screen.queryByText('Under review')).not.toBeInTheDocument()
  })

  it('shows the tiles when the caller has a scope but nobody has started', () => {
    show({ scopeIsEmpty: false, participants: 4 })

    // The same zeroes as above, and a completely different thing to say about them.
    expect(screen.queryByText(/no dashboard for you/i)).not.toBeInTheDocument()
    expect(screen.getByText('Under review')).toBeInTheDocument()
    expect(screen.getByText('Self-reviews in')).toBeInTheDocument()
  })

  it('counts peer reviews against two per person, not one', () => {
    show({ scopeIsEmpty: false, participants: 3, peerReviewsSubmitted: 2 })

    // Three people means six peer reviews are owed (P-3.6), so "2 of 6" is the honest
    // fraction. "2 of 3" would read as nearly done when it is a third done.
    expect(screen.getByText('of 6')).toBeInTheDocument()
  })

  it('drops the denominator rather than printing "of 0"', () => {
    show({ scopeIsEmpty: false, participants: 2, ratingsSet: 0 })

    // "Shared with the employee" counts against ratings set, which is zero here.
    expect(screen.queryByText('of 0')).not.toBeInTheDocument()
  })
})
