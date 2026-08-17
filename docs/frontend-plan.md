# Frontend plan

The nine phases of the Sprint 1 frontend, with what each one covers and why the awkward parts are the way they are. Companion to [PROJECT-PLAN.md](../../PROJECT-PLAN.md), which carries the one-line build-order status, and to [authorization-policy.md](authorization-policy.md), which is the policy list this all defers to.

## Status

| Phase | Covers | Status |
|---|---|---|
| 0 | Backend prerequisites: CORS, the leadership metrics endpoint | done, 195 backend tests |
| 1 | Scaffold, Asgardeo login, `/me`, the shell | done |
| 2 | Shared foundations: fetch wrapper, error model, pager, forms | done, 10 frontend tests |
| 3 | Employee console, `/my/*` | done |
| 4 | Manager console, `/manager/*` | done, 202 backend + 17 frontend tests |
| 5 | HR console, `/hr/*` | done, 208 backend + 17 frontend tests |
| 6 | Leadership, `/leadership/metrics` | next |
| 7 | Super Admin console, `/admin/*` | |
| 8 | Verification: Vitest and the manual walkthrough | |

Phases 3 to 7 are independent of each other and can be built in any order. The order above front-loads the flows that carry the marks.

## The two rules the whole thing hangs off

**The UI never computes permission.** It renders what the server returned. `ReviewRecordView.visibleSections` exists for exactly this: it names the sections the caller had grounds for, so the client renders "you cannot see peer feedback" instead of inferring it from a null. Client-side role checks decide navigation only, and are cosmetic by definition.

**A 403 is a designed state, not an error boundary.** Every screen renders it, and renders it identically.

The frontend is not where authorization lives. The backend already refuses everything it should; the UI's job is to render honestly what the caller was given and to make a refusal legible rather than a crash.

---

## Phase 0 - backend prerequisites (done)

**CORS.** There was none, so a Vite app on `:5173` could not make a single call: the preflight fails before the filter chain runs, no token is ever presented, and every screen fails identically with nothing in the log. Origins come from `altrium.cors.allowed-origins`; credentials stay off, because every request carries a bearer token and there is no cookie to attach. Fixed in `SecurityConfig` rather than proxied away in Vite, which would have hidden the problem until the first deployment.

**Leadership metrics.** `Capability.READ_AGGREGATE_METRICS` existed with `LEADERSHIP` grounds and no endpoint implementing it, while `MeResponse` sends every Leadership user to `/leadership/metrics`. `GET /api/leadership/metrics?cycleId=` now returns per-department completion counts and an organisation-wide rating distribution, aggregated in SQL. See P-7.4 and P-7.5 for why the distribution is never broken down by department.

## Phase 1 - scaffold, auth and the shell (done)

`altrium/frontend/`, in the same local repo. Stack as fixed by [tech-stack.md](../../requirements/tech-stack.md): React + Vite + TypeScript + Tailwind, `@asgardeo/auth-react`, TanStack Query, React Hook Form + Zod, React Router v6, Recharts.

```
altrium/frontend/src/
  auth/        AltriumAuthProvider, RequireAuth, useCurrentUser
  api/         client.ts, errors.ts, types.ts
  components/  Shell, Pager, States (Forbidden, NotProvisioned, ...)
  features/    my/ manager/ hr/ leadership/ admin/
  routes.tsx
```

`GET /api/me` is the source of truth for identity, **not the token**. The backend derives authorities from `user_role` and ignores the role claim, so a UI reading roles out of the ID token could disagree with the server, and would disagree in the direction of showing more. `landing` is obeyed rather than recomputed.

Sign-out calls `signOut()`, which hits the end-session endpoint. Clearing tokens locally leaves the Asgardeo session alive, so the next sign-in reuses it silently and the user appears unable to log out.

Two states the shell renders before any feature exists, because the seed data guarantees both: an authenticated subject with no `app_user` row, and an expired token.

## Phase 2 - shared foundations (done)

**`api/client.ts`** is the only place a request leaves the app. Nothing else calls `fetch`: one place to attach a token is one place to get it wrong. The token is requested per request rather than captured at mount, because the SDK refreshes underneath.

**The four outcomes stay four:**

| Status | Meaning | UI |
|---|---|---|
| 401 | Not identified yet | Re-authenticate |
| 403 | Access denied, deliberately indistinguishable from not-found | `<Forbidden/>`, same copy every time |
| 409 | Caller *has* permission; the record's state refuses | Inline on the form, never `<Forbidden/>` |
| 400 | Validation | Field-level errors |

404 gets no kind of its own, and no screen renders "not found".

**`components/Pager`** drives `page` and `size` as query parameters and never receives an array to slice. `totalElements` already counts only what the caller may see, and stays honest exactly as long as the client shows it unchanged.

**Types carry policy where they can.** `OwnReviewRecord` has no `peerReviews` field at all - absent, not optional - so no component in the employee console can render peer content even if the server somehow sent it.

---

## Phase 3 - employee console (`/my/*`)

| Route | Endpoints |
|---|---|
| `/my/reviews` | `GET /api/reviews/cycles`, `GET /api/reviews?cycleId=`, `GET /api/reviews/{me}?cycleId=` |
| `/my/self-review` | `PUT /api/reviews/self-review` |
| `/my/peer-tasks` | `GET /api/reviews/my-peer-assignments`, `POST /api/reviews/{subjectId}/peer-review` |
| `/my/rating` | `GET /api/reviews/my-rating` |
| `/my/plan` | `GET /api/plans/development/me`, goal create, edit, delete |
| `/my/improvement-plan` | `GET /api/plans/improvement/me` |

**Peer anonymity is structural on this side too.** No component under `features/my/` imports a peer-review type, and the own-record screen renders strictly from `visibleSections`. Peer *count* is equally off limits: no "2 peers assigned" badge, no submission progress bar on the employee's own record.

**`/my/rating` renders "no rating yet" and "rating withheld" identically.** `OwnRatingView` was built so the two are indistinguishable; a UI saying "awaiting release" for one and "not yet rated" for the other would put back the disclosure the DTO removed.

**`/my/improvement-plan`** uses `hasPlan`. Before HR co-signs, the plan is invisible, and it shows nothing the *same way* it would if no plan existed.

Self-review is a `PUT` with no subject id, so the form has no employee selector to get wrong.

## Phase 4 - manager console (`/manager/*`)

| Route | Endpoints |
|---|---|
| `/manager/team` | `GET /api/reviews?cycleId=` (SQL-scoped to direct reports) |
| `/manager/reviews/{subjectId}` | record, `PUT /{id}/manager-review`, `GET`/`PUT /{id}/peers`, `PUT /{id}/rating`, `POST /{id}/rating/release` |
| `/manager/plans/{userId}` | development plan, goal approval, target dates, PIP lifecycle |

The team list is the **same endpoint** the employee console calls. It returns different rows because the scope is in the `WHERE` clause, not because the client asked differently - the clearest demonstration of the project's central claim, and worth a comment in the code.

**Peer assignment** requires exactly two; the backend rejects the subject, `mgr(S)` and deactivated users. The picker surfaces the server's 400 rather than silently pre-filtering: the rule is the server's, and a client filter that drifted would hide a real failure.

> **Gap found while building this.** A manager had no way to *see* two people to choose: the only endpoint listing users is the Super Admin's. `GET /api/reviews/{subjectId}/peer-candidates` was added, guarded by `ASSIGN_PEERS` rather than a read capability, so the roster opens only to the person assigning and only for one named subject. Recorded as P-3.11, with seven denial tests.

**No aggregate or suggested rating is displayed.** P-4.1 says the rating is chosen, never computed, and a "peer average" hint would make that false in practice while remaining true in the database.

**Co-sign and witness controls are absent, not disabled.** A disabled button implies a permission that might be granted; P-5.4 is a segregation of duties, not a workflow step.

**There is no deadline control anywhere in the UI.** `PUT /plans/improvement/{planId}/deadline` exists in order to refuse everyone. Building a form for it would be building a feature out of a policy.

## Phase 5 - HR console (`/hr/*`)

| Route | Endpoints |
|---|---|
| `/hr/scope` | `GET /api/hr/scope` |
| `/hr/cycles` | `GET /api/cycles/{cycleId}/monitoring`, `/monitoring/{departmentId}` |
| `/hr/reviews` | `GET /api/reviews?cycleId=` (HR-scoped) |
| `/hr/reviews/{subjectId}` | record, plus `GET`/`PUT /{id}/rating/calibration` |
| `/hr/improvement-plans` | `POST /{planId}/cosign`, `POST /{planId}/witness` |

**`/hr/scope` is the first screen HR sees.** It renders the granted departments, marks which is the caller's own, and shows whether the explicit-grant flag lifts the own-department block. HR need to see the shape of their own authority - and this is the screen on which P-2.5 is demonstrable: revoke a grant in the admin console and the very next refetch loses it, with no re-login.

**The caller's own row is absent from the counts and the review list**, because the backend excludes it. The UI must not add a "you" row back from `/api/me` for completeness.

**Co-sign states plainly that it is the moment the plan becomes visible to the employee.** That is the one place a UI sentence carries real weight: it is an irreversible disclosure, and the person clicking should know it.

> **Second gap found while building.** HR had no way to *find* a plan awaiting co-signature. Every route to one started from a person, and the review list only names people under review in a cycle - while a PIP is opened whenever a manager decides to, cycle or no cycle. `GET /api/plans/improvement` now returns the running plans in HR's granted departments, taking its scope from `COSIGN_IMPROVEMENT_PLAN`. Recorded as P-5.15, with six tests.

## Phase 6 - leadership (`/leadership/metrics`)

Consumes the phase 0 endpoint. Recharts bar chart of the rating distribution, and a per-department completion table.

**Nothing is clickable through to a person.** No row links to a review, because there is no endpoint behind such a link and there never will be one. The absence is the policy.

## Phase 7 - Super Admin console (`/admin/*`)

| Route | Endpoints |
|---|---|
| `/admin/users` | `GET`/`POST /api/admin/users`, `PUT /{id}`, `/{id}/manager`, `/{id}/deactivate`, `/{id}/reactivate`, `GET /{id}/direct-reports` |
| `/admin/departments` | `GET`/`POST /api/admin/departments`, `PUT /{id}` |
| `/admin/hr-grants` | `GET /{hrUserId}`, `POST`, `PUT /{hr}/{dept}/explicit`, `DELETE /{hr}/{dept}` |
| `/admin/cycles` | `POST /api/admin/cycles`, `PUT /{id}/dates`, `POST /{id}/open`, `POST /{id}/close` |
| `/admin/cohorts` | cohorts, quadrimester assignment, membership add and remove |

Server-paged users table - the one place the stack explicitly says the console must not assume a small organisation.

**Deactivation shows the dangling-reports warning and says it sets a flag.** "Delete user" would misdescribe what happens, and the history pillar depends on the row surviving.

**Cycle dates are editable only while `opened_at` is null**, and cohort removal after opening returns 409, rendered as the reason it is refused with a note that the section 15.4 behaviour is awaiting the Product Owner. The UI should not paper over an open question.

**No review, rating or plan content appears anywhere in this console** (P-9.4).

## Phase 8 - verification

**Automated.** Vitest and Testing Library, with MSW faking the API, on the pieces where a bug would be a policy bug rather than a cosmetic one:

- `client.ts` maps 401, 403, 409 and 400 to distinct outcomes *(done in phase 2)*
- the own-record screen renders only the sections named in `visibleSections`, and given a response that wrongly contained peer data, still renders none
- `/my/rating` renders identically for a withheld rating and an absent one
- `Pager` requests page 2 from the server and never slices a fetched array

These **do not replace the backend denial tests** and must not be described as if they did. A React test proves a control is hidden; the `MockMvc` denial tests prove access is refused. The backend suite remains the security evidence.

**Manual, against the live tenant.** Two terminals - see [local-setup.md](local-setup.md) section 4.

Walk the five accounts that can actually log in ([seed-data.md](seed-data.md)): **Devin** creates and opens a cycle; **John** writes a self-review and reads his rating once released; **Jane** writes John's manager review, assigns peers, sets and releases the rating, runs a PDP and a PIP; **Kevin** monitors, calibrates, co-signs; **Richard** sees totals only.

Then the checks that matter - each should fail:

- John opens `/manager/reviews/{someone else}` by editing the URL
- John hits `GET /api/reviews/{his own id}` and finds no peer content **in the response body**, not merely none on screen
- Kevin, the HR Head with an explicit grant, opens his own review record
- Devin opens any review URL
- a grant is revoked in `/admin/hr-grants` while Kevin has `/hr/cycles` open, and his next refetch loses the department with no re-login

**One seed-data constraint to plan the demo around:** only five people can log in, and a peer reviewer must be one of them. Jane cannot be John's peer, since she is his manager - assign **Kevin or Devin**, which the backend permits because peer assignment is cross-department.
