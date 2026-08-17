import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { CycleSelect, useSelectedCycle } from '../../components/CycleSelect'
import { useLeadershipMetrics, type DepartmentTotals } from '../../api/leadership'
import { Card, Fact, when } from '../../components/Form'
import { EmptyState, Loading, QueryFailure } from '../../components/States'
import { RATINGS, RATING_LABELS } from '../../api/types'

/**
 * The Leadership landing screen (P-7.1).
 *
 * **Nothing here is clickable through to a person.** No row links to a review, no bar drills
 * into a list, and no name appears anywhere - because the response carries none, and because
 * there is no endpoint such a link could point at. The absence is the policy: Leadership are
 * given the state of the cycle, not a way into it.
 *
 * The rating distribution is **organisation-wide and is not broken down by department**, and
 * this screen offers no control that would break it down. A department with one participant
 * would make its distribution that person's rating handed to somebody with no grounds to read
 * an individual rating - and it would do so without any individual row appearing on screen,
 * which is the kind of leak that survives a review. The completion table below *is* per
 * department, because those counts say only that a rating exists, not what it is.
 */
export function Metrics() {
  const { cycles, cycleId, setCycleId } = useSelectedCycle()
  const { data, isPending, error } = useLeadershipMetrics(cycleId)

  return (
    <>
      <h1 className="mb-1 text-xl font-semibold tracking-tight">Performance across Altrium</h1>
      <p className="mb-4 text-sm text-muted">
        Totals for the organisation. Individual reviews, ratings and plans are not shown to
        Leadership, and there is no way to open one from here.
      </p>
      <CycleSelect cycles={cycles} cycleId={cycleId} onChange={setCycleId} />

      {cycleId === undefined ? null : isPending ? (
        <Loading />
      ) : error ? (
        <QueryFailure error={error} />
      ) : (
        <div className="grid gap-4">
          <Card>
            <Fact label="Cycle">{data.cycle.label}</Fact>
            <Fact label="Status">{data.cycle.status.toLowerCase()}</Fact>
            <Fact label="Runs">
              {when(data.cycle.startDate)} to {when(data.cycle.endDate)}
            </Fact>
            <Fact label="Opened">
              {data.cycle.openedAt ? when(data.cycle.openedAt) : 'not yet'}
            </Fact>
          </Card>

          {data.departments.length === 0 ? (
            <EmptyState>
              {data.cycle.openedAt
                ? 'This cycle has opened but has no participants. That usually means the cohort configuration for this quadrimester is wrong.'
                : 'This cycle has not opened yet, so nobody is under review in it.'}
            </EmptyState>
          ) : (
            <>
              <Distribution distribution={data.ratingDistribution} />
              <Completion departments={data.departments} />
            </>
          )}
        </div>
      )}
    </>
  )
}

const RATING_COLOURS: Record<string, string> = {
  NEEDS_IMPROVEMENT: 'oklch(0.62 0.15 40)',
  MEETS_EXPECTATIONS: 'oklch(0.58 0.10 255)',
  EXCEEDS_EXPECTATIONS: 'oklch(0.55 0.13 155)',
}

function Distribution({ distribution }: { distribution: Record<string, number> }) {
  // Built from the scale itself rather than from the keys that happened to arrive, so the
  // three bars are always the same three bars in the same order. A chart whose axis changed
  // between cycles would invite comparisons that were not being made.
  const bars = RATINGS.map((rating) => ({
    rating,
    label: RATING_LABELS[rating],
    total: distribution[rating] ?? 0,
  }))
  const rated = bars.reduce((sum, bar) => sum + bar.total, 0)

  return (
    <Card title="Ratings across the organisation">
      {rated === 0 ? (
        <p className="text-sm text-muted">No ratings have been set in this cycle yet.</p>
      ) : (
        <>
          <div className="h-64 w-full">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={bars} margin={{ top: 8, right: 8, bottom: 8, left: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="oklch(0.91 0.005 260)" vertical={false} />
                <XAxis dataKey="label" tick={{ fontSize: 12 }} stroke="oklch(0.55 0.015 260)" />
                <YAxis allowDecimals={false} tick={{ fontSize: 12 }} stroke="oklch(0.55 0.015 260)" />
                <Tooltip
                  cursor={{ fill: 'oklch(0.95 0.005 260)' }}
                  formatter={(value) => [`${Number(value)} people`, 'Rated'] as [string, string]}
                />
                <Bar dataKey="total" radius={[4, 4, 0, 0]}>
                  {bars.map((bar) => (
                    <Cell key={bar.rating} fill={RATING_COLOURS[bar.rating]} />
                  ))}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          </div>
          <p className="mt-2 text-xs text-muted">
            {rated} rating{rated === 1 ? '' : 's'} set across the organisation. This is not
            broken down by department: in a small one, the breakdown would identify people.
          </p>
        </>
      )}
    </Card>
  )
}

/**
 * Per-department progress.
 *
 * Counts of who has done what, which is a process fact rather than review content. Nothing
 * here says what anybody's rating is, and no cell is a link.
 */
function Completion({ departments }: { departments: DepartmentTotals[] }) {
  const total = (pick: (d: DepartmentTotals) => number) =>
    departments.reduce((sum, department) => sum + pick(department), 0)

  return (
    <Card title="Progress by department">
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="border-b border-line text-left text-muted">
            <tr>
              <th className="p-3 font-medium">Department</th>
              <th className="p-3 font-medium">Under review</th>
              <th className="p-3 font-medium">Self-reviews</th>
              <th className="p-3 font-medium">Manager reviews</th>
              <th className="p-3 font-medium">Ratings set</th>
              <th className="p-3 font-medium">Shared</th>
            </tr>
          </thead>
          <tbody>
            {departments.map((department) => (
              <tr key={department.departmentId} className="border-b border-line/60">
                {/* Deliberately not a link. There is no endpoint behind one. */}
                <td className="p-3">{department.departmentName}</td>
                <td className="p-3">{department.participants}</td>
                <td className="p-3">
                  {department.selfReviewsSubmitted} / {department.participants}
                </td>
                <td className="p-3">
                  {department.managerReviewsSubmitted} / {department.participants}
                </td>
                <td className="p-3">
                  {department.ratingsSet} / {department.participants}
                </td>
                <td className="p-3">{department.ratingsReleased}</td>
              </tr>
            ))}
            <tr className="font-medium">
              <td className="p-3">Altrium</td>
              <td className="p-3">{total((d) => d.participants)}</td>
              <td className="p-3">{total((d) => d.selfReviewsSubmitted)}</td>
              <td className="p-3">{total((d) => d.managerReviewsSubmitted)}</td>
              <td className="p-3">{total((d) => d.ratingsSet)}</td>
              <td className="p-3">{total((d) => d.ratingsReleased)}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </Card>
  )
}
