import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import type { GoogleStatus, SlotProposal } from '../../api/meetings'

const useGoogleStatus = vi.fn()
const useMeetingSlots = vi.fn()
const useScheduleMeeting = vi.fn()

vi.mock('../../api/meetings', async (importOriginal) => ({
  // The labels stay real; only the hooks are replaced, so a renamed meeting type still breaks
  // this test rather than passing against a stub of itself.
  ...(await importOriginal<typeof import('../../api/meetings')>()),
  useGoogleStatus: () => useGoogleStatus(),
  useMeetingSlots: (...args: unknown[]) => useMeetingSlots(...args),
  useScheduleMeeting: () => useScheduleMeeting(),
}))

const { ScheduleMeeting } = await import('./ScheduleMeeting')

/**
 * The scheduling control.
 *
 * **Not a test of who may book a meeting.** That is decided by `SCHEDULE_PLAN_MEETING` and
 * `SCHEDULE_NORMALIZATION_MEETING` on the server and proved by `MeetingTest`, which calls the
 * endpoints with minted tokens. Asserting here that a control is hidden would prove a button
 * is hidden, which is not access control.
 *
 * What is proved here is the one judgment the client makes on its own: whether a list of times
 * is presented as mutually free or as the organiser's own availability. The server sends the
 * same shape for both, distinguished by a flag, and getting it backwards would tell a manager
 * a time works for an employee whose calendar was never read (scenario section 14).
 */
describe('ScheduleMeeting', () => {
  const status = (over: Partial<GoogleStatus> = {}): GoogleStatus => ({
    connected: true,
    googleEmail: 'jane@gmail.example',
    configured: true,
    ...over,
  })

  const proposal = (over: Partial<SlotProposal> = {}): SlotProposal => ({
    slots: [{ start: '2027-03-01T10:00:00Z', end: '2027-03-01T10:45:00Z' }],
    bothCalendarsChecked: true,
    attendeeName: 'John Alvarez',
    minutes: 45,
    ...over,
  })

  const show = (statusData: GoogleStatus, slots?: SlotProposal) => {
    useGoogleStatus.mockReturnValue({ data: statusData, isPending: false, error: null })
    useMeetingSlots.mockReturnValue({ data: slots, isPending: false, error: null })
    useScheduleMeeting.mockReturnValue({ mutate: vi.fn(), isPending: false, error: null })

    return render(
      <MemoryRouter>
        <ScheduleMeeting type="PLAN_MEETING" subjectId={7} subjectName="John Alvarez" />
      </MemoryRouter>,
    )
  }

  /** Nothing is fetched until somebody asks, so every slot assertion goes through this. */
  const findATime = () => fireEvent.click(screen.getByRole('button', { name: 'Find a time' }))

  it('asks the organiser to connect before offering to find a time', () => {
    show(status({ connected: false, googleEmail: null }))

    expect(screen.getByText(/Connect your Google Calendar/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Find a time' })).not.toBeInTheDocument()
  })

  it('renders nothing at all when the server holds no Google credentials', () => {
    // Not a refusal and not the person's problem, so there is no control and no explanation.
    const { container } = show(status({ configured: false }))

    expect(container).toBeEmptyDOMElement()
  })

  it('says the times are mutual when both calendars were read', () => {
    show(status(), proposal({ bothCalendarsChecked: true }))
    findATime()

    expect(screen.getByText(/both free/)).toBeInTheDocument()
  })

  it('says the times are the organiser alone when the other party has not connected', () => {
    // The claim the server did not make. Presenting these as mutual is the failure that
    // matters, because the manager would book one believing it had been checked.
    show(status(), proposal({ bothCalendarsChecked: false }))
    findATime()

    expect(screen.getByText(/has not connected a calendar/)).toBeInTheDocument()
    expect(screen.queryByText(/both free/)).not.toBeInTheDocument()
  })

  it('offers no times, rather than an empty list, when nothing is free', () => {
    show(status(), proposal({ slots: [] }))
    findATime()

    expect(screen.getByText(/Nothing free in the next two weeks/)).toBeInTheDocument()
  })
})
