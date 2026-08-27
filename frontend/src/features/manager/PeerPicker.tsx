import { useState } from 'react'
import { useAssignPeers, useAssignedPeers, usePeerCandidates } from '../../api/reviews'
import { Button, Card, TextInput, WriteFailure, when } from '../../components/Form'
import type { PeerReview } from '../../api/types'

/**
 * Choosing the two peer reviewers for a subject (P-3.6).
 *
 * **The client does not enforce the rules, and deliberately does not pre-filter.** The server
 * rejects the subject themselves, the subject's manager - who already writes the manager
 * review - and anybody deactivated, and it excludes all three from the candidate query, so the
 * list offered and the set accepted are the same set by construction. If they ever disagreed,
 * this screen would surface the server's 400 rather than quietly hiding the disagreement,
 * which is the behaviour that lets a real drift be noticed.
 *
 * The count is the server's too: "exactly two" is checked there, and the button below is
 * disabled at two only as a courtesy.
 *
 * <h2>Two states, not one</h2>
 *
 * Assigned and unassigned are different screens. An assignment that already exists is a
 * decision the manager made and wants to read back, so it is shown as a settled list rather
 * than as the by-product of a search box left open underneath it. Changing it is then a
 * deliberate act with a control of its own.
 */
export function PeerPicker({
  subjectId,
  cycleId,
  peerReviews,
}: {
  subjectId: number
  cycleId: number
  /** The peer reviews on the record, used only to report progress and to explain the lock. */
  peerReviews: PeerReview[] | null
}) {
  const assigned = useAssignedPeers(subjectId, cycleId)
  const peers = assigned.data ?? []

  // The server's own condition for refusing a reassignment, read from the record rather than
  // guessed: replacing a peer who has already written would strand their feedback where no
  // endpoint could reach it. This only decides what to *show* - the 409 is still what stops
  // the write, and it renders below if a stale tab tries anyway.
  const locked = Boolean(peerReviews?.some((review) => review.submittedAt))

  const [changing, setChanging] = useState(false)
  const open = changing || peers.length === 0

  return (
    <Card title="Peer reviewers">
      {peers.length > 0 && (
        <ul className="mb-4 grid gap-2">
          {peers.map((peer) => {
            const written = peerReviews?.find((review) => review.peerId === peer.peerId)
            return (
              <li
                key={peer.peerId}
                className="flex flex-wrap items-baseline gap-x-3 rounded border border-line px-3 py-2 text-sm"
              >
                <span className="font-medium">{peer.peerName}</span>
                <span className="ml-auto text-xs text-muted">
                  {written?.submittedAt
                    ? `submitted ${when(written.submittedAt)}`
                    : 'not submitted yet'}
                </span>
              </li>
            )
          })}
        </ul>
      )}

      {peers.length === 0 && (
        <p className="mb-4 text-sm text-muted">No peers assigned yet. Two are required.</p>
      )}

      {peers.length > 0 && !open && (
        <div className="flex flex-wrap items-center gap-3">
          {locked ? (
            <p className="text-sm text-muted">
              Peer feedback has been submitted, so the reviewers are now fixed for this cycle.
            </p>
          ) : (
            <Button onClick={() => setChanging(true)}>Change reviewers</Button>
          )}
        </div>
      )}

      {open && (
        <Picker
          subjectId={subjectId}
          cycleId={cycleId}
          current={peers.map((peer) => ({ id: peer.peerId, name: peer.peerName }))}
          onDone={() => setChanging(false)}
          onCancel={changing ? () => setChanging(false) : undefined}
        />
      )}
    </Card>
  )
}

type Picked = { id: number; name: string }

/**
 * The search-and-choose form.
 *
 * Opened for a change, it starts from the people already assigned. Swapping one reviewer is
 * the ordinary case, and making the manager rebuild a pair they had already decided on invites
 * them to get the other half wrong.
 */
function Picker({
  subjectId,
  cycleId,
  current,
  onDone,
  onCancel,
}: {
  subjectId: number
  cycleId: number
  current: Picked[]
  onDone: () => void
  onCancel?: () => void
}) {
  const [search, setSearch] = useState('')
  const [selected, setSelected] = useState<Picked[]>(current)

  const candidates = usePeerCandidates(subjectId, search)
  const assign = useAssignPeers(subjectId, cycleId)

  const toggle = (id: number, name: string) =>
    setSelected((chosen) =>
      chosen.some((peer) => peer.id === id)
        ? chosen.filter((peer) => peer.id !== id)
        : chosen.length >= 2
          ? chosen
          : [...chosen, { id, name }],
    )

  const unchanged =
    selected.length === current.length &&
    selected.every((peer) => current.some((was) => was.id === peer.id))

  return (
    <div className="grid gap-3">
      <TextInput
        value={search}
        placeholder="Search colleagues by name"
        onChange={(e) => setSearch(e.target.value)}
      />

      {candidates.data && (
        <ul className="grid gap-1">
          {candidates.data.content.map((person) => {
            const picked = selected.some((peer) => peer.id === person.id)
            return (
              <li key={person.id}>
                <button
                  type="button"
                  aria-pressed={picked}
                  onClick={() => toggle(person.id, person.fullName)}
                  className={`flex w-full cursor-pointer flex-wrap items-baseline gap-x-2 rounded border px-3 py-2 text-left text-sm transition-colors ${
                    picked ? 'border-accent bg-accent/5' : 'border-line hover:border-ink/25 hover:bg-line/30'
                  }`}
                >
                  <span>{person.fullName}</span>
                  <span className="text-xs text-muted">
                    {person.departmentName ?? 'no department'}
                  </span>
                  {picked && <span className="ml-auto text-xs text-accent">chosen</span>}
                </button>
              </li>
            )
          })}
          {candidates.data.content.length === 0 && (
            <li className="text-sm text-muted">Nobody matches that name.</li>
          )}
          {candidates.data.totalElements > candidates.data.content.length && (
            <li className="text-xs text-muted">
              {candidates.data.totalElements} people match. Narrow the search to see the rest -
              peers can come from any department.
            </li>
          )}
        </ul>
      )}

      {/*
        The choice, said once and in one place. It used to live in the button's label, which
        grew and shrank as people were picked and moved the control out from under the pointer.
      */}
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <span className="text-muted">Chosen:</span>
        {selected.length === 0 && <span className="text-muted">nobody yet</span>}
        {selected.map((peer) => (
          <button
            key={peer.id}
            type="button"
            onClick={() => toggle(peer.id, peer.name)}
            className="cursor-pointer rounded-full border border-accent bg-accent/5 px-3 py-1 text-xs transition-colors hover:bg-accent/10"
            aria-label={`Remove ${peer.name}`}
          >
            {peer.name} <span aria-hidden="true">&times;</span>
          </button>
        ))}
      </div>

      {/* A 409 lands here when a peer submitted between this page loading and the save. */}
      <WriteFailure error={assign.error} />

      <div className="flex flex-wrap items-center gap-3">
        <Button
          variant="primary"
          disabled={selected.length !== 2 || unchanged}
          busy={assign.isPending}
          busyLabel="Saving"
          onClick={() =>
            assign.mutate(
              selected.map((peer) => peer.id),
              { onSuccess: onDone },
            )
          }
        >
          {current.length > 0 ? 'Save reviewers' : 'Assign reviewers'}
        </Button>
        {onCancel && (
          <Button
            onClick={() => {
              setSelected(current)
              onCancel()
            }}
          >
            Cancel
          </Button>
        )}
        {selected.length !== 2 && (
          <span className="text-xs text-muted">Two are required.</span>
        )}
      </div>
    </div>
  )
}
