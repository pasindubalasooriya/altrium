/**
 * How many peer reviews the caller had already seen for a subject, per cycle.
 *
 * This is a read receipt, and it is deliberately kept in the browser rather than on the server.
 * Whether somebody has looked at a screen is not a fact about the review - nothing in the
 * scenario turns on it, no policy references it, and no other person may ever query it. Putting
 * it in the database would mean a table, a migration and a capability guarding a field whose
 * only consumer is a dot, and it would create a record of one employee's reading habits that
 * did not exist before.
 *
 * The cost of that choice, stated plainly: the dot is per browser. Clearing site data or moving
 * to another machine shows the dot again. It marks something as unread that has been read,
 * which is the harmless direction for this to fail in - the opposite would hide a peer review
 * that had just arrived.
 *
 * Storage can throw outright in a private window or with site data blocked, so every access is
 * guarded and a failure simply means no dot is suppressed.
 */

const KEY = 'altrium.peerSeen'

type Seen = Record<string, number>

function slot(cycleId: number, subjectId: number): string {
  return `${cycleId}:${subjectId}`
}

function read(): Seen {
  try {
    const raw = window.localStorage.getItem(KEY)
    return raw ? (JSON.parse(raw) as Seen) : {}
  } catch {
    // Unreadable or not JSON. Treat it as nothing seen rather than trying to repair it.
    return {}
  }
}

/** Whether peer feedback has arrived for this subject that the caller has not opened yet. */
export function hasUnseenPeerFeedback(
  cycleId: number,
  subjectId: number,
  submitted: number | null | undefined,
): boolean {
  // Null is not zero here. The server sends no count at all where the caller has no grounds to
  // read this subject's peer feedback - their own row among them - and a dot on a row whose
  // peer section they can never open would be an odd thing to offer them.
  if (submitted === null || submitted === undefined || submitted === 0) {
    return false
  }
  return submitted > (read()[slot(cycleId, subjectId)] ?? 0)
}

/** Called when the caller opens the record, which is the moment they have seen what is there. */
export function markPeerFeedbackSeen(
  cycleId: number,
  subjectId: number,
  submitted: number,
): void {
  try {
    const seen = read()
    if ((seen[slot(cycleId, subjectId)] ?? 0) === submitted) {
      return
    }
    seen[slot(cycleId, subjectId)] = submitted
    window.localStorage.setItem(KEY, JSON.stringify(seen))
  } catch {
    // Nowhere to record it. The dot stays, which is the safe direction.
  }
}
