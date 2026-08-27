import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { hasUnseenPeerFeedback, markPeerFeedbackSeen } from './peerSeen'

/**
 * The unread dot for newly arrived peer feedback.
 *
 * **This is not a security test.** The server decides who is told how many peers have
 * submitted, and `PermittedReviewReadTest` proves it withholds that from the subject. What is
 * proved here is that the client cannot invent the number when the server declined to send one:
 * a null count must never become a zero, and a zero must never become a dot on a row whose peer
 * section the caller could not open anyway.
 */
describe('hasUnseenPeerFeedback', () => {
  beforeEach(() => {
    window.localStorage.clear()
  })

  afterEach(() => {
    window.localStorage.clear()
  })

  it('shows nothing when the server sent no count', () => {
    // The subject's own row. `READ_PEER_REVIEW` has no SELF ground, so the field is absent
    // rather than zero, and the two must not be conflated on the way in.
    expect(hasUnseenPeerFeedback(2, 8, null)).toBe(false)
    expect(hasUnseenPeerFeedback(2, 8, undefined)).toBe(false)
  })

  it('shows nothing when no peer has submitted', () => {
    expect(hasUnseenPeerFeedback(2, 7, 0)).toBe(false)
  })

  it('shows a dot for feedback that has not been opened', () => {
    expect(hasUnseenPeerFeedback(2, 7, 1)).toBe(true)
  })

  it('stops showing it once the record has been opened', () => {
    markPeerFeedbackSeen(2, 7, 1)
    expect(hasUnseenPeerFeedback(2, 7, 1)).toBe(false)

    // The second peer submits. It is new again, because the count moved.
    expect(hasUnseenPeerFeedback(2, 7, 2)).toBe(true)
  })

  it('keeps subjects and cycles apart', () => {
    markPeerFeedbackSeen(2, 7, 1)

    // Same count, different person, and different cycle for the same person.
    expect(hasUnseenPeerFeedback(2, 8, 1)).toBe(true)
    expect(hasUnseenPeerFeedback(3, 7, 1)).toBe(true)
  })

  it('survives unreadable storage', () => {
    window.localStorage.setItem('altrium.peerSeen', 'not json')

    // A dot is shown rather than an exception thrown. Failing towards "unread" is the harmless
    // direction: the opposite would hide peer feedback that had just arrived.
    expect(hasUnseenPeerFeedback(2, 7, 1)).toBe(true)
    expect(() => markPeerFeedbackSeen(2, 7, 1)).not.toThrow()
  })
})
