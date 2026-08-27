import { beforeEach, describe, expect, it } from 'vitest'
import { managerIsWaitedOn } from './waiting'
import type { ReviewSummary } from '../../api/types'

function row(over: Partial<ReviewSummary>): ReviewSummary {
  return {
    subjectId: 7,
    subjectName: 'John Alvarez',
    departmentName: 'Engineering',
    managerId: 5,
    subjectActive: true,
    cycleId: 2,
    cycleLabel: '2026 Q1',
    peerReviewsSubmitted: 2,
    ratingStage: 'SIGNED_OFF',
    ...over,
  }
}

describe('managerIsWaitedOn', () => {
  beforeEach(() => window.localStorage.clear())

  it('prompts the manager of the row', () => {
    expect(managerIsWaitedOn(2, row({}), 5)).not.toBeNull()
  })

  /**
   * The list is scoped by every ground the caller holds at once, so an HR user who also runs a
   * team gets their granted departments in the same page as their own reports. Neither prompt
   * is theirs to clear on somebody else's report - they cannot share that rating, and the peer
   * feedback is the other manager's to read - so a dot there could never go away.
   */
  it('says nothing on a row the caller does not manage', () => {
    expect(managerIsWaitedOn(2, row({}), 28)).toBeNull()
    expect(managerIsWaitedOn(2, row({ ratingStage: 'NOT_SET' }), 28)).toBeNull()
  })

  it('says nothing before the caller is known', () => {
    expect(managerIsWaitedOn(2, row({}), undefined)).toBeNull()
  })

  it('falls back to unread peer feedback once the rating is not the story', () => {
    expect(managerIsWaitedOn(2, row({ ratingStage: 'NOT_SET' }), 5))
      .toBe('New peer feedback')
    expect(managerIsWaitedOn(2, row({ ratingStage: 'NOT_SET', peerReviewsSubmitted: 0 }), 5))
      .toBeNull()
  })

  /** Withheld, not zero: the subject's own row carries no count at all (P-3.3). */
  it('says nothing on a row whose peer count was withheld', () => {
    expect(managerIsWaitedOn(2, row({ ratingStage: 'SHARED', peerReviewsSubmitted: null }), 5))
      .toBeNull()
  })
})
