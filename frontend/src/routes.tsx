import { Suspense, lazy } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { Shell } from './components/Shell'
import { useCurrentUser } from './auth/useCurrentUser'
import { Loading } from './components/States'
import { MyReviews } from './features/my/MyReviews'
import { SelfReview } from './features/my/SelfReview'
import { PeerTasks } from './features/my/PeerTasks'
import { MyPlan } from './features/my/MyPlan'
import { MyHistory } from './features/my/MyHistory'
import { MyImprovementPlan } from './features/my/MyImprovementPlan'
import { Team } from './features/manager/Team'
import { ReviewDetail } from './features/manager/ReviewDetail'
import { MemberPlan } from './features/manager/MemberPlan'
import { CycleMonitoring } from './features/hr/CycleMonitoring'
import { HrReviews } from './features/hr/HrReviews'
import { Calibration } from './features/hr/Calibration'
import { ImprovementPlans } from './features/hr/ImprovementPlans'
import { Users } from './features/admin/Users'
import { Departments } from './features/admin/Departments'
import { HrGrants } from './features/admin/HrGrants'
import { Cycles } from './features/admin/Cycles'
import { Cohorts } from './features/admin/Cohorts'
import { CalendarSettings } from './features/meetings/CalendarSettings'

/**
 * The one lazily loaded route.
 *
 * Recharts is around a third of the whole bundle, and exactly one screen uses it - one that
 * four of the five roles never open. Everything else is loaded eagerly, because splitting
 * routes that share the same handful of components buys nothing and makes the loading states
 * harder to reason about.
 */
const Metrics = lazy(() =>
  import('./features/leadership/Metrics').then((module) => ({ default: module.Metrics })),
)

/**
 * Lazy for the same reason: it draws the same Recharts distribution. Loading a third of the
 * bundle for a screen the employee and Super Admin never open would undo the split above.
 */
const Dashboard = lazy(() =>
  import('./features/dashboard/Dashboard').then((module) => ({ default: module.Dashboard })),
)

/**
 * The route table.
 *
 * The paths match `MeResponse.landingFor` on the server exactly. That is not decoration: the
 * server decides where each role lands after login, and a client that invented its own paths
 * would send people to routes the server had never heard of.
 *
 * Every console is reachable by URL whether or not its link is rendered. There is no
 * client-side role guard on any route, on purpose - a guard here would look like access
 * control without being any, and would hide the fact that the endpoint behind the screen is
 * what actually refuses.
 */
export function AppRoutes() {
  return (
    <Routes>
      <Route element={<Shell />}>
        <Route index element={<Landing />} />

        <Route path="my/reviews" element={<MyReviews />} />
        <Route path="my/self-review" element={<SelfReview />} />
        <Route path="my/peer-tasks" element={<PeerTasks />} />
        {/*
          The rating used to have a page of its own. It says the same thing "My review" already
          says, on the same cycle, from an endpoint that answers the same question - and two
          screens for one fact is two places for the wording of a withheld rating to drift.
          Redirected rather than dropped, for anybody holding the old link.
        */}
        <Route path="my/rating" element={<Navigate to="/my/reviews" replace />} />
        <Route path="my/history" element={<MyHistory />} />
        <Route path="my/plan" element={<MyPlan />} />
        <Route path="my/improvement-plan" element={<MyImprovementPlan />} />

        <Route
          path="dashboard"
          element={
            <Suspense fallback={<Loading />}>
              <Dashboard />
            </Suspense>
          }
        />

        {/*
          Where Google's OAuth redirect lands. The path is in the server's `altrium.app-url`
          plus `/settings/calendar`, so the two must stay in step: change one and the consent
          round trip returns people to a page that does not exist.
        */}
        <Route path="settings/calendar" element={<CalendarSettings />} />

        <Route path="manager/team" element={<Team />} />
        <Route path="manager/reviews/:subjectId" element={<ReviewDetail />} />
        <Route path="manager/plans/:userId" element={<MemberPlan />} />

        <Route path="hr/cycles" element={<CycleMonitoring />} />
        <Route path="hr/reviews" element={<HrReviews />} />
        <Route path="hr/reviews/:subjectId" element={<Calibration />} />
        <Route path="hr/improvement-plans" element={<ImprovementPlans />} />

        <Route
          path="leadership/metrics"
          element={
            <Suspense fallback={<Loading />}>
              <Metrics />
            </Suspense>
          }
        />

        <Route path="admin/users" element={<Users />} />
        <Route path="admin/departments" element={<Departments />} />
        <Route path="admin/hr-grants" element={<HrGrants />} />
        <Route path="admin/cycles" element={<Cycles />} />
        <Route path="admin/cohorts" element={<Cohorts />} />

        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  )
}

/**
 * Sends the caller wherever the server said.
 *
 * `landing` is obeyed, never recomputed. The precedence between additive roles - a manager
 * who is also HR - is decided once, on the server, and duplicating that ordering here would
 * give the two places to disagree.
 */
function Landing() {
  const { data: me, isPending } = useCurrentUser()

  if (isPending) {
    return <Loading />
  }
  if (!me) {
    // The shell has already rendered the reason; nothing useful to add here.
    return null
  }
  return <Navigate to={me.landing} replace />
}
