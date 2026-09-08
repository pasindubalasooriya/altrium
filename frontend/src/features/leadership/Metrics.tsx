import { CycleSelect, useSelectedCycle } from '../../components/CycleSelect'
import { useLeadershipMetrics, type DepartmentTotals } from '../../api/leadership'
import { Card, Fact, when } from '../../components/Form'
import { EmptyState, Loading, QueryFailure } from '../../components/States'
import { RatingBars } from '../dashboard/RatingBars'
import { ExportButtons } from '../dashboard/ExportButtons'

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
      <h1 className="mb-4 text-xl font-semibold tracking-tight">Performance across Altrium</h1>
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

              {/*
                Leadership export the company totals; HR export their departments from the
                dashboard. Each control sits beside the report it produces, so what the file
                covers is what the person is looking at.
              */}
              {cycleId !== undefined && <ExportButtons cycleId={cycleId} />}
            </>
          )}
        </div>
      )}
    </>
  )
}

/**
 * One hue, light to dark, on the brand amber.
 *
 * The three ratings are an **ordinal** scale, so they get a sequential ramp rather than three
 * unrelated hues: darker means a higher rating, and the bars read in order even to somebody who
 * cannot separate the hues at all.
 *
 * The obvious alternative, red-amber-green, was measured and rejected: red against green comes
 * out at deutan delta-E 5.5, which is below the floor - the two ends of the scale would be the
 * pair a red-green colourblind reader could least tell apart, which is the worst possible place
 * to put the confusion. These three steps pass the lightness, step-gap, single-hue and
 * surface-contrast checks.
 *
 * It also keeps the chart from editorialising. A red "Needs Improvement" bar states a judgement
 * the distribution does not make; Leadership are reading how a population is spread, not being
 * told which end is the bad one.
 */
function Distribution({ distribution }: { distribution: Record<string, number> }) {
  return (
    <Card title="Ratings across the organisation">
      <RatingBars
        distribution={distribution}
        empty="No ratings have been set in this cycle yet."
      />
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
