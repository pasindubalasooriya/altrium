import { useState } from 'react'
import { Link } from 'react-router-dom'
import {
  MEETING_LABELS,
  useGoogleStatus,
  useMeetingSlots,
  useScheduleMeeting,
  type MeetingType,
  type Slot,
} from '../../api/meetings'
import { Button, Card, WriteFailure } from '../../components/Form'

/**
 * Booking one of the two review meetings (scenario section 12).
 *
 * Placed beside the thing the meeting is about - the plan on the manager's screen, the rating
 * on HR's - rather than on a scheduling page of its own. A meeting exists to have a
 * conversation about a particular record, and a separate screen with a person picker would be
 * a second place deciding who is being met and why.
 *
 * Which of the two meetings this is comes from where it is rendered, and the server decides
 * whether the caller may book it. Nothing here checks a role: a manager who opened HR's screen
 * by URL would see the control and be refused by the endpoint, which is the refusal that counts.
 */
export function ScheduleMeeting({
  type,
  subjectId,
  subjectName,
}: {
  type: MeetingType
  subjectId: number
  subjectName: string
}) {
  const [looking, setLooking] = useState(false)
  const [booked, setBooked] = useState<{ start: string; link: string | null } | null>(null)

  const status = useGoogleStatus()
  const range = searchWindow()
  const slots = useMeetingSlots(type, subjectId, range.from, range.to, looking)
  const schedule = useScheduleMeeting(type, subjectId)

  const book = (slot: Slot) =>
    schedule.mutate(slot.start, {
      onSuccess: (meeting) => {
        setBooked({ start: meeting.when.start, link: meeting.htmlLink })
        setLooking(false)
      },
    })

  if (status.data && !status.data.configured) {
    // Not a permission problem and not worth a button. The server holds no Google credentials,
    // so there is nothing anybody here can do about it.
    return null
  }

  return (
    <Card title={MEETING_LABELS[type]}>
      {booked ? (
        <div className="grid gap-2 text-sm">
          <p>
            Booked for {longWhen(booked.start)}. The invitation has been emailed.
          </p>
          {booked.link && (
            <a
              href={booked.link}
              target="_blank"
              rel="noreferrer"
              className="text-accent underline underline-offset-2"
            >
              Open it in Google Calendar
            </a>
          )}
          <p className="text-xs text-muted">
            Move or cancel it in Google Calendar, where both of you will see the change.
          </p>
        </div>
      ) : status.data && !status.data.connected ? (
        <div className="grid gap-2 text-sm">
          <p className="text-muted">
            The meeting is created in your own Google Calendar, so Altrium needs your
            permission before it can put one there.
          </p>
          <Link to="/settings/calendar" className="text-accent underline underline-offset-2">
            Connect your Google Calendar
          </Link>
        </div>
      ) : (
        <div className="grid gap-3">
          <p className="text-sm text-muted">
            {describe(type, subjectName)}
          </p>

          {!looking && (
            <div>
              <Button type="button" onClick={() => setLooking(true)}>
                Find a time
              </Button>
            </div>
          )}

          {looking && slots.isPending && <p className="text-sm text-muted">Reading calendars.</p>}

          {looking && slots.error && <WriteFailure error={slots.error} />}

          {looking && slots.data && (
            <>
              <p className="text-sm">
                {slots.data.bothCalendarsChecked ? (
                  <>
                    Times you and {slots.data.attendeeName} are both free, {slots.data.minutes}{' '}
                    minutes.
                  </>
                ) : (
                  <>
                    {/*
                      Section 14's fallback, said plainly. There is no shared directory, so
                      without their own connection their calendar is simply unavailable, and
                      offering these as mutual would be a claim the server never made.
                    */}
                    {slots.data.attendeeName} has not connected a calendar, so these are times{' '}
                    <strong>you</strong> are free. Agree one with them before booking.
                  </>
                )}
              </p>

              {slots.data.slots.length === 0 ? (
                <p className="text-sm text-muted">
                  Nothing free in the next two weeks. Clear some time and try again.
                </p>
              ) : (
                <ul className="grid gap-1">
                  {slots.data.slots.map((slot) => (
                    <li key={slot.start}>
                      <button
                        type="button"
                        disabled={schedule.isPending}
                        onClick={() => book(slot)}
                        className="flex w-full cursor-pointer flex-wrap items-baseline gap-x-2 rounded border border-line px-3 py-2 text-left text-sm transition-colors hover:border-ink/25 hover:bg-line/30 disabled:cursor-wait"
                      >
                        <span>{longWhen(slot.start)}</span>
                        <span className="ml-auto text-xs text-muted">book this</span>
                      </button>
                    </li>
                  ))}
                </ul>
              )}

              <div>
                <Button type="button" onClick={() => setLooking(false)}>
                  Cancel
                </Button>
              </div>
            </>
          )}

          <WriteFailure error={schedule.error} />
        </div>
      )}
    </Card>
  )
}

/**
 * What the meeting is for, in the words of the person reading it.
 *
 * The normalization wording says the manager is invited without naming them, because the
 * screen it sits on has already named the employee and the manager is the obvious other party.
 */
function describe(type: MeetingType, subjectName: string): string {
  return type === 'PLAN_MEETING'
    ? `Meet ${subjectName} to agree the plan. The invitation goes to their email.`
    : `Meet this employee's manager to calibrate the rating. The invitation goes to their email.`
}

/**
 * The fortnight ahead.
 *
 * Fixed rather than a date picker, because two weeks is the answer almost every time and a
 * range control would be three more decisions before anybody sees a single slot. Starting
 * tomorrow: a meeting arranged for later today is arranged by walking over.
 */
function searchWindow(): { from: string; to: string } {
  const from = new Date()
  from.setDate(from.getDate() + 1)
  const to = new Date(from)
  to.setDate(to.getDate() + 13)
  return { from: isoDate(from), to: isoDate(to) }
}

function isoDate(date: Date): string {
  return date.toISOString().slice(0, 10)
}

/** A slot, in the reader's own timezone, said in full so there is nothing to misread. */
export function longWhen(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return date.toLocaleString(undefined, {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    hour: '2-digit',
    minute: '2-digit',
  })
}
