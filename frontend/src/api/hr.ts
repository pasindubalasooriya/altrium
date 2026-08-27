import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'
import type { Calibration, ImprovementPlan, Rating } from './types'

/**
 * HR data access.
 *
 * Everything here is scoped on the server by the grants resolved **this request** (P-2.5).
 * Nothing is cached across a grant change beyond a normal refetch, and **no scope is held in
 * the client at all**: no call below names a department, so there is nothing here that could
 * disagree with the server about what this caller may reach.
 */

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

/**
 * HR sign a rating off unchanged, which is what lets the manager share it (P-4.8).
 *
 * A separate call from calibration rather than a flag on it: "HR moved this" and "HR agreed
 * with this" are different things to have happened, and the audit trail says which.
 */
export function useApproveRating(subjectId: number | undefined, cycleId: number | undefined) {
  const queries = useQueryClient()
  return useMutation({
    mutationFn: (note: string) =>
      api.post<Calibration>(
        `/api/reviews/${subjectId}/rating/approval`,
        { note },
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
