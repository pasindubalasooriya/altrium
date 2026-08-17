import { Navigate, Route, Routes } from 'react-router-dom'
import { Shell } from './components/Shell'
import { useCurrentUser } from './auth/useCurrentUser'
import { EmptyState, Loading } from './components/States'
import { MyReviews } from './features/my/MyReviews'
import { SelfReview } from './features/my/SelfReview'
import { PeerTasks } from './features/my/PeerTasks'
import { MyRating } from './features/my/MyRating'
import { MyPlan } from './features/my/MyPlan'
import { MyImprovementPlan } from './features/my/MyImprovementPlan'
import { Team } from './features/manager/Team'
import { ReviewDetail } from './features/manager/ReviewDetail'
import { MemberPlan } from './features/manager/MemberPlan'
import { HrScope } from './features/hr/HrScope'
import { CycleMonitoring } from './features/hr/CycleMonitoring'
import { HrReviews } from './features/hr/HrReviews'
import { Calibration } from './features/hr/Calibration'
import { ImprovementPlans } from './features/hr/ImprovementPlans'

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
        <Route path="my/rating" element={<MyRating />} />
        <Route path="my/plan" element={<MyPlan />} />
        <Route path="my/improvement-plan" element={<MyImprovementPlan />} />

        <Route path="manager/team" element={<Team />} />
        <Route path="manager/reviews/:subjectId" element={<ReviewDetail />} />
        <Route path="manager/plans/:userId" element={<MemberPlan />} />

        <Route path="hr/scope" element={<HrScope />} />
        <Route path="hr/cycles" element={<CycleMonitoring />} />
        <Route path="hr/reviews" element={<HrReviews />} />
        <Route path="hr/reviews/:subjectId" element={<Calibration />} />
        <Route path="hr/improvement-plans" element={<ImprovementPlans />} />

        <Route path="leadership/metrics" element={<ToBuild phase="6" name="Metrics" />} />

        <Route path="admin/users" element={<ToBuild phase="7" name="Users" />} />
        <Route path="admin/departments" element={<ToBuild phase="7" name="Departments" />} />
        <Route path="admin/hr-grants" element={<ToBuild phase="7" name="HR department grants" />} />
        <Route path="admin/cycles" element={<ToBuild phase="7" name="Cycles" />} />
        <Route path="admin/cohorts" element={<ToBuild phase="7" name="Cohorts" />} />

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

/** Honest placeholder. Says which phase builds it rather than pretending to be empty. */
function ToBuild({ name, phase }: { name: string; phase: string }) {
  return (
    <>
      <h1 className="mb-4 text-xl font-semibold tracking-tight">{name}</h1>
      <EmptyState>Not built yet - frontend phase {phase}.</EmptyState>
    </>
  )
}
