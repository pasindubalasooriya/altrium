import { useQuery } from '@tanstack/react-query'
import { api } from './client'
import type { TimelineEntry } from './types'

/**
 * History and carry-over (scenario section 5 step 9, P-4.9).
 *
 * Two calls to one endpoint pair, and the difference between them is who is asking rather than
 * what is asked. The `/me` route takes no id at all, so this module offers no way to spell
 * "somebody else's history" through it - the same shape the self-review and plan endpoints
 * take, and for the same reason.
 *
 * Neither call is cycle-scoped. That is the point of the feature: a timeline that took a cycle
 * would be the screen this replaces.
 */

/**
 * The caller's own timeline, newest cycle first.
 *
 * Every cycle they took part in, carrying released outcomes only. A cycle whose rating is
 * still with HR comes back with `rating` null, indistinguishable from one where nothing was
 * ever set.
 */
export function useMyHistory() {
  return useQuery({
    queryKey: ['history', 'me'],
    queryFn: () => api.get<TimelineEntry[]>('/api/history/me'),
  })
}

/**
 * Somebody else's timeline, for their manager or for HR in scope.
 *
 * Returns an empty array for a person who has never been in a cycle, and a 403 for one the
 * caller may not see. The two are different answers on purpose: an empty history is a fact
 * about somebody you are entitled to ask about.
 */
export function useHistory(userId: number | undefined) {
  return useQuery({
    queryKey: ['history', userId],
    queryFn: () => api.get<TimelineEntry[]>(`/api/history/${userId}`),
    enabled: userId !== undefined && Number.isFinite(userId),
  })
}
