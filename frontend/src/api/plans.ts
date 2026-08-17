import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'
import type { DevelopmentPlan, Goal, ImprovementPlan, OwnImprovementPlan } from './types'

/**
 * Plan data access.
 *
 * Two things are deliberately missing from this file, and both are policy rather than
 * oversight:
 *
 * - **No hook moves a PIP deadline.** `PUT /plans/improvement/{id}/deadline` exists on the
 *   server in order to refuse everybody. Giving it a hook here would be the first step to
 *   giving it a button, and the rule would quietly become a feature.
 * - **No hook writes a development plan on HR's behalf.** HR read a PDP and never write one
 *   (P-5.1); the write hooks below are used only by the employee and manager consoles.
 */

// ---------------------------------------------------------------- development plan

export function useMyDevelopmentPlan() {
  return useQuery({
    queryKey: ['development-plan', 'me'],
    queryFn: () => api.get<DevelopmentPlan>('/api/plans/development/me'),
  })
}

export function useDevelopmentPlan(userId: number | undefined) {
  return useQuery({
    queryKey: ['development-plan', userId],
    queryFn: () => api.get<DevelopmentPlan>(`/api/plans/development/${userId}`),
    enabled: userId !== undefined,
  })
}

export interface GoalInput {
  title: string
  detail: string
  targetDate: string | null
}

/** `planKey` is what to invalidate: the caller's own plan, or a named person's. */
export function useAddGoal(userId: number | undefined, planKey: unknown[]) {
  const queries = useQueryClient()
  return useMutation({
    mutationFn: (goal: GoalInput) =>
      api.post<Goal>(`/api/plans/development/${userId}/goals`, goal),
    onSuccess: () => {
      void queries.invalidateQueries({ queryKey: planKey })
    },
  })
}

export function useEditGoal(planKey: unknown[]) {
  const queries = useQueryClient()
  return useMutation({
    mutationFn: ({ goalId, title, detail }: { goalId: number; title: string; detail: string }) =>
      api.put<Goal>(`/api/plans/development/goals/${goalId}`, { title, detail }),
    onSuccess: () => {
      void queries.invalidateQueries({ queryKey: planKey })
    },
  })
}

/**
 * Moving a development goal's target date - the manager's, under its own capability (P-5.5).
 *
 * The server refuses this same endpoint when the goal belongs to an improvement plan, because
 * a target date on a PIP goal is a PIP deadline. One answer to "may this date move?".
 */
export function useMoveTargetDate(planKey: unknown[]) {
  const queries = useQueryClient()
  return useMutation({
    mutationFn: ({ goalId, targetDate }: { goalId: number; targetDate: string | null }) =>
      api.put<Goal>(`/api/plans/development/goals/${goalId}/target-date`, { targetDate }),
    onSuccess: () => {
      void queries.invalidateQueries({ queryKey: planKey })
    },
  })
}

export function useApproveGoal(planKey: unknown[]) {
  const queries = useQueryClient()
  return useMutation({
    mutationFn: ({ goalId, approved }: { goalId: number; approved: boolean }) =>
      approved
        ? api.post<Goal>(`/api/plans/development/goals/${goalId}/approval`)
        : api.del<Goal>(`/api/plans/development/goals/${goalId}/approval`),
    onSuccess: () => {
      void queries.invalidateQueries({ queryKey: planKey })
    },
  })
}

export function useRemoveGoal(planKey: unknown[]) {
  const queries = useQueryClient()
  return useMutation({
    mutationFn: (goalId: number) => api.del<void>(`/api/plans/development/goals/${goalId}`),
    onSuccess: () => {
      void queries.invalidateQueries({ queryKey: planKey })
    },
  })
}

// ---------------------------------------------------------------- improvement plan

export function useMyImprovementPlan() {
  return useQuery({
    queryKey: ['improvement-plan', 'me'],
    queryFn: () => api.get<OwnImprovementPlan>('/api/plans/improvement/me'),
  })
}

export function useImprovementPlanHistory(userId: number | undefined) {
  return useQuery({
    queryKey: ['improvement-plan', 'history', userId],
    queryFn: () => api.get<ImprovementPlan[]>(`/api/plans/improvement/${userId}/history`),
    enabled: userId !== undefined,
  })
}

export interface OpenPlanInput {
  consequenceClause: string
  deadline: string
}

export function useOpenImprovementPlan(userId: number | undefined) {
  const queries = useQueryClient()
  return useMutation({
    mutationFn: (input: OpenPlanInput) =>
      api.post<ImprovementPlan>(`/api/plans/improvement/${userId}`, input),
    onSuccess: () => {
      void queries.invalidateQueries({ queryKey: ['improvement-plan'] })
      // Opening a PIP suspends the development plan, so that view is stale too.
      void queries.invalidateQueries({ queryKey: ['development-plan'] })
    },
  })
}

export function useImprovementPlanActions(userId: number | undefined) {
  const queries = useQueryClient()
  const refresh = () => {
    void queries.invalidateQueries({ queryKey: ['improvement-plan'] })
    void queries.invalidateQueries({ queryKey: ['development-plan'] })
  }

  const addGoal = useMutation({
    mutationFn: ({ planId, goal }: { planId: number; goal: GoalInput }) =>
      api.post<Goal>(`/api/plans/improvement/${planId}/goals`, goal),
    onSuccess: refresh,
  })

  const setConsequenceClause = useMutation({
    mutationFn: ({ planId, consequenceClause }: { planId: number; consequenceClause: string }) =>
      api.put<ImprovementPlan>(`/api/plans/improvement/${planId}/consequence-clause`, {
        consequenceClause,
      }),
    onSuccess: refresh,
  })

  const close = useMutation({
    mutationFn: ({ planId, outcome }: { planId: number; outcome: 'pass' | 'fail' }) =>
      api.post<ImprovementPlan>(`/api/plans/improvement/${planId}/${outcome}`),
    onSuccess: refresh,
  })

  return { userId, addGoal, setConsequenceClause, close }
}
