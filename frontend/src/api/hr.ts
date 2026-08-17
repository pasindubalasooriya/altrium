import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'
import type { Calibration, ImprovementPlan, Rating } from './types'

/**
 * HR data access.
 *
 * Everything here is scoped on the server by the grants resolved **this request** (P-2.5).
 * Nothing is cached across a grant change beyond a normal refetch, and no scope is held in
 * the client: `useHrScope` is a display of what the server resolved, never an input to any
 * other call.
 */

export interface ScopedDepartment {
  id: number
  name: string
  isOwnDepartment: boolean
}

/**
 * `note` is written by the server in plain language, and this client displays it rather than
 * composing its own. The rule it explains - why your own department is missing - is the one
 * most likely to look like a bug, and two wordings of it would eventually disagree.
 */
export interface HrScope {
  departments: ScopedDepartment[]
  ownDepartmentId: number | null
  ownDepartmentExcluded: boolean
  ownDepartmentLiftedByExplicitGrant: boolean
  note: string
}

export function useHrScope() {
  return useQuery({ queryKey: ['hr-scope'], queryFn: () => api.get<HrScope>('/api/hr/scope') })
}

export interface DepartmentProgress {
  departmentId: number
  departmentName: string
  participants: number
  selfReviewsSubmitted: number
  managerReviewsSubmitted: number
  peerAssignmentsMade: number
  peerReviewsSubmitted: number
  ratingsSet: number
  ratingsReleased: number
}

export interface CycleMonitoring {
  cycle: {
    cycleId: number
    label: string
    status: string
    startDate: string
    endDate: string
    openedAt: string | null
    closedAt: string | null
  }
  departments: DepartmentProgress[]
}

export function useCycleMonitoring(cycleId: number | undefined) {
  return useQuery({
    queryKey: ['monitoring', cycleId],
    queryFn: () => api.get<CycleMonitoring>(`/api/cycles/${cycleId}/monitoring`),
    enabled: cycleId !== undefined,
  })
}

export function useCalibrate(subjectId: number | undefined, cycleId: number | undefined) {
  const queries = useQueryClient()
  return useMutation({
    mutationFn: ({ rating, note }: { rating: Rating; note: string }) =>
      api.put<Calibration>(
        `/api/reviews/${subjectId}/rating/calibration`,
        { rating, note },
        { cycleId },
      ),
    onSuccess: () => {
      void queries.invalidateQueries({ queryKey: ['calibration', subjectId] })
      void queries.invalidateQueries({ queryKey: ['review', subjectId] })
    },
  })
}

/** The plans HR oversee, including ones still awaiting their co-signature. */
export function useImprovementPlanQueue() {
  return useQuery({
    queryKey: ['improvement-plan', 'queue'],
    queryFn: () => api.get<ImprovementPlan[]>('/api/plans/improvement'),
  })
}

export function useCosign() {
  const queries = useQueryClient()
  return useMutation({
    mutationFn: (planId: number) =>
      api.post<ImprovementPlan>(`/api/plans/improvement/${planId}/cosign`),
    onSuccess: () => {
      void queries.invalidateQueries({ queryKey: ['improvement-plan'] })
    },
  })
}

export function useRecordWitness() {
  const queries = useQueryClient()
  return useMutation({
    mutationFn: ({ planId, witnessName }: { planId: number; witnessName: string }) =>
      api.post<ImprovementPlan>(`/api/plans/improvement/${planId}/witness`, { witnessName }),
    onSuccess: () => {
      void queries.invalidateQueries({ queryKey: ['improvement-plan'] })
    },
  })
}
