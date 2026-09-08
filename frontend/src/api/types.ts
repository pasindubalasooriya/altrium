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
  /**
   * How many assigned peers have submitted about this person.
   *
   * **Absent, not zero, where the caller has no grounds to read this subject's peer feedback**
   * - which includes their own row, since `READ_PEER_REVIEW` has no `SELF` ground. A zero is
   * the count, and P-3.3 withholds the count from the subject. Treat null and 0 as different
   * facts here: null means "not yours to know", 0 means "none yet".
   */
  peerReviewsSubmitted: number | null
  /**
   * Where the rating has got to, so each side can be told when it is their turn (P-4.8).
   *
   * **Null where the caller has no grounds to know**, which includes the subject's own row:
   * `READ_RATING_AUDIT` has no `SELF` ground (P-4.7), and "a rating exists and is sitting with
   * HR" is exactly what release exists to withhold. Null is not a fifth state.
   */
  ratingStage: 'NOT_SET' | 'AWAITING_SIGN_OFF' | 'SIGNED_OFF' | 'SHARED' | null
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
  /**
   * Whether HR have signed this rating off, by adjusting it or approving it as set (P-4.8).
   *
   * **Null where the caller has no grounds to know.** Whether HR were through a rating is part
   * of the calibration trail, and `READ_RATING_AUDIT` has no `SELF` ground (P-4.7), so the
   * subject reading their own released rating gets no field at all. Null is not false.
   */
  signedOffByHr: boolean | null
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

// ---------------------------------------------------------------- writing reviews

export interface SelfReviewWritten {
  cycleId: number
  achievements: string | null
  challenges: string | null
  goals: string | null
  submittedAt: string | null
  submitted: boolean
}

export interface ManagerReviewWritten {
  subjectId: number
  feedback: string | null
  submittedAt: string | null
  submitted: boolean
}

/**
 * A peer's own workload.
 *
 * Note what is not here: who the *other* peer is. A peer knowing that would be one
 * conversation away from the subject knowing it too, so the server does not send it.
 */
export interface PeerTask {
  subjectId: number
  subjectName: string
  cycleId: number
  submitted: boolean
}

/** The manager's view of an assignment. It names the peer, because they chose them. */
export interface PeerAssignment {
  subjectId: number
  subjectName: string
  peerId: number
  peerName: string
}

export interface PeerCandidate {
  id: number
  fullName: string
  departmentName: string | null
}

export interface RatingView {
  subjectId: number
  rating: Rating
  setAt: string
  releasedAt: string | null
  released: boolean
}

export interface Calibration {
  from: Rating
  to: Rating
  by: string
  at: string
  note: string | null
}

// ---------------------------------------------------------------- plans

export type GoalStatus = 'OPEN' | 'COMPLETE'

/**
 * Where a development goal stands between the manager who wrote it and the employee it is for
 * (P-5.9). Null on an improvement goal, which is put to the employee rather than agreed.
 *
 * DRAFT never reaches the employee: the server does not return drafts to them at all.
 */
export type GoalAgreement = 'DRAFT' | 'PENDING' | 'AGREED'

export interface Goal {
  id: number
  title: string
  detail: string | null
  targetDate: string | null
  status: GoalStatus
  completedAt: string | null
  approvedBy: string | null
  agreement: GoalAgreement | null
  submittedAt: string | null
  agreedAt: string | null
  /** The employee's own words, kept apart from `detail`, which is the manager's. */
  progressNote: string | null
}

export type PlanStatus = 'ACTIVE' | 'SUSPENDED'

export interface DevelopmentPlan {
  userId: number
  userName: string
  status: PlanStatus
  active: boolean
  suspendedAt: string | null
  goals: Goal[]
}

export type ImprovementStatus = 'ACTIVE' | 'PASSED' | 'FAILED'

export interface ImprovementPlan {
  id: number
  userId: number
  userName: string
  status: ImprovementStatus
  active: boolean
  openedBy: string
  openedAt: string
  deadline: string
  consequenceClause: string | null
  cosignedBy: string | null
  cosignedAt: string | null
  cosigned: boolean
  witnessName: string | null
  witnessRecordedAt: string | null
  closedAt: string | null
  goals: Goal[]
}

/**
 * `hasPlan` is false both when no plan exists and when one exists but is not co-signed.
 * The two are deliberately indistinguishable, and the screen renders them the same way.
 */
export interface OwnImprovementPlan {
  hasPlan: boolean
  plan: ImprovementPlan | null
}

/**
 * One cycle in somebody's history (P-4.9).
 *
 * `rating`, `releasedAt` and `managerFeedback` are null both when the caller may not see them
 * and when nothing was ever recorded, and the two are deliberately indistinguishable: telling
 * them apart would disclose that a decision exists and is being withheld, which is exactly what
 * release controls (P-4.4). So nothing in this app may render "withheld" for a null here - the
 * only honest words are "not shared yet", which is equally true of both.
 */
export interface TimelineEntry {
  cycleId: number
  financialYear: number
  quadrimester: number
  cycleStatus: CycleStatus
  startDate: string
  endDate: string
  rating: Rating | null
  releasedAt: string | null
  managerFeedback: string | null
}
