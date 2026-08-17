import { useQuery } from '@tanstack/react-query'
import { api } from './client'
import type { Rating } from './types'

/**
 * Leadership metrics (P-7.1).
 *
 * Note what these types cannot express: **there is no id of any person anywhere in them**, so
 * no component built on this module can link through to an individual. That is P-7.1 enforced
 * by the shape of the data rather than by a link somebody remembered not to render, and it
 * matches the server, which sends nothing a link could be built from.
 *
 * There is also no per-department rating distribution, and no hook that could ask for one. A
 * department with one participant would make its distribution that person's rating, and
 * Leadership hold no grounds to read an individual rating (P-7.5).
 */

export interface DepartmentTotals {
  departmentId: number
  departmentName: string
  participants: number
  selfReviewsSubmitted: number
  managerReviewsSubmitted: number
  ratingsSet: number
  ratingsReleased: number
}

export interface LeadershipMetrics {
  cycle: {
    cycleId: number
    label: string
    status: string
    startDate: string
    endDate: string
    openedAt: string | null
    closedAt: string | null
  }
  departments: DepartmentTotals[]
  /** Every scale value is present, zeroes included, so a chart cannot change shape silently. */
  ratingDistribution: Record<Rating, number>
}

export function useLeadershipMetrics(cycleId: number | undefined) {
  return useQuery({
    queryKey: ['leadership-metrics', cycleId],
    queryFn: () => api.get<LeadershipMetrics>('/api/leadership/metrics', { cycleId }),
    enabled: cycleId !== undefined,
  })
}
