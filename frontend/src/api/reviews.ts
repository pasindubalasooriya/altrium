import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'
import type {
  Calibration,
  Cycle,
  ManagerReviewWritten,
  OwnRating,
  OwnReviewRecord,
  Page,
  PeerAssignment,
  PeerCandidate,
  PeerTask,
  Rating,
  RatingView,
  ReviewRecord,
  ReviewSummary,
  SelfReviewWritten,
} from './types'

/**
 * Review data access.
 *
 * The list endpoint appears twice below, under two names, and returns different rows to a
 * manager and to an employee. That is not two endpoints: the scope is in the SQL `WHERE`
 * clause, so the same call answers "the reviews you may see" for whoever is asking. Naming
 * them separately is for the reader; the request is identical.
 */

export function useCycles() {
  return useQuery({ queryKey: ['cycles'], queryFn: () => api.get<Cycle[]>('/api/reviews/cycles') })
}

/** The reviews the caller may see, paged on the server. */
export function usePermittedReviews(cycleId: number | undefined, page: number, size = 25) {
  return useQuery({
    queryKey: ['reviews', cycleId, page, size],
    queryFn: () => api.get<Page<ReviewSummary>>('/api/reviews', { cycleId, page, size }),
    enabled: cycleId !== undefined,
  })
}

/**
 * The caller's own record.
 *
 * Typed as `OwnReviewRecord`, which has no peer field at all, so nothing downstream can
 * render peer content or count peers even if the server were somehow to send them.
 */
export function useMyReviewRecord(cycleId: number | undefined, myId: number | undefined) {
  return useQuery({
    queryKey: ['review', myId, cycleId],
    queryFn: () => api.get<OwnReviewRecord>(`/api/reviews/${myId}`, { cycleId }),
    enabled: cycleId !== undefined && myId !== undefined,
  })
}

/** Somebody else's record, as their manager or HR-in-scope. */
export function useReviewRecord(subjectId: number | undefined, cycleId: number | undefined) {
  return useQuery({
    queryKey: ['review', subjectId, cycleId],
    queryFn: () => api.get<ReviewRecord>(`/api/reviews/${subjectId}`, { cycleId }),
    enabled: cycleId !== undefined && subjectId !== undefined,
  })
}

export function useMyRating(cycleId: number | undefined) {
  return useQuery({
    queryKey: ['my-rating', cycleId],
    queryFn: () => api.get<OwnRating>('/api/reviews/my-rating', { cycleId }),
    enabled: cycleId !== undefined,
  })
}

export interface SelfReviewInput {
  achievements: string
  challenges: string
  goals: string
}

export function useSaveSelfReview(cycleId: number | undefined) {
  const queries = useQueryClient()
  return useMutation({
    mutationFn: ({ input, submit }: { input: SelfReviewInput; submit: boolean }) =>
      api.put<SelfReviewWritten>('/api/reviews/self-review', input, { cycleId, submit }),
    onSuccess: () => {
      void queries.invalidateQueries({ queryKey: ['review'] })
    },
  })
}

export function useMyPeerAssignments(cycleId: number | undefined) {
  return useQuery({
    queryKey: ['my-peer-assignments', cycleId],
    queryFn: () => api.get<PeerTask[]>('/api/reviews/my-peer-assignments', { cycleId }),
    enabled: cycleId !== undefined,
  })
}

export function useSubmitPeerReview(cycleId: number | undefined) {
  const queries = useQueryClient()
  return useMutation({
    mutationFn: ({
      subjectId,
      feedback,
      rating,
    }: {
      subjectId: number
      feedback: string
      rating: Rating
    }) =>
      api.post(`/api/reviews/${subjectId}/peer-review`, { feedback, rating }, { cycleId }),
    onSuccess: () => {
      void queries.invalidateQueries({ queryKey: ['my-peer-assignments'] })
    },
  })
}

export function useAssignedPeers(subjectId: number | undefined, cycleId: number | undefined) {
  return useQuery({
    queryKey: ['peers', subjectId, cycleId],
    queryFn: () => api.get<PeerAssignment[]>(`/api/reviews/${subjectId}/peers`, { cycleId }),
    enabled: cycleId !== undefined && subjectId !== undefined,
  })
}

/**
 * Candidates for peer assignment, searched and paged on the server.
 *
 * The exclusions - the subject, their manager, anybody deactivated - are applied in the
 * query. The client does not re-apply them: the rule is the server's, and a client filter
 * that drifted would hide a real disagreement rather than surface it.
 */
export function usePeerCandidates(subjectId: number | undefined, name: string) {
  return useQuery({
    queryKey: ['peer-candidates', subjectId, name],
    queryFn: () =>
      api.get<Page<PeerCandidate>>(`/api/reviews/${subjectId}/peer-candidates`, {
        name: name || undefined,
        size: 10,
      }),
    enabled: subjectId !== undefined,
  })
}

export function useAssignPeers(subjectId: number | undefined, cycleId: number | undefined) {
  const queries = useQueryClient()
  return useMutation({
    mutationFn: (peerIds: number[]) =>
      api.put<PeerAssignment[]>(`/api/reviews/${subjectId}/peers`, { peerIds }, { cycleId }),
    onSuccess: () => {
      void queries.invalidateQueries({ queryKey: ['peers', subjectId] })
    },
  })
}

export function useSaveManagerReview(subjectId: number | undefined, cycleId: number | undefined) {
  const queries = useQueryClient()
  return useMutation({
    mutationFn: ({ feedback, submit }: { feedback: string; submit: boolean }) =>
      api.put<ManagerReviewWritten>(
        `/api/reviews/${subjectId}/manager-review`,
        { feedback },
        { cycleId, submit },
      ),
    onSuccess: () => {
      void queries.invalidateQueries({ queryKey: ['review', subjectId] })
    },
  })
}

export function useSetRating(subjectId: number | undefined, cycleId: number | undefined) {
  const queries = useQueryClient()
  return useMutation({
    mutationFn: (rating: Rating) =>
      api.put<RatingView>(`/api/reviews/${subjectId}/rating`, { rating }, { cycleId }),
    onSuccess: () => {
      void queries.invalidateQueries({ queryKey: ['review', subjectId] })
    },
  })
}

export function useReleaseRating(subjectId: number | undefined, cycleId: number | undefined) {
  const queries = useQueryClient()
  return useMutation({
    mutationFn: () =>
      api.post<RatingView>(`/api/reviews/${subjectId}/rating/release`, undefined, { cycleId }),
    onSuccess: () => {
      void queries.invalidateQueries({ queryKey: ['review', subjectId] })
    },
  })
}

/**
 * The calibration trail.
 *
 * Read by the manager and HR-in-scope, and never by the subject - `READ_RATING_AUDIT` has no
 * `SELF` ground, so this hook has no place in the employee console.
 */
export function useCalibrationHistory(subjectId: number | undefined, cycleId: number | undefined) {
  return useQuery({
    queryKey: ['calibration', subjectId, cycleId],
    queryFn: () =>
      api.get<Calibration[]>(`/api/reviews/${subjectId}/rating/calibration`, { cycleId }),
    enabled: cycleId !== undefined && subjectId !== undefined,
  })
}
