import { useState } from 'react'
import { useManagerCandidates } from '../../api/admin'
import { TextInput } from '../../components/Form'
import { QueryFailure } from '../../components/States'

/**
 * Chooses a person's manager.
 *
 * This field used to be a box you typed a numeric id into, and it is the field the whole
 * manager side of the authorization model reads from - `isManagerOf(A,S)` is true only when
 * `S.manager == A`. A mistyped id is not a validation error there; it is a valid assignment to
 * the wrong human being, and the console showed no ids anywhere to check against.
 *
 * Searched and paged on the server rather than a `<select>` of everybody, because nothing may
 * be hardcoded to the size of the organisation. The exclusions are the server's too: the
 * person themselves, everybody beneath them in the chart, anybody deactivated, and the Super
 * Admin. Deliberately the same shape and behaviour as the manager console's `PeerPicker`, so
 * there is one way of choosing a person in this app rather than two.
 */
export function ManagerPicker({
  userId,
  value,
  valueName,
  onChange,
}: {
  /** Whose manager is being chosen. The candidate list is computed relative to this person. */
  userId: number
  value: number | null
  valueName: string | null
  onChange: (managerId: number | null, managerName: string | null) => void
}) {
  const [search, setSearch] = useState('')
  const candidates = useManagerCandidates(userId, search)

  const rowStyle = (chosen: boolean) =>
    `flex w-full cursor-pointer flex-wrap items-baseline gap-x-2 rounded border px-3 py-2 text-left text-sm transition-colors ${
      chosen ? 'border-accent bg-accent/5' : 'border-line hover:border-ink/25 hover:bg-line/30'
    }`

  return (
    <div className="grid gap-2">
      <TextInput
        value={search}
        placeholder="Search people by name"
        onChange={(e) => setSearch(e.target.value)}
      />

      {candidates.error ? (
        <QueryFailure error={candidates.error} />
      ) : (
        candidates.data && (
          <ul className="grid gap-1">
            {/*
              Detaching has to be a choice on the list, not the absence of one. It is how the
              top of the chain is expressed - Leadership report to nobody - so without this row
              the picker would be unable to say something the old text box could say by being
              left empty.
            */}
            <li>
              <button
                type="button"
                aria-pressed={value === null}
                onClick={() => onChange(null, null)}
                className={rowStyle(value === null)}
              >
                <span>Nobody</span>
                <span className="text-xs text-muted">top of the chain</span>
              </button>
            </li>

            {candidates.data.content.map((person) => {
              const chosen = value === person.id
              return (
                <li key={person.id}>
                  <button
                    type="button"
                    aria-pressed={chosen}
                    onClick={() => onChange(person.id, person.fullName)}
                    className={rowStyle(chosen)}
                  >
                    <span>{person.fullName}</span>
                    <span className="text-xs text-muted">
                      {person.departmentName ?? 'no department'}
                    </span>
                    {chosen && <span className="ml-auto text-xs text-accent">chosen</span>}
                  </button>
                </li>
              )
            })}

            {candidates.data.content.length === 0 && (
              <li className="text-sm text-muted">
                Nobody matches that name who could be this person's manager.
              </li>
            )}

            {/*
              What is on screen is one page, and saying so is what keeps the paging honest -
              otherwise ten rows read as the whole organisation and somebody concludes a
              colleague is missing rather than unsearched.
            */}
            {candidates.data.totalElements > candidates.data.content.length && (
              <li className="text-xs text-muted">
                {candidates.data.totalElements} people could take this. Narrow the search to see
                the rest.
              </li>
            )}
          </ul>
        )
      )}

      <div className="text-sm">
        <span className="text-muted">Reports to: </span>
        {value === null ? (
          <span className="text-muted">nobody</span>
        ) : (
          <span>{valueName ?? 'the person currently set'}</span>
        )}
      </div>
    </div>
  )
}
