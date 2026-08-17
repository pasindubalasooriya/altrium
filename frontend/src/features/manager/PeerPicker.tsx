import { useState } from 'react'
import { useAssignPeers, useAssignedPeers, usePeerCandidates } from '../../api/reviews'
import { Button, Card, TextInput, WriteFailure } from '../../components/Form'

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
 */
export function PeerPicker({ subjectId, cycleId }: { subjectId: number; cycleId: number }) {
  const [search, setSearch] = useState('')
  const [selected, setSelected] = useState<{ id: number; name: string }[]>([])

  const assigned = useAssignedPeers(subjectId, cycleId)
  const candidates = usePeerCandidates(subjectId, search)
  const assign = useAssignPeers(subjectId, cycleId)

  const toggle = (id: number, name: string) =>
    setSelected((current) =>
      current.some((peer) => peer.id === id)
        ? current.filter((peer) => peer.id !== id)
        : current.length >= 2
          ? current
          : [...current, { id, name }],
    )

  return (
    <Card title="Peer reviewers">
      {assigned.data?.length ? (
        <p className="mb-4 text-sm">
          Currently assigned: {assigned.data.map((peer) => peer.peerName).join(' and ')}.
          <span className="block text-xs text-muted">
            The person being reviewed never sees these names.
          </span>
        </p>
      ) : (
        <p className="mb-4 text-sm text-muted">No peers assigned yet. Two are required.</p>
      )}

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
                    onClick={() => toggle(person.id, person.fullName)}
                    className={`w-full rounded border px-3 py-2 text-left text-sm ${
                      picked ? 'border-accent bg-accent/5' : 'border-line'
                    }`}
                  >
                    {person.fullName}
                    <span className="ml-2 text-xs text-muted">
                      {person.departmentName ?? 'no department'}
                    </span>
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

        <WriteFailure error={assign.error} />

        <div className="flex items-center gap-3">
          <Button
            variant="primary"
            disabled={selected.length !== 2 || assign.isPending}
            onClick={() => assign.mutate(selected.map((peer) => peer.id))}
          >
            Assign {selected.map((peer) => peer.name).join(' and ') || 'two peers'}
          </Button>
          {selected.length > 0 && <Button onClick={() => setSelected([])}>Clear</Button>}
        </div>
      </div>
    </Card>
  )
}
