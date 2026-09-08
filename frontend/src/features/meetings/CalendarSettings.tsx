import { useSearchParams } from 'react-router-dom'
import {
  MEETING_LABELS,
  useConnectGoogle,
  useDisconnectGoogle,
  useGoogleStatus,
  useMyMeetings,
} from '../../api/meetings'
import { Button, Card, WriteFailure } from '../../components/Form'
import { EmptyState, Loading, QueryFailure } from '../../components/States'
import { longWhen } from './ScheduleMeeting'

/**
 * Connecting a personal Google account, and the meetings that came of it (scenario section 12).
 *
 * This is where Google sends the browser back to after consent, which is why the outcome
 * arrives in the query string rather than from a mutation: the round trip left the app
 * entirely and came back through a redirect, so there is no promise left to resolve.
 *
 * Everything on this page is about the caller. There is no user id anywhere in it, because
 * none of these endpoints take one - connecting somebody else's Google account is not a
 * permission that exists, and a screen offering to do it would suggest otherwise.
 */
export function CalendarSettings() {
  const [params] = useSearchParams()
  const outcome = params.get('google')

  const status = useGoogleStatus()
  const connect = useConnectGoogle()
  const disconnect = useDisconnectGoogle()
  const meetings = useMyMeetings()

  if (status.isPending) {
    return <Loading />
  }
  if (status.error) {
    return <QueryFailure error={status.error} />
  }

  return (
    <>
      <h1 className="mb-6 text-xl font-semibold tracking-tight">Calendar</h1>

      <div className="grid gap-4">
        {outcome && <Outcome outcome={outcome} />}

        <Card title="Google Calendar">
          {!status.data.configured ? (
            <p className="text-sm text-muted">
              This server holds no Google credentials, so meetings cannot be scheduled from it.
            </p>
          ) : status.data.connected ? (
            <div className="grid gap-3 text-sm">
              <p>
                Connected as <strong>{status.data.googleEmail}</strong>.
              </p>
              <p className="text-muted">
                Altrium can see when you are free and add a meeting to this calendar. It never
                reads what your meetings are, and nobody sees your free times except a person
                already arranging a meeting with you.
              </p>
              <div className="flex flex-wrap items-center gap-2">
                <Button
                  type="button"
                  onClick={() => disconnect.mutate()}
                  busy={disconnect.isPending}
                  busyLabel="Disconnecting"
                >
                  Disconnect
                </Button>
                {/*
                  Said plainly, because the opposite is what people assume. Altrium can forget
                  its own credential; only the account holder can withdraw the consent, and
                  letting them believe otherwise would leave a grant standing that they think
                  they revoked.
                */}
                <p className="text-xs text-muted">
                  This removes Altrium's copy. To withdraw the permission itself, remove Altrium
                  in your Google account settings.
                </p>
              </div>
              <WriteFailure error={disconnect.error} />
            </div>
          ) : (
            <div className="grid gap-3 text-sm">
              <p className="text-muted">
                Altrium has no shared company calendar, so a meeting is created in your own
                Google Calendar and the other person is invited by email. Connect the account
                you want meetings booked from.
              </p>
              <p className="text-muted">
                Connecting also lets a colleague arranging a meeting with you see when you are
                free - the times, never what is in them, and only somebody who could already
                book with you.
              </p>
              <div>
                <Button
                  type="button"
                  onClick={() => connect.mutate()}
                  busy={connect.isPending}
                  busyLabel="Opening Google"
                >
                  Connect Google Calendar
                </Button>
              </div>
              <WriteFailure error={connect.error} />
            </div>
          )}
        </Card>

        <Card title="Upcoming meetings">
          {meetings.isPending && <Loading />}
          {meetings.error && <QueryFailure error={meetings.error} />}
          {meetings.data?.length === 0 && (
            <EmptyState>Nothing booked. Meetings arranged through Altrium appear here.</EmptyState>
          )}
          {meetings.data && meetings.data.length > 0 && (
            <ul className="grid gap-2 text-sm">
              {meetings.data.map((meeting) => (
                <li key={meeting.id} className="rounded border border-line p-3">
                  <p>
                    {MEETING_LABELS[meeting.type]} with {meeting.withName}
                  </p>
                  <p className="text-xs text-muted">{longWhen(meeting.startsAt)}</p>
                  {/*
                    Only the organiser has one: Google issues the link per calendar, so the
                    other party's copy of the event lives at an address of their own.
                  */}
                  {meeting.htmlLink && (
                    <a
                      href={meeting.htmlLink}
                      target="_blank"
                      rel="noreferrer"
                      className="text-xs text-accent underline underline-offset-2"
                    >
                      Open in Google Calendar
                    </a>
                  )}
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>
    </>
  )
}

/**
 * What the redirect said.
 *
 * Three outcomes and no detail beyond them, because the server deliberately puts none in the
 * URL: this address ends up in browser history. "Failed" is genuinely all the person needs -
 * the reason is in the server log, and the action is the same either way.
 */
function Outcome({ outcome }: { outcome: string }) {
  const messages: Record<string, string> = {
    connected: 'Your Google Calendar is connected.',
    declined: 'You did not grant access, so nothing was connected.',
    failed: 'That did not work. Try connecting again.',
  }
  const message = messages[outcome]
  if (!message) {
    return null
  }
  return (
    <p
      role="status"
      className={`rounded border px-3 py-2 text-sm ${
        outcome === 'connected' ? 'border-accent bg-accent/5' : 'border-line bg-line/20 text-muted'
      }`}
    >
      {message}
    </p>
  )
}
