import type { ReviewSummary } from '../../api/types'
import { hasUnseenPeerFeedback } from './peerSeen'

/**
 * What a review row is waiting on, for the person looking at it.
 *
 * The rating is handed back and forth - the manager sets it, HR sign it off, the manager shares
 * it - and neither side is told when their turn comes. No email goes out in Sprint 1, so each
 * was waiting on a screen they had no reason to open.
 *
 * **These are outstanding work, not unread news, and the difference decides how they clear.**
 * A dot for something new goes when you look at it, because having looked is the point; the
 * unread dot for arriving peer feedback works that way and remembers what was seen. These clear
 * when the work is *done* instead - when HR sign off, when the manager shares - so nothing is
 * remembered anywhere for them. The server already knows the state, and the state is the answer.
 */

/**
 * Something the manager still has to do on this row.
 *
 * **Only for rows the caller actually manages.** The review list is scoped by all of the
 * caller's grounds at once, so an HR user who also runs a team gets their granted departments
 * in the same page as their own reports. Neither signal below is theirs to clear on somebody
 * else's report - they cannot share that rating, and the peer feedback is the other manager's
 * to read - so a dot on those rows was a prompt that could never go away.
 */
export function managerIsWaitedOn(
  cycleId: number,
  review: ReviewSummary,
  callerId: number | undefined,
): string | null {
  if (callerId === undefined || review.managerId !== callerId) {
    return null
  }
  if (review.ratingStage === 'SIGNED_OFF') {
    // HR have been through it and handed it back. Ahead of the peer dot, because it is the
    // later step: if both are true, this is the one that moves the cycle on.
    return 'HR have signed this rating off - it can be shared'
  }
  if (hasUnseenPeerFeedback(cycleId, review.subjectId, review.peerReviewsSubmitted)) {
    return 'New peer feedback'
  }
  return null
}

/** Something HR still has to do on this row. */
export function hrIsWaitedOn(review: ReviewSummary): string | null {
  return review.ratingStage === 'AWAITING_SIGN_OFF'
    ? 'Waiting for you to calibrate or approve this rating'
    : null
}

/**
 * Whether any row on the page is waiting on this person.
 *
 * Drives the dot in the navigation, which is the only signal somebody gets before they have
 * opened anything at all. It reads the first page rather than the whole cycle - the count is
 * not shown, so a dot that appears one page late is a smaller failure than a query that grows
 * with the organisation.
 */
export function anyWaiting(
  rows: ReviewSummary[] | undefined,
  waiting: (review: ReviewSummary) => string | null,
): boolean {
  return rows?.some((review) => waiting(review) !== null) ?? false
}
