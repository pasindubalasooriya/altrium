import type { DevelopmentPlan, OwnImprovementPlan } from '../../api/types'

/**
 * Whether the employee's plan tab has something on it for them.
 *
 * Nothing else tells them. No email goes out in Sprint 1, a development goal is drafted by the
 * manager and arrives without a sound, and an improvement plan becomes visible the moment HR
 * co-sign it - all behind a tab there is no reason to open on any given day.
 *
 * **Two signals, and they clear differently on purpose**, which is why they are not one flag:
 *
 * - A **goal awaiting agreement** is outstanding work. It goes when the employee agrees to it,
 *   which the mutation already refreshes, and looking at it changes nothing. Nothing is stored.
 * - An **improvement plan** is news. The employee writes nothing on a PIP - they read it - so
 *   there is no act of completion to clear a dot, and a plan runs for months. Marking it as
 *   work would burn a permanent dot in the navigation, which trains people to ignore dots. So
 *   it is a read receipt, like the manager's peer-feedback one, and it clears on being opened.
 */

const KEY = 'altrium.pipSeen'

/**
 * The id of the last improvement plan this browser has shown its owner.
 *
 * Kept in the browser rather than the database for the same reasons as
 * {@link ../manager/peerSeen}: whether somebody has looked at a screen is not a fact about the
 * plan, no policy references it, and nobody else may ever query it. Storing it would create a
 * record of one employee's reading habits - on the most sensitive document the system holds.
 *
 * The id, not a boolean, so that a *second* improvement plan opened later dots again rather
 * than being silently swallowed by the receipt for the first.
 */
function lastSeenPlanId(): number | null {
  try {
    const raw = window.localStorage.getItem(KEY)
    return raw === null ? null : Number(raw) || null
  } catch {
    // Private window, or site data blocked. Nothing has been seen, so the dot shows - which is
    // the harmless direction: the opposite would hide a plan that had just been shared.
    return null
  }
}

/** Called when the employee opens their improvement plan, which is when they have seen it. */
export function markImprovementPlanSeen(planId: number): void {
  try {
    window.localStorage.setItem(KEY, String(planId))
  } catch {
    // Nowhere to record it. The dot stays, which is the safe direction.
  }
}

/**
 * What the plan tab is waiting on, as the label the dot announces to a screen reader, or null
 * for no dot.
 *
 * The improvement plan is tested first because it is the graver of the two, and because a
 * suspended development plan holds no pending goals anyway - opening a PIP suspends it (P-5.7),
 * so the two signals do not compete in practice.
 */
export function planWaitsOnYou(
  development: DevelopmentPlan | undefined,
  improvement: OwnImprovementPlan | undefined,
): string | null {
  const plan = improvement?.hasPlan ? improvement.plan : null
  if (plan && plan.id !== lastSeenPlanId()) {
    return 'An improvement plan has been shared with you'
  }

  // PENDING is the manager's goal submitted and awaiting agreement. DRAFT never reaches the
  // employee at all (P-5.9), and the server does not send it to them, so this cannot dot on
  // something they are unable to see.
  if (development?.goals.some((goal) => goal.agreement === 'PENDING')) {
    return 'A development goal is waiting for you to agree to it'
  }

  return null
}
