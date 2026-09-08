import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'
import { ApiError } from './errors'
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

/**
 * @param enabled false for accounts that hold no plan at all - the Super Admin (P-9.5) and
 *   Leadership, who are never reviewed. The server refuses them anyway, at step 2 of the
 *   evaluation order; this simply does not ask a question whose answer is always 403.
 */
export function useMyDevelopmentPlan(enabled = true) {
  return useQuery({
    queryKey: ['development-plan', 'me'],
    queryFn: () => api.get<DevelopmentPlan>('/api/plans/development/me'),
    enabled,
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
    mutationFn: (goal: GoalInput) => {
      // Guarded, because the id comes from a separate query and an undefined one used to be
      // interpolated straight into the path - producing a POST to `.../undefined/goals` that
      // the server could only reject, on a screen whose own data had loaded perfectly well.
      // Whoever it happened to saw "add goal" simply not work, with nothing to explain it.
      if (userId === undefined) {
        return Promise.reject(
          new ApiError(0, 'Still working out who you are. Try that again in a moment.'),
        )
      }
      return api.post<Goal>(`/api/plans/development/${userId}/goals`, goal)
    },
    onSuccess: () => {
      void queries.invalidateQueries({ queryKey: planKey })
    },
  })
}

/** The manager putting a drafted goal in front of the employee (P-5.9). */
export function useSubmitGoal(planKey: unknown[]) {
  const queries = useQueryClient()
  return useMutation({
    mutationFn: (goalId: number) =>
      api.post<Goal>(`/api/plans/development/goals/${goalId}/submission`),
    onSuccess: () => {
      void queries.invalidateQueries({ queryKey: planKey })
    },
  })
}

/**
 * The employee accepting a goal. Takes no user id, so the request cannot be pointed at
 * somebody else - the same shape as the self-review, and for the same reason.
 */
export function useAgreeGoal(planKey: unknown[]) {
  const queries = useQueryClient()
  return useMutation({
    mutationFn: (goalId: number) =>
      api.post<Goal>(`/api/plans/development/goals/${goalId}/agreement`),
    onSuccess: () => {
      void queries.invalidateQueries({ queryKey: planKey })
    },
  })
}

/** Progress, written to its own field so it can never restate the goal that was agreed. */
export function useReportProgress(planKey: unknown[]) {
  const queries = useQueryClient()
  return useMutation({
    mutationFn: ({ goalId, note }: { goalId: number; note: string }) =>
      api.put<Goal>(`/api/plans/development/goals/${goalId}/progress`, { note }),
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

/** @param enabled see {@link useMyDevelopmentPlan}. */
export function useMyImprovementPlan(enabled = true) {
  return useQuery({
    queryKey: ['improvement-plan', 'me'],
    queryFn: () => api.get<OwnImprovementPlan>('/api/plans/improvement/me'),
    enabled,
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
