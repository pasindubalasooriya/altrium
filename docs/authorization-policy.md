# Altrium — Authorization Policy List

The enumerated authorization model. This is the intellectual core of the project: it is what makes the confidentiality claim concrete and testable.

**Every policy here is implemented in `AuthorizationService` and proved by a named JUnit test.** Code comments and test names cite policy IDs (`P_1_2_managerCannotReadNonReportReview`).

Derived from scenario §14. Where scenario §3 and §14 conflict, §14 governs (see P-2.2).

## Notation

| Symbol | Meaning |
|---|---|
| `S` | the subject / reviewee of an artifact |
| `A` | the acting principal (the caller) |
| `mgr(x)` | x's single direct manager |
| `dept(x)` | x's department |
| `grants(A)` | departments granted to A in `hr_department_grant`, resolved **this request** |
| "HR-in-scope" | an HR user for whom P-2.1 to P-2.4 all pass for the resource in question |

---

## P-0 Foundations

| # | Policy |
|---|---|
| **P-0.1** | Every authenticated principal is an **Employee** in addition to any other role. Manager, HR, Leadership and Super Admin are additive, never exclusive. |
| **P-0.2** | Every read and write routes through a single `AuthorizationService`. No controller, repository or service performs its own ad-hoc check. |
| **P-0.3** | Every **collection** read is scoped inside the SQL `WHERE` clause via a JPA `Specification`. Fetching then filtering in Java is prohibited — it leaks through pagination counts and total-elements headers. |
| **P-0.4** | Every **single-entity** read re-checks the same predicate before returning. An ID guessed or copied from another user's data must 403. |
| **P-0.5** | All denials return **403**, with no body distinguishing "does not exist" from "not permitted". |
| **P-0.6** | **Evaluation order is fixed and enforced in one method:** (1) authenticated → (2) absolute self-blocks → (3) role gate → (4) relationship/scope → (5) explicit-grant override → (6) state gates. **Step 5 can never reach past step 2.** Ordering them any other way lets a granted HR Head reach their own review. |
| **P-0.7** | Deactivated users (`active = false`) are excluded from peer-selection lists, cohort intake and newly opened cycles. Their rows and historical artifacts are never deleted and remain readable to whoever could read them. |

## P-1 Relationship (ReBAC)

| # | Policy |
|---|---|
| **P-1.1** | `isManagerOf(A,S)` is true only when `mgr(S) = A`. **Direct reports only** — never transitive, never skip-level. |
| **P-1.2** | A manager may read and write review artifacts **only** for S where `isManagerOf(A,S)`. |
| **P-1.3** | Peer reviewers for S are selected by `mgr(S)` — one uniform rule. Applied to a manager-as-reviewee this is scenario §7's "the manager's manager selects", so no separate mechanism exists. |
| **P-1.4** | Reporting-line assignment walks up the chain and **rejects any cycle**; every user has at most one manager. Without this the direct-reports query never terminates. |
| **P-1.5** | Leadership is never a reviewee: no review, rating or plan artifact may be created with a Leadership member as S. Enforced at creation, not merely hidden on read. |

## P-2 HR scoping (ABAC) — the ordering-critical block

| # | Policy |
|---|---|
| **P-2.1** | Every HR capability is confined to `grants(A)`. An HR action on a resource whose department ∉ `grants(A)` is 403. |
| **P-2.2** | **Own-review block — absolute.** An HR user may never read or act on any review, rating or plan where they are S. **No grant, flag or role overrides this.** Evaluated at step 2 of P-0.6, before any override.<br><br>*Resolves a source-document conflict: scenario §3 reads "…including their own reviews, unless explicitly granted", implying the override covers own reviews. §14 states the own-review block is absolute. §14 governs; §3's phrasing is loose.* |
| **P-2.3** | **Own-department block.** An HR user may not act on resources where `dept(resource) = dept(A)`. |
| **P-2.4** | **Explicit-grant override (HR Head).** A grant row carrying the explicit-grant flag lifts **P-2.3 only**, for that department. P-2.2 still bites. This is a grant configuration, **not a new role**. |
| **P-2.5** | Grants are resolved **from the database on every request**, cached only inside that request (request-scoped holder). Never in the JWT, the session, or a login-time cache. A grant change applies on the caller's very next request, with no re-login. |
| **P-2.6** | The HR Head's own review is conducted by **Leadership** (reviewer and rating-setter). This is how P-2.2 stays absolute without leaving the HR Head unreviewed. |

## P-3 Review artifact visibility

| # | Artifact | Policy |
|---|---|---|
| **P-3.1** | Self-review | Written by S only. Readable by S, `mgr(S)`, HR-in-scope. No rating field exists on it at all. |
| **P-3.2** | Peer review | Readable by `mgr(S)` and HR-in-scope, **including author identity**. |
| **P-3.3** | Peer review — anonymity | **S may never receive peer text, peer rating, peer author, or peer count** — through any endpoint, aggregate, sort order, pagination count or error message. Structural, not conditional: the subject-scoped query never joins the peer table. |
| **P-3.4** | Peer review — write | Only the two assigned peers may write, only for their assigned S, only while the cycle is open. |
| **P-3.5** | Peer review — single submission | Once submitted, immutable (scenario §15.2). A second submission is refused, backed by a unique constraint on `(cycle_id, subject_id, peer_id)`. *Status code pending confirmation: proposed **409**, since the peer has permission and it is the state that forbids the write; P-0.5's 403 rule governs access denials.* |
| **P-3.6** | Peer assignment | Exactly **two**, chosen by `mgr(S)` from active users; cross-department allowed (§5). Excluded: S themselves, and — *pending confirmation* — `mgr(S)`, who already writes the manager review. Scenario §7's allowance for same-level managers or the manager's own reports is unaffected. |
| **P-3.7** | Manager review | Written by `mgr(S)` only. Readable by S, `mgr(S)`, HR-in-scope. |

## P-4 Ratings

| # | Policy |
|---|---|
| **P-4.1** | Only `mgr(S)` sets the final rating. It is **chosen, never computed** from peer ratings — no aggregate is stored or surfaced as a suggestion (scenario §11). |
| **P-4.2** | `mgr(S)` may read peer ratings for their reports, as input to P-4.1. |
| **P-4.3** | HR-in-scope calibrates the final rating. Every change records before-value, after-value, actor and timestamp as an immutable append-only row. |
| **P-4.4** | S reads their **final rating and manager feedback only**, and only once released. Nothing else in the cycle is exposed to them. |
| **P-4.5** | The scale is exactly `NEEDS_IMPROVEMENT`, `MEETS_EXPECTATIONS`, `EXCEEDS_EXPECTATIONS`. |

## P-5 Plans

| # | Policy |
|---|---|
| **P-5.1** | PDP readable by S, `mgr(S)`, HR-in-scope. HR is **read-only** on a PDP — no write, no approve. |
| **P-5.2** | Only `mgr(S)` approves PDP or PIP goals as complete. |
| **P-5.3** | **PIP co-sign gate.** A PIP with `cosigned_at IS NULL` is invisible to S. Enforced as a predicate in the query, so calling the endpoint directly 403s — not a UI condition. |
| **P-5.4** | Only HR-in-scope may co-sign and record the witness. A manager can do neither; that separation is the entire point of the formality objects (scenario §9). |
| **P-5.5** | PDP goal target dates are movable by `mgr(S)`. **PIP deadlines are immutable once set** — no actor, including HR, may extend them. |
| **P-5.6** | A PIP must carry non-empty consequence-clause text before it can be co-signed. |
| **P-5.7** | **Exclusivity invariant.** No employee holds an ACTIVE PDP and an ACTIVE PIP simultaneously. Enforced in `PlanService` **and** by a database constraint, so concurrent requests cannot both slip through. |
| **P-5.8** | Only `PlanService` mutates plan status. No controller or repository writes a status field. |

## P-6 Cycles and configuration

| # | Policy |
|---|---|
| **P-6.1** | Only the Super Admin writes `quadrimester_config` and `cohort_membership`. |
| **P-6.2** | The cycle start date may be changed **only while `opened_at IS NULL`**; afterwards refused. |
| **P-6.3** | HR monitors cycles for their granted departments only — counts and statuses scoped by `grants(A)`, subject to P-2.2 and P-2.3. |
| **P-6.4** | The sweep runs as a **system principal**: it bypasses user authorization (there is no user) but remains bound by every domain invariant. |

## P-7 Leadership

| # | Policy |
|---|---|
| **P-7.1** | Leadership receives **aggregate metrics only**. No endpoint returns an individual review, rating or plan row to Leadership. Dashboards are Sprint 2, but the policy holds from day one so no drill-down endpoint is ever built. |
| **P-7.2** | Leadership are **not reviewees (P-1.5) and hold no PDP and no PIP**.<br><br>*Resolves a source-document conflict: §7 excludes Leadership from review; §8 says every employee holds a PDP. §8 means every reviewable employee.* |
| **P-7.3** | Leadership act as reviewer and peer-assigner for the tier directly below them, including the HR Head (P-2.6). |

## P-8 Exports — Sprint 2, policy reserved now

| # | Policy |
|---|---|
| **P-8.1** | Export endpoints are HR and Leadership only. **Managers cannot export**, despite being able to read the same rows in the UI (scenario §6). |
| **P-8.2** | An HR export carries `grants(A)` in the `WHERE` clause; a Leadership export returns totals only. |

## P-9 Super Admin

| # | Policy |
|---|---|
| **P-9.1** | Manages users, reporting lines, departments and activation flags. |
| **P-9.2** | Manages HR department grants, including the explicit-grant flag. |
| **P-9.3** | Manages cycle configuration and cohort membership. |
| **P-9.4** | **No read access to any review, rating or plan content.**<br><br>*Not addressed in the source documents; settled by the team. Since the Super Admin grants HR their departments, review access on top would make the role omnipotent and defeat segregation of duties.* |

---

## Denial test matrix

Every row is a named JUnit test calling the endpoint **directly** via `MockMvc` with a minted JWT — never through the UI. A UI-driven test proves a button is hidden, not that access is refused.

| Test | Policy | Expect |
|---|---|---|
| Manager reads a non-report's review | P-1.2 | 403 |
| Manager reads a non-report's plan | P-1.2, P-5.1 | 403 |
| Subject requests peer reviews about themselves, by every route including list endpoints and pagination counts | P-3.3 | 403 / absent |
| HR acts in their own department without the explicit grant | P-2.3 | 403 |
| **HR with an explicit grant reaches their own review** — the rule-ordering test | P-0.6, P-2.2, P-2.4 | 403 |
| HR grant revoked mid-session, next request with the same token | P-2.5 | 403, no re-login |
| Subject reads a PIP before co-sign | P-5.3 | 403 |
| Manager attempts co-sign or witness | P-5.4 | 403 |
| Any actor extends a PIP deadline | P-5.5 | 403 |
| Leadership requests an individual review | P-7.1 | 403 |
| Leadership is made a reviewee | P-1.5, P-7.2 | rejected at creation |
| Super Admin requests review content | P-9.4 | 403 |
| Peer submits twice | P-3.5 | 409 (pending) |

**Correctness tests alongside:** sweep is idempotent (run twice, one cycle opened) · a past-dated cycle opens on the next sweep · a suspended PDP resumes with goals and progress intact · the exclusivity invariant holds under two concurrent open-PIP requests, against real MySQL · a reporting-line loop is rejected.
