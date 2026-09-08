import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Timeline } from './Timeline'
import type { TimelineEntry } from '../../api/types'

/**
 * The history list.
 *
 * **This is not a test of who may read a history.** That is decided in the query and proved by
 * `HistoryTest`, calling the endpoint directly with a minted token. Rendering a list somebody
 * handed the component proves nothing about access.
 *
 * What is proved here is the part that is genuinely the client's, and it is a real risk: the
 * server returns null for a rating that is with HR and null for a rating that was never set,
 * deliberately indistinguishable (P-4.4). A screen that rendered "withheld" for one of them
 * would reconstruct in the browser exactly the disclosure the null exists to prevent. So the
 * words used for a missing rating are worth pinning down.
 */
describe('Timeline', () => {
  const entry = (over: Partial<TimelineEntry>): TimelineEntry => ({
    cycleId: 1,
    financialYear: 2026,
    quadrimester: 2,
    cycleStatus: 'CLOSED',
    startDate: '2026-05-01',
    endDate: '2026-08-31',
    rating: null,
    releasedAt: null,
    managerFeedback: null,
    ...over,
  })

  it('says nothing about why a rating is missing', () => {
    render(<Timeline entries={[entry({})]} emptyMessage="none" />)

    expect(screen.getByText('Not shared')).toBeInTheDocument()
    // The two words that would turn an absent value back into a disclosure.
    expect(screen.queryByText(/withheld/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/pending/i)).not.toBeInTheDocument()
  })

  it('renders a released rating and the feedback that travelled with it', () => {
    render(
      <Timeline
        entries={[entry({
          rating: 'MEETS_EXPECTATIONS',
          releasedAt: '2026-09-01T10:00:00Z',
          managerFeedback: 'A solid year.',
        })]}
        emptyMessage="none"
      />,
    )

    expect(screen.getByText('Meets expectations')).toBeInTheDocument()
    expect(screen.getByText('A solid year.')).toBeInTheDocument()
  })

  it('keeps the order the server gave it, newest first', () => {
    render(
      <Timeline
        entries={[
          entry({ cycleId: 2, financialYear: 2026, quadrimester: 2 }),
          entry({ cycleId: 1, financialYear: 2025, quadrimester: 3 }),
        ]}
        emptyMessage="none"
      />,
    )

    const headings = screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent)
    expect(headings).toEqual(['FY2026 · Q2', 'FY2025 · Q3'])
  })

  it('explains an empty history rather than showing a blank panel', () => {
    render(<Timeline entries={[]} emptyMessage="No cycles yet." />)

    expect(screen.getByText('No cycles yet.')).toBeInTheDocument()
  })
})
