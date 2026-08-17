/**
 * The wire shapes, mirroring the backend DTOs.
 *
 * Hand-written rather than generated, and deliberately narrow: several of these types are
 * narrower than what the endpoint could physically return, because the type is where the
 * policy is easiest to hold. `OwnReviewRecord` has no peer field at all, so no component
 * under `features/my/` can render peer content even by mistake - the property does not
 * exist to be read.
 */

export type Rating = 'NEEDS_IMPROVEMENT' | 'MEETS_EXPECTATIONS' | 'EXCEEDS_EXPECTATIONS'

export const RATINGS: Rating[] = [
  'NEEDS_IMPROVEMENT',
  'MEETS_EXPECTATIONS',
  'EXCEEDS_EXPECTATIONS',
]

export const RATING_LABELS: Record<Rating, string> = {
  NEEDS_IMPROVEMENT: 'Needs improvement',
  MEETS_EXPECTATIONS: 'Meets expectations',
  EXCEEDS_EXPECTATIONS: 'Exceeds expectations',
}

export type Role = 'EMPLOYEE' | 'MANAGER' | 'HR' | 'LEADERSHIP' | 'SUPER_ADMIN'

/** `GET /api/me`. Identity and roles only - no review content, by design. */
export interface Me {
  id: number
  email: string
  fullName: string
  departmentId: number | null
  managerId: number | null
  roles: Role[]
  /** Where the server says this user belongs after login. Obeyed, never recomputed. */
  landing: string
}

/** Spring's `Page`, as it arrives. `totalElements` counts only what the caller may see. */
export interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

/** `OrgDtos.PageView`, which the admin endpoints return instead of a Spring page. */
export interface PageView<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export type CycleStatus = 'CONFIGURED' | 'OPEN' | 'CLOSED'

export interface Cycle {
  id: number
  label: string
  financialYear: number
  quadrimesterNo: number
  status: CycleStatus
  startDate: string
  endDate: string
  openedAt: string | null
  closedAt: string | null
}

export interface ReviewSummary {
  subjectId: number
  subjectName: string
  departmentName: string | null
  managerId: number | null
  subjectActive: boolean
  cycleId: number
  cycleLabel: string
}

export interface SelfReview {
  achievements: string | null
  challenges: string | null
  goals: string | null
  submittedAt: string | null
}

export interface ManagerReview {
  managerId: number
  managerName: string
  feedback: string | null
  submittedAt: string | null
}

export interface PeerReview {
  peerId: number
  peerName: string
  feedback: string | null
  rating: Rating | null
  submittedAt: string | null
}

export interface FinalRating {
  rating: Rating
  setAt: string
  releasedAt: string | null
}

/**
 * Which parts of a record the caller had grounds for, named by the server.
 *
 * This is what the UI renders from. A null section could mean "you may not see this" or
 * "nobody has written it yet", and those are different sentences to show somebody - the
 * server already knows which, so it says.
 */
export type Section = 'SELF_REVIEW' | 'MANAGER_REVIEW' | 'PEER_REVIEWS' | 'FINAL_RATING'

/** `GET /api/reviews/{subjectId}` as a reviewer sees it. */
export interface ReviewRecord {
  summary: ReviewSummary
  selfReview: SelfReview | null
  managerReview: ManagerReview | null
  peerReviews: PeerReview[] | null
  finalRating: FinalRating | null
  visibleSections: Section[]
}

/**
 * The same endpoint, typed for the subject reading their own record.
 *
 * `peerReviews` is **absent from this type**, not optional. `READ_PEER_REVIEW` has no `SELF`
 * ground, so the server never sends it here - and the employee console must have no code
 * path capable of displaying it if it somehow arrived. Peer *count* is equally off limits:
 * there is no field here to count.
 */
export interface OwnReviewRecord {
  summary: ReviewSummary
  selfReview: SelfReview | null
  managerReview: ManagerReview | null
  finalRating: FinalRating | null
  visibleSections: Section[]
}

/**
 * `GET /api/reviews/my-rating`.
 *
 * "No rating yet" and "rating withheld" produce the same response on purpose, and the screen
 * renders them the same way. Saying "awaiting release" for one and "not yet rated" for the
 * other would put back the disclosure this shape exists to remove.
 */
export interface OwnRating {
  cycleId: number | null
  rating: Rating | null
  releasedAt: string | null
  managerFeedback: string | null
  released: boolean
}
