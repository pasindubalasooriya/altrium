import { useQuery } from '@tanstack/react-query'
import { api } from './client'
import type { CycleStatus, Rating } from './types'

/**
 * Role-based dashboards for the manager and for HR (scenario section 13).
 *
 * One endpoint for both, because there is one rule. The totals cover whoever the caller may
 * open a review for, decided in the server's `WHERE` clause, so a manager and an HR user get
 * different numbers from an identical request. Naming two hooks would suggest two endpoints
 * and invite a second, divergent scope.
 *
 * Leadership are not served here. Their metrics are `/api/leadership/metrics`, which is
 * unscoped by department; asking this endpoint returns an empty dashboard rather than a denial.
 */

export interface Dashboard {
  cycleId: number
  cycleLabel: string
  cycleStatus: CycleStatus
  /**
   * True when the caller has grounds over nobody in this cycle.
   *
   * The zeroes then mean "there is no dashboard for you" rather than "nobody has started",
   * and only one of those deserves an explanation on screen. Reported by the server rather
   * than guessed from `participants === 0`, which is also what a manager sees on the morning
   * a cycle opens.
   */
  scopeIsEmpty: boolean
  participants: number
  selfReviewsSubmitted: number
  managerReviewsSubmitted: number
  peerReviewsSubmitted: number
  ratingsSet: number
  ratingsReleased: number
  /** Only the ratings actually given. A rating nobody received is absent, not zero. */
  ratingDistribution: Partial<Record<Rating, number>>
}

export function useDashboard(cycleId: number | undefined) {
  return useQuery({
    queryKey: ['dashboard', cycleId],
    queryFn: () => api.get<Dashboard>('/api/dashboard', { cycleId }),
    enabled: cycleId !== undefined,
  })
}
