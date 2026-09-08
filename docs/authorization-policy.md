# Altrium - Authorization Policy List

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
| **P-0.1** | Every authenticated principal is an **Employee** in addition to any other role. Manager, HR and Leadership are additive, never exclusive. **Super Admin is the one exception (P-9.5)**: it is held alone, and holds Employee only as the provisioned-and-active marker every endpoint requires, never as a claim to be reviewed. |
| **P-0.2** | Every read and write routes through a single `AuthorizationService`. No controller, repository or service performs its own ad-hoc check. |
| **P-0.3** | Every **collection** read is scoped inside the SQL `WHERE` clause via a JPA `Specification`. Fetching then filtering in Java is prohibited - it leaks through pagination counts and total-elements headers. |
| **P-0.4** | Every **single-entity** read re-checks the same predicate before returning. An ID guessed or copied from another user's data must 403. |
| **P-0.5** | All denials return **403**, with no body distinguishing "does not exist" from "not permitted". |
| **P-0.6** | **Evaluation order is fixed and enforced in one method:** (1) authenticated → (2) absolute self-blocks → (3) role gate → (4) relationship/scope → (5) explicit-grant override → (6) state gates. **Step 5 can never reach past step 2.** Ordering them any other way lets a granted HR Head reach their own review. |
| **P-0.7** | Deactivated users (`active = false`) are excluded from peer-selection lists, cohort intake and newly opened cycles. Their rows and historical artifacts are never deleted and remain readable to whoever could read them. |

## P-1 Relationship (ReBAC)

| # | Policy |
|---|---|
| **P-1.1** | `isManagerOf(A,S)` is true only when `mgr(S) = A`. **Direct reports only** - never transitive, never skip-level. |
| **P-1.2** | A manager may read and write review artifacts **only** for S where `isManagerOf(A,S)`. |
| **P-1.3** | Peer reviewers for S are selected by `mgr(S)` - one uniform rule. Applied to a manager-as-reviewee this is scenario §7's "the manager's manager selects", so no separate mechanism exists. |
| **P-1.4** | Reporting-line assignment walks up the chain and **rejects any cycle**; every user has at most one manager. Without this the direct-reports query never terminates. |
| **P-1.5** | Leadership is never a reviewee: no review, rating or plan artifact may be created with a Leadership member as S. Enforced at creation, not merely hidden on read. |

## P-2 HR scoping (ABAC) - the ordering-critical block

| # | Policy |
|---|---|
| **P-2.1** | Every HR capability is confined to `grants(A)`. An HR action on a resource whose department ∉ `grants(A)` is 403. |
| **P-2.2** | **Own-review block - absolute.** An HR user may never exercise **HR authority** over any review, rating or plan where they are S: no calibration of their own rating, no co-signing or witnessing of their own PIP, no HR-scoped read of their own record. **No grant, flag or role overrides this.** Evaluated at step 2 of P-0.6, before any override, by withholding `HR_IN_SCOPE` grounds entirely.<br><br>What it does **not** remove is the ordinary right every reviewee holds. An HR user is an employee too (P-0.1), so they read their own final rating and manager feedback on the same terms as anyone (P-4.4), and their own peer feedback stays hidden on the same terms as anyone (P-3.3) - because no subject reads peer feedback, not because they are HR.<br><br>*Resolves two source-document conflicts. First, scenario §3 reads "…including their own reviews, unless explicitly granted", implying the override covers own reviews; §14 states the block is absolute. §14 governs; §3's phrasing is loose. Second, §14's blanket wording read against P-4.4 would withhold an HR user's own rating from them, leaving the HR Head reviewed by Leadership under P-2.6 and never told the outcome. Settled by the team: the block removes HR **authority**, not employee rights.* |
| **P-2.3** | **Own-department block.** An HR user may not act on resources where `dept(resource) = dept(A)`. |
| **P-2.4** | **Explicit-grant override (HR Head).** A grant row carrying the explicit-grant flag lifts **P-2.3 only**, for that department. P-2.2 still bites. This is a grant configuration, **not a new role**. |
| **P-2.5** | Grants are resolved **from the database on every request**, cached only inside that request (request-scoped holder). Never in the JWT, the session, or a login-time cache. A grant change applies on the caller's very next request, with no re-login. |
| **P-2.6** | The HR Head's own review is conducted by **Leadership** (reviewer and rating-setter). This is how P-2.2 stays absolute without leaving the HR Head unreviewed. |

## P-3 Review artifact visibility

| # | Artifact | Policy |
|---|---|---|
| **P-3.1** | Self-review | Written by S only. Readable by S, `mgr(S)`, HR-in-scope. No rating field exists on it at all. Drafted freely, **final once submitted** (409): editing afterwards would rewrite what the manager has already read and acted on. The endpoint takes **no subject id**, so the API cannot express writing somebody else's. |
| **P-3.2** | Peer review | Readable by `mgr(S)` and HR-in-scope, **including author identity**. Where S is themselves a manager, `mgr(S)` is the manager's manager - the same rule one level up, not a separate mechanism (scenario §7). |
| **P-3.3** | Peer review - anonymity | **S may never receive peer text, peer rating, peer author, or peer count** - through any endpoint, aggregate, sort order, pagination count or error message. Structural, not conditional: the subject-scoped query never joins the peer table. |
| **P-3.4** | Peer review - write | Only the two assigned peers may write, only for their assigned S, only while the cycle is open. |
| **P-3.5** | Peer review - single submission | Once submitted, immutable (scenario §15.2). A second submission is refused with **409**, backed by a unique constraint on `(cycle_id, subject_id, peer_id)`. 409 rather than 403 because the peer *has* permission and it is the state that forbids the write; P-0.5's 403 rule governs **access** denials. |
| **P-3.6** | Peer assignment | Exactly **two**, chosen by `mgr(S)` from active users; cross-department allowed (§5). **Excluded: S themselves, and `mgr(S)`** - a manager is never a peer reviewer of their own report, since they already write the manager review. Scenario §7's allowance for a manager-reviewee's peers to be same-level managers or their own reports is unaffected. Two identical ids are one peer, not two. |
| **P-3.7** | Manager review | Written by `mgr(S)` only, and the author is recorded as **the caller**, not inferred from today's reporting line. Readable by `mgr(S)` and HR-in-scope throughout, and **by S only once the rating is released** - the release gate of P-4.4 applies to the words as well as the number. Final once submitted (409), because S may already have read it. |
| **P-3.8** | Peer assignment - reassignment | Peers may be replaced while nothing has been written and **not afterwards** (409). Replacing a peer who has already submitted would strand their row: unreadable through any endpoint, and nobody would know it was there. |
| **P-3.9** | Peer assignment - visibility | Who is assigned to S is readable **only by `mgr(S)`**, gated on `ASSIGN_PEERS` rather than a read capability. S asking about themselves is refused by the ordinary route, since they are not their own manager. A peer sees **their own workload only** - whom they must review, never who reviews them, and never who else was assigned to the same subject. |
| **P-3.10** | Write window | Every review write requires an **open cycle**, refused with 409. A domain invariant rather than a state gate: it refuses everybody identically, S included, so it says nothing about the caller. |
| **P-3.11** | Peer assignment - candidates | The **peer-candidate list** is guarded by `ASSIGN_PEERS`, the capability of the write it feeds, not by a read capability and not by the Manager role. It is the only route to the employee roster outside the Super Admin's console, so the gate matters: it opens only to the person entitled to choose, and only for one named subject. It carries a name and a department and nothing else. Its exclusions - the subject, `mgr(S)`, deactivated users - live in the query, so the list offered and the set the write accepts are the same set, and the page count describes the candidates rather than the organisation. |
| **P-3.12** | Peer assignment - HR | **An HR user may be a peer only for somebody in their own department.** Enforced in the candidate query and again on the write, so an id typed straight into the request is refused too. The rule is the *role and the department*, deliberately not today's grants: a grant added after an assignment would otherwise create the conflict retroactively, and the check would have to be repeated at calibration to catch it.<br><br>*Product Owner ruling. An HR user who peer-reviews outside their own team writes input to a rating they then calibrate, and reads their own peer review back as HR while doing it. Inside HR they can and they should - those are the colleagues they actually work alongside, and peer feedback is meant to come from people who have worked with the subject. "No department" matches nobody rather than everybody, so the Leadership, who hold none, are not a way back in.* |
| **P-3.13** | Peer assignment - Leadership | **The Leadership are never peer reviewers.** Excluded in the candidate query and refused on the write, like the Super Admin (P-9.5).<br><br>*Product Owner ruling. They sit a tier or two above the people under review, so they are not colleagues in the sense scenario §5 means: peer feedback is what somebody you work alongside says about working with you, and a remark from two levels up is not that whatever it says. **The channel they do have is untouched** - where a Leadership member manages somebody directly they write that person's manager review on `DIRECT_MANAGER` grounds, which is how an HR Head is reviewed (P-2.6). Nothing about being reviewed changes either: the Leadership are never reviewees, so they were never on the other side of this table.* |

## P-4 Ratings

| # | Policy |
|---|---|
| **P-4.1** | Only `mgr(S)` sets the final rating. It is **chosen, never computed** from peer ratings - no aggregate is stored, derived on read, or offered by any endpoint (scenario §11). **Set once**: setting the rating is what submits it for HR sign-off, so from that moment it is with HR and the manager cannot change it. A second attempt is 409, as is any attempt after calibration or after release.<br><br>*Tightened by the Product Owner. It was previously editable until HR actually calibrated, which left a window where HR were assessing a figure the manager could change underneath them, with nothing recording that it had moved. The calibration trail exists to answer "who decided this and when", and a rating revised silently between submission and sign-off is a decision the trail cannot see. A manager who submits the wrong figure now asks HR to calibrate it, which is exactly what calibration is for and leaves the correction on the record.* |
| **P-4.2** | `mgr(S)` may read peer ratings for their reports, as input to P-4.1. |
| **P-4.3** | HR-in-scope calibrates the final rating. Every change records before-value, after-value, actor and timestamp as an immutable append-only row, written in the **same transaction** as the change. A no-op calibration is refused with 400: every row in that table means "somebody moved this", and HR agreeing with the manager is the normal case and leaves no trace. Calibration after release is refused with 409. |
| **P-4.4** | S reads their **final rating and manager feedback only**, and only once released. Nothing else in the cycle is exposed to them. "No rating yet" and "a rating exists and is withheld" produce an **identical response**, so the existence of a decided rating is itself not disclosed. The manager's feedback is gated on the same release, since feedback arriving first would tell the employee the outcome without telling them the outcome. |
| **P-4.6** | **Release** opens the P-4.4 gate, and is performed by HR-in-scope or `mgr(S)`.<br><br>*A gap in the source documents rather than a conflict: §5 puts "the final rating is shared with the employee" straight after the HR normalisation meeting but never names the actor. `mgr(S)` is listed as well because P-2.2 withholds HR grounds on one's own case, so an HR-only release would leave the HR Head's rating permanently unreleasable - reviewed by Leadership under P-2.6 and never told the outcome. With the manager listed, Leadership release it as that person's manager and no special case is needed.*<br><br>*Nothing forces the normalisation meeting to have happened first, because the system cannot know that it did; modelling "HR has signed off" would invent a state the scenario does not have.* |
| **P-4.7** | The calibration trail is readable by `mgr(S)` and HR-in-scope, and **never by S** - `READ_RATING_AUDIT` carries no `SELF` grounds, exactly as `READ_PEER_REVIEW` does not. P-4.4 gives the subject their rating and their manager's feedback; "your manager said Meets, HR moved it to Exceeds" is neither, and would undermine the manager in the conversation they have to hold. |
| **P-4.8** | Release - HR sign-off | **A rating cannot be shared with the employee until HR have signed it off**, either by calibrating it or by approving it as set. 409, not 403: the manager holds `RELEASE_RATING` throughout and it is the state of the sign-off that refuses. Approval is a separate act from calibration with its own endpoint, recorded in the same audit trail as a row whose before and after are equal - so the trail says whether HR moved the rating or agreed with it. It carries `CALIBRATE_RATING` rather than a capability of its own, so P-2.2 refuses an HR user signing off their own rating by the ordering rather than by a second check.<br><br>**Whether HR have signed off is part of the calibration trail** and is withheld from S by P-4.7 - null in the DTO, never false.<br><br>*Product Owner ruling, and a **deviation from scenario §5**, which shares the rating after the normalisation meeting without making that meeting a precondition. **The gate applies only where an HR user could sign off**: where no grant covers the subject's department, the manager releases alone. A gate nobody can open is a stuck record, and an HR user's own rating is exactly that case - P-2.2 blocks them, and their department may have no other HR user in scope. The cost, accepted: revoking every grant over a department switches the requirement off for it rather than blocking releases.* |
| **P-4.5** | The scale is exactly `NEEDS_IMPROVEMENT`, `MEETS_EXPECTATIONS`, `EXCEEDS_EXPECTATIONS`. |
| **P-4.9** | **History across cycles.** A person's timeline is gated on `READ_REVIEW_SUMMARY`, the same roster capability the review list uses, so a caller who may read nothing about them does not learn they have a history either. **Every cycle the person took part in appears for every caller**; what narrows is what each cycle carries. Where the justifying ground is `SELF` the rating and the manager's words are fetched for released cycles only, in the `WHERE` clause - so a withheld cycle is byte-for-byte the response a cycle with nothing written yet produces, satisfying P-4.4 without the timeline having to lie about which cycles the employee was in. Somebody never reviewed gets an empty list, not a 404 (P-0.5).<br><br>*The narrowing is read off what `require` returns rather than recomputed from roles or from comparing ids. `SELF` implies no other ground here, because a reporting line cannot form a cycle (P-1.4) so nobody manages themselves, and P-2.2 withholds an HR user's own HR grounds - which is what makes a granted HR Head's own timeline the employee view.*|

## P-5 Plans

| # | Policy |
|---|---|
| **P-5.1** | PDP readable by S, `mgr(S)`, HR-in-scope. HR is **read-only** on a PDP - no write, no approve. |
| **P-5.2** | Only `mgr(S)` approves PDP or PIP goals as complete. |
| **P-5.3** | **PIP co-sign gate.** A PIP with `cosigned_at IS NULL` is invisible to S. Enforced as a predicate in the query, so calling the endpoint directly 403s - not a UI condition.<br><br>**The suspension is withheld with it.** Suspending the development plan has exactly one cause (P-5.7), so telling S their plan is on hold tells them a PIP exists. Until it is co-signed, S's own development plan is presented to them as ACTIVE with no `suspendedAt`; `mgr(S)` and HR-in-scope always see the true state. The row is never altered - writes to the suspended plan are still refused with 409, which is the one seam this leaves and is preferable to disclosing the plan early.<br><br>*Found in testing. The plan screen announced "on hold while an improvement plan is running" and linked to a page that then denied any plan existed - both a disclosure the gate exists to prevent and a contradiction the employee could see.* |
| **P-5.4** | Only HR-in-scope may co-sign and record the witness. A manager can do neither; that separation is the entire point of the formality objects (scenario §9). |
| **P-5.5** | PDP goal target dates are movable **by `mgr(S)`**, and remain movable after the goal is agreed - section 8 asks for dates that shift as priorities change, and the date is the one thing on an agreed goal that still moves (P-5.9). **PIP deadlines are immutable once set** - no actor, including HR, may extend them. |
| **P-5.9** | The PDP is **not keyed to a cycle**. One enduring row per employee, unique on `user_id`, materialised the first time anybody asks for it. That is what makes §8's "from the moment they join" and §5 step 9's "resumes the suspended development plan with its goals and progress intact" the same fact - a per-cycle plan would make carry-over a copy, and a copy is where progress gets lost. |
| **P-5.10** | An approved goal is frozen: it cannot be edited or removed until `mgr(S)` reopens it (409). Reopening is gated on `APPROVE_GOAL`, the same authority that granted the approval, so the two cannot drift apart. |
| **P-5.6** | A PIP must carry non-empty consequence-clause text before it can be co-signed. |
| **P-5.16** | **A PIP follows a completed review.** `openImprovementPlan` is refused with 409 unless S has a final rating that has been **released** to them in some cycle. Released, not merely set: scenario §5 shares the rating at step 6 and routes the plan track at step 7, so a rating still sitting with HR is a decision the employee has not been given. Any cycle rather than the current one, because §9 allows a slipped development plan to be suspended in favour of a new improvement plan later.<br><br>409 and not 403: `mgr(S)` holds `OPEN_IMPROVEMENT_PLAN` on their report throughout, and it is the absent review that refuses.<br><br>*Consequence, stated rather than hidden: an employee who has never been through a review cycle cannot be put on an improvement plan. That follows from §5 and is the intended reading, but it means somebody hired between cycles is out of reach of the instrument until the next one closes.* |
| **P-5.7** | **Exclusivity invariant.** No employee holds an ACTIVE PDP and an ACTIVE PIP simultaneously. Enforced in `PlanService` **and** by a database constraint: `improvement_plan.active_user_id` is a stored generated column holding `user_id` only while the plan is ACTIVE, carrying a unique index. MySQL treats NULLs as distinct, so any number of closed plans coexist and a second ACTIVE one cannot be inserted at all. Opening suspends the PDP in the **same transaction**; passing or failing resumes that **same row**, goals and progress intact. |
| **P-5.11** | An improvement plan is written **to** the employee, not with them. `WRITE_IMPROVEMENT_PLAN` carries `DIRECT_MANAGER` only - no `SELF`, unlike `WRITE_DEVELOPMENT_PLAN`. An employee able to edit their own consequence clause could soften it. |
| **P-5.12** | A plan that was never co-signed **cannot be passed or failed** (409): the employee never saw it, so no outcome may be recorded against it. A plan can only be **failed once its deadline has passed** - the literal reading of "deadlines missed", and the protection the fixed deadline exists to give. |
| **P-5.13** | The consequence clause is fixed once co-signed (409). It is what the employee accepted; changing it afterwards would make the signature meaningless. |
| **P-5.14** | **Failing also resumes the development plan.**<br><br>*A judgment call, not a rule from the documents: §5 step 9 only says a passed plan resumes it. Leaving it suspended would leave the employee holding no active plan at all, contradicting §8's universal development plan. A failed PIP records an outcome; it does not end somebody's development.* |
| **P-5.15** | The **improvement-plan queue** - running plans in an HR user's granted departments - takes its scope from `COSIGN_IMPROVEMENT_PLAN`, whose only ground is `HR_IN_SCOPE`. A manager therefore receives an empty list rather than a denial, which is the same shape the scoped review list takes for somebody who may see nothing. Unlike the per-person read it is **not** gated on co-signature: an uncosigned plan is invisible to its subject (P-5.3) and must be visible to HR, because co-signing it is their job and a plan they cannot see is one they cannot review. The caller's own row is excluded (P-2.2). |
| **P-5.8** | Only `PlanService` mutates plan status. No controller or repository writes a status field. |
| **P-5.9** | **A development goal is drafted by `mgr(S)` and agreed by S.** The employee writes no goals of their own. The lifecycle is per goal: `DRAFT` (invisible to S entirely), `PENDING` (submitted, S can see it and agree), `AGREED` (S has accepted; the wording is fixed and progress reporting opens).<br><br>Agreement is `SELF` **alone** - not the manager who wrote it, not HR-in-scope, not the Super Admin. There is no "decline": an un-agreed goal stays visibly pending, and a refusal recorded in the system would be a disagreement with a manager written into the employee's own development record.<br><br>Once agreed the wording is **fixed** (409, not 403 - the manager still holds the capability and it is the agreement that refuses). A goal whose text could change afterwards is not one that was agreed to. The target date still moves under P-5.5.<br><br>**Progress** is a field of its own, written by S or `mgr(S)`, never over the goal's wording - so reporting progress cannot restate the goal. It requires agreement first.<br><br>An improvement goal has **no** agreement state at all. A PIP is put *to* an employee: one who could withhold agreement could stall the plan meant to correct their performance.<br><br>*Product Owner ruling, and a **deviation from scenario §8** ("collaborative between manager and employee") and **§6** ("manager and employee own it"). The collaboration survives as agreement and progress rather than as shared authorship. Without it the PDP and the PIP would become the same instrument, distinguished only by name.* |

## P-6 Cycles and configuration

| # | Policy |
|---|---|
| **P-6.1** | Only the Super Admin writes `review_cycle`, `cohort` and `cohort_member`. Enforced by the `CONFIGURE_CYCLE` capability inside `CycleService`, not by a role annotation on the controller. |
| **P-6.2** | The cycle start date may be changed **only while `opened_at IS NULL`**; afterwards refused with **409**, not 403. The Super Admin holds the permission; it is the cycle's state that forbids the write. |
| **P-6.3** | HR monitors cycles for their granted departments only - counts and statuses scoped by `grants(A)`, subject to P-2.2 and P-2.3. **The caller's own participant row is excluded from every total**, so an HR Head with an explicit grant oversees their department without their own case being part of what they oversee. Monitoring returns counts and never names a person; anyone needing the rows uses the scoped review list. |
| **P-6.4** | The sweep runs as a **system principal**: it bypasses user authorization (there is no user) but remains bound by every domain invariant. Idempotency and "date arrived or passed" are properties of the selection predicate (`opened_at IS NULL AND start_date <= :today`), not of a flag anybody maintains. Intake applies P-0.7 and P-1.5 inside the query. |
| **P-6.5** | A cohort attached to no quadrimester is never swept in, and a cycle whose quadrimester has no cohort opens **empty rather than failing**. Both are visible to HR through P-6.3, which is the trade-off §15.4 accepts. |
| **P-6.6** | An employee belongs to **at most one cohort**, enforced by a unique key on `cohort_member.user_id`. This is what makes §4's "assessed once per year in a fixed quadrimester" a guarantee of the schema. Moving somebody between cohorts is an update, never a second row. |

## P-7 Leadership

| # | Policy |
|---|---|
| **P-7.1** | Leadership receives **aggregate metrics only**. No endpoint returns an individual review, rating or plan row to Leadership. Dashboards are Sprint 2, but the policy holds from day one so no drill-down endpoint is ever built. |
| **P-7.2** | Leadership are **not reviewees (P-1.5) and hold no PDP and no PIP**.<br><br>*Resolves a source-document conflict: §7 excludes Leadership from review; §8 says every employee holds a PDP. §8 means every reviewable employee.* |
| **P-7.3** | Leadership act as reviewer and peer-assigner for the tier directly below them, including the HR Head (P-2.6). |
| **P-7.4** | The metrics response **contains no id, name or handle of any person**, so there is nothing a client could build a drill-down link from. P-7.1 is enforced by the shape of the payload, not by a link somebody remembered not to render. |
| **P-7.5** | The **rating distribution is organisation-wide and is never broken down by department**. A department with one participant would make its distribution that person's rating, and Leadership hold no grounds to read an individual rating. Completion counts *are* broken down by department, because they say only that a rating exists, not what it is. |

| **P-7.6** | **The manager's and HR's dashboard takes `READ_REVIEW_SUMMARY`'s scope**, so a total counts exactly the people whose reviews the caller may open and never one more. One endpoint serves both consoles, as the review list does. The caller's own row is removed first (P-6.3), which also leaves the remaining grounds as exactly `DIRECT_MANAGER` and `HR_IN_SCOPE` - the grounds of `READ_PEER_REVIEW` - so the peer-submission count is safe by construction rather than by care. Leadership, employees and the Super Admin hold none of those grounds and receive an **empty dashboard rather than a denial**, flagged as empty so the client can tell it apart from a cycle nobody has started.<br><br>**The rating distribution is not broken down here and does not need to be**, because it is already confined to one team or one set of granted departments. P-7.5's small-department objection does not apply: a manager may open each report's rating one at a time and HR may open every rating in a granted department, so the aggregate discloses nothing they could not already read. The response carries no id and no name, exactly as P-7.4 requires of Leadership's.|

`GET /api/leadership/metrics?cycleId=` implements all of the above. It is unscoped by department, which is the difference from HR monitoring: HR oversee the departments they were granted, Leadership see the organisation, and neither sees a person. Charts remain Sprint 2; the endpoint exists now so P-7.1 is enforced by something real rather than by an enum constant nothing implements.

## P-8 Exports

| # | Policy |
|---|---|
| **P-8.1** | Export endpoints are HR and Leadership only. **Managers cannot export**, despite being able to read the same rows in the UI (scenario §6). |
| **P-8.2** | An HR export carries `grants(A)` in the `WHERE` clause; a Leadership export returns totals only. |
| **P-8.3** | **The export runs no query of its own.** Every figure comes from the service that already produces it for a screen: `CycleMonitoringService` and `DashboardService` for HR, `LeadershipMetricsService` for Leadership. A hand-written export query would be a second definition of what a caller may see, and the day the two disagreed the file would be the wrong one and already off the premises. Each of those services re-checks the caller, so `EXPORT_REPORT` decides **whether** a file may be taken (P-8.1) and they decide **what is in it** (P-8.2).<br><br>Consequences worth naming. The rating distribution is never per department in a file, for Leadership because P-7.5 forbids it and for HR because that is what their dashboard shows. And no export names any person, because none of those services returns one.|
| **P-8.4** | **`EXPORT_REPORT` is `Kind.GLOBAL`**, because an HR export covers the whole of `grants(A)` rather than one department named in the request. For a global capability `HR_IN_SCOPE` therefore means *holds HR and has been granted something*, rather than *this department is granted*; an HR user with no grants holds no export. This widens nothing that existed: the other global capabilities are `CONFIGURE_CYCLE`, `MANAGE_ORG` and `READ_AGGREGATE_METRICS`, and none lists `HR_IN_SCOPE`.|
| **P-8.5** | **Every export is logged**: who took it, which cycle, which format, the ground the decision was made on, the scope in words, and how many rows. Append-only, written in the same transaction as the export, and read by nobody through the API. Not scenario text - section 13 asks for exports and says nothing about recording them. The team's addition, because a file stops being governed the moment it is saved and an unlogged export makes the authorization model unfalsifiable after the fact.|

## P-10 Reminders

| # | Policy |
|---|---|
| **P-10.1** | **A reminder goes to the person responsible for the task, and to nobody else.** No endpoint or query produces a reminder telling a manager their team is behind, or telling a subject that peer feedback about them is outstanding - the latter would hand them the count P-3.3 withholds everywhere else. Enforced by the shape of the queries: each selects exactly one recipient per row and it is the person who has to act.|
| **P-10.2** | **The email carries a task, a date and a link. Never review, rating or plan content.** Mail leaves this system's access control behind: anything in the body has escaped it and cannot be withdrawn. The link returns the person to a page where the ordinary checks apply.|
| **P-10.3** | **No reminder about an improvement plan that has not been co-signed** (P-5.3). The plan is invisible to the subject until HR sign it, and an email would announce it outside the system. A predicate in the query (`cosignedAt IS NOT NULL`), not a check further up. A development goal on a suspended plan is likewise excluded, since chasing it would hint at the same thing.|
| **P-10.4** | **No reminder about a goal the employee has not agreed to** (P-5.9). A DRAFT goal is not returned to them at all and a PENDING one is theirs to accept rather than to work on, so neither is theirs to be chased about. Deactivated people are not chased either (P-0.7): their rows survive, their inbox is not pursued.|
| **P-10.5** | **The sweep runs as the system**, like the cycle sweep (P-6.4). There is no caller, so no authorization decision is made and none is faked; every rule above lives in a `WHERE` clause, below where authorization would have been. **One email per item, recipient and day**, guaranteed by a unique key rather than by an application check, because two concurrent sweeps would both pass a check and both send.|

## P-11 Meeting scheduling

| # | Policy |
|---|---|
| **P-11.1** | **The plan meeting is called by `mgr(S)`, with S invited** (scenario section 12). `SELF` is absent: an employee cannot summon their manager to a review conversation. HR are absent too, on the same grounds that make them read-only on a PDP (P-5.1) - calling the meeting that agrees a plan is writing to it by another route. |
| **P-11.2** | **The normalization meeting is called by HR-in-scope, with `mgr(S)` invited** (scenario section 5 step 5). **The subject of the capability is S**, whose rating is being calibrated, and who sits in neither seat. That choice makes "may this HR user convene the meeting?" the same question as `CALIBRATE_RATING` (P-4.3), decided by the same department grant and refused by the same own-review block (P-2.2) when the rating is their own. `DIRECT_MANAGER` is absent: a manager who could call this meeting could arrange the review of their own judgment on their own terms. |
| **P-11.3** | **Reading somebody's free/busy requires the capability to book with them.** Slot proposal is gated by the same capability, against the same subject, as the booking itself. A caller with no grounds to schedule cannot use free/busy as a way into a colleague's diary. |
| **P-11.4** | **Only the intersection is returned, never the other party's busy times.** The caller learns that both are free at 10:00, not that the other person's morning was full. Google's free/busy already withholds titles and attendees; this withholds the blocks themselves, which are still a fact about somebody's day that nobody needs in order to book half an hour. |
| **P-11.5** | **The calendar event carries the kind of meeting and nothing else.** No rating, no plan content, and on a normalization meeting not even the name of the employee being calibrated. The event is copied into a personal Google account, appears in email invitations and is visible to anyone either party has shared their calendar with, so nothing this system would withhold may travel in it (the same rule as P-10.2, one channel further out). |
| **P-11.6** | **Altrium acts only as a person who personally consented, and only on their own account.** There is no Workspace, no service account and no delegation. Every endpoint that connects, reads status or disconnects works on the caller and takes no user id, so connecting somebody else's account is not a permission that exists. The refresh token is encrypted at rest. |
| **P-11.7** | **The OAuth callback is the one endpoint without a bearer token, and the `state` parameter is its credential.** It is a browser redirect from Google, so no header can be attached and Altrium holds no session. The state is unguessable, single-use and ten minutes old at most, and one this server did not mint attaches nothing to anybody. |
| **P-11.8** | **A meeting is visible to the two people in it.** The list query is scoped to the caller's own id with no parameter that could widen it (P-0.3). This is narrower than the capability that booked the meeting: HR who could convene a calibration see it because they are in it, not because they hold a grant. |

## P-9 Super Admin

| # | Policy |
|---|---|
| **P-9.1** | Manages users, reporting lines, departments and activation flags. |
| **P-9.2** | Manages HR department grants, including the explicit-grant flag. |
| **P-9.3** | Manages cycle configuration and cohort membership. They decide who is reviewed and when, and can read none of the result (P-9.4) - including the monitoring counts, which are HR's. |
| **P-9.4** | **No read access to any review, rating or plan content.**<br><br>*Not addressed in the source documents; settled by the team. Since the Super Admin grants HR their departments, review access on top would make the role omnipotent and defeat segregation of duties.* |
| **P-9.5** | **A dedicated account, and never a reviewee.** SUPER_ADMIN is held alone: MANAGER, HR and LEADERSHIP are refused alongside it (400). The account holds no self-review, rating or plan, and cannot be enrolled in a cohort or assigned as anyone's peer. Enforced at **step 2** of P-0.6, alongside the Leadership exclusion, so a participant row arriving by any other route still yields nothing readable.<br><br>EMPLOYEE **is** retained, and that is not a loophole. Employee is not a job in this schema; it is the marker that a caller is provisioned and active, required by `SecurityConfig` on every authenticated endpoint. An account without it could not reach the administration console it exists to use. P-9.5 removes being a *reviewee*, not the marker.<br><br>*Product Owner ruling, and a **deviation from P-0.1**, which states that roles are additive and never exclusive. The reasoning is the same as P-9.4's: this account grants the HR users their departments and configures the cycles, so a reviewing role on top would let one account arrange the scope and then act inside it. It reverses the earlier seeding, in which Devin Marsh was an ordinary engineer who also administered - a deliberate demonstration of P-9.4 that no longer holds.* |

---

## Denial test matrix

Every row is a named JUnit test calling the endpoint **directly** via `MockMvc` with a minted JWT - never through the UI. A UI-driven test proves a button is hidden, not that access is refused.

| Test | Policy | Expect |
|---|---|---|
| Manager reads a non-report's review | P-1.2 | 403 |
| Manager reads a non-report's plan | P-1.2, P-5.1 | 403 |
| HR writes, edits or approves anything on a PDP in scope | P-5.1 | 403 |
| Employee moves their own goal's target date | P-5.5 | 403 |
| Employee approves their own goal | P-5.2 | 403 |
| Goal id lifted from another person's plan | P-0.4 | 403 |
| Leadership member's own PDP requested, by them or their manager | P-7.2 | 403 |
| Approved goal edited or removed before being reopened | P-5.10 | 409 |
| Subject requests peer reviews about themselves, by every route including list endpoints and pagination counts | P-3.3 | 403 / absent |
| HR acts in their own department without the explicit grant | P-2.3 | 403 |
| **HR with an explicit grant calibrates their own rating, co-signs their own PIP, or reads their own peer feedback** - the rule-ordering test | P-0.6, P-2.2, P-2.4 | 403 |
| HR with an explicit grant reads their own released rating and manager feedback | P-2.2, P-4.4 | permitted, on `SELF` grounds |
| HR grant revoked mid-session, next request with the same token | P-2.5 | 403, no re-login |
| Subject reads a PIP before co-sign | P-5.3 | 403 |
| Manager attempts co-sign or witness | P-5.4 | 403 |
| Any actor extends a PIP deadline - manager, HR, Super Admin, subject | P-5.5 | 403 |
| Any actor moves a target date on a PIP **goal** | P-5.5 | 403 |
| Employee writes a goal or consequence clause on their own PIP | P-5.11 | 403 |
| HR or another manager opens a PIP | P-1.2, P-5.7 | 403 |
| PIP co-signed with no consequence clause | P-5.6 | 400 |
| PIP opened for somebody whose rating has never been released | P-5.16 | 409 |
| Subject reads their own PDP while an uncosigned PIP suspends it | P-5.3 | presented as ACTIVE |
| Consequence clause edited after co-signing | P-5.13 | 409 |
| HR user's own improvement plan appears in their own queue | P-2.2, P-5.15 | excluded from the list |
| Manager or Super Admin requests the improvement-plan queue | P-5.15 | empty list, not a denial |
| Second PIP opened for the same employee, including concurrently | P-5.7 | 409, one row |
| Goal added to a suspended PDP | P-5.7 | 409 |
| PIP passed with goals outstanding, or closed before co-signing | P-5.12 | 409 |
| PIP failed before its deadline | P-5.12 | 409 |
| Leadership requests an individual review | P-7.1 | 403 |
| Leadership is made a reviewee | P-1.5, P-7.2 | rejected at creation |
| Manager, HR, Super Admin or employee requests leadership metrics | P-7.1 | 403 |
| Leadership requests a rating distribution for one department | P-7.5 | not offered; the endpoint returns one organisation-wide distribution |
| Super Admin requests review content, or cycle monitoring counts | P-9.4 | 403 |
| HR configures a cycle or a cohort, however wide their grants | P-6.1 | 403 |
| Manager requests cycle monitoring for a department | P-6.3 | 403 |
| HR monitors their own department on a plain grant | P-2.3, P-6.3 | 403 |
| HR Head monitors their own department; their own row in the counts | P-2.2, P-6.3 | department permitted, own row excluded |
| Super Admin reschedules a cycle that has opened | P-6.2 | 409 |
| Super Admin removes a mid-cycle employee from their cohort | §15.4 | 409, path unbuilt |
| Peer submits twice | P-3.5 | 409 |
| Manager is assigned as their own report's peer | P-3.6 | rejected at assignment |
| Unassigned colleague submits peer feedback | P-3.4 | 403 |
| Subject asks who was assigned to review them | P-3.3, P-3.9 | 403 |
| Subject, or a manager who is not `mgr(S)`, lists peer candidates for S | P-3.11 | 403 |
| Super Admin lists peer candidates | P-3.11, P-9.4 | 403 |
| Manager assigns peers for somebody who is not their report | P-1.2, P-3.6 | 403 |
| HR writes a manager review, however wide their grants | P-3.7 | 403 |
| Peers reassigned after feedback has been submitted | P-3.8 | 409 |
| An HR user assigned as a peer outside their own department | P-3.12 | 400 |
| A Leadership member assigned as a peer | P-3.13 | 400 |
| Any review written while the cycle is not open | P-3.10 | 409 |
| Self-review or manager review edited after submission | P-3.1, P-3.7 | 409 |
| **HR with an explicit grant calibrates their own rating** - the rule-ordering test, at the point it matters most | P-0.6, P-2.2, P-2.4 | 403 |
| HR, another manager, or the subject sets a final rating | P-4.1 | 403 |
| Manager calibrates a rating | P-4.3 | 403 |
| Subject reads their own calibration trail | P-4.7 | 403 |
| Manager changes the rating after submitting it, before HR act | P-4.1 | 409 |
| Manager sets the rating back after HR calibrated it | P-4.1, P-4.3 | 409 |
| Rating changed, calibrated, or released again after release | P-4.1, P-4.3, P-4.6 | 409 |
| Manager shares a rating HR have not signed off, where HR are in scope | P-4.8 | 409 |
| HR sign off their own rating, however explicit their grant | P-2.2, P-4.8 | 403 |
| Subject reads their rating before release; and after | P-4.4 | withheld, then permitted |
| Subject reads their **manager's feedback** before release; and after | P-3.7, P-4.4 | withheld, then permitted |

| HR schedules the plan meeting, however wide their grant | P-11.1, P-5.1 | 403 |
| An employee books a plan meeting into their own manager's diary | P-11.1 | 403 |
| A manager convenes the normalization meeting that calibrates their own judgment | P-11.2 | 403 |
| **HR with an explicit grant convenes the calibration of their own rating** | P-2.2, P-11.2 | 403 |
| Somebody who could not book the meeting reads the other party's free/busy | P-11.3 | 403 |
| A third party lists a meeting they are not in | P-0.3, P-11.8 | absent from the list |
| A second plan meeting is booked for the same employee | state, not access | 409 |
| An OAuth callback carrying a reused, forged or expired state | P-11.7 | nothing attached |

**Correctness tests alongside:** sweep is idempotent (run twice, one cycle opened) · a past-dated cycle opens on the next sweep · a suspended PDP resumes with goals and progress intact · the exclusivity invariant holds under two concurrent open-PIP requests, against real MySQL · a reporting-line loop is rejected.
