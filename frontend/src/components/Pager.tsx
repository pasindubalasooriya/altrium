import { useState } from 'react'

/**
 * Server-side paging. There is no client-side alternative in this app.
 *
 * Nothing may be hardcoded to the size of the organisation, and loading every employee into
 * one table does not hold at a hundred people, let alone beyond. But the stronger reason is
 * the one the backend already acted on: every list is scoped inside its SQL `WHERE` clause,
 * so `totalElements` counts what the caller may see rather than what exists. That count is
 * only honest while the client shows it unchanged - the moment the frontend filtered or
 * sliced a fetched array, the number on screen would stop describing the rows behind it.
 *
 * So this component drives `page` and `size` as query parameters and renders whatever came
 * back. It never receives a full array to cut up.
 */
export function usePaging(size = 25) {
  const [page, setPage] = useState(0)
  return { page, size, setPage }
}

export function Pager({
  page,
  totalPages,
  totalElements,
  onPage,
}: {
  page: number
  totalPages: number
  totalElements: number
  onPage: (page: number) => void
}) {
  if (totalPages <= 1) {
    return <p className="p-2 text-sm text-muted">{totalElements} shown</p>
  }
  return (
    <div className="flex items-center gap-3 p-2 text-sm">
      <button
        type="button"
        className="rounded border border-line px-3 py-1 disabled:opacity-40"
        disabled={page <= 0}
        onClick={() => onPage(page - 1)}
      >
        Previous
      </button>
      <span className="text-muted">
        Page {page + 1} of {totalPages} · {totalElements} total
      </span>
      <button
        type="button"
        className="rounded border border-line px-3 py-1 disabled:opacity-40"
        disabled={page >= totalPages - 1}
        onClick={() => onPage(page + 1)}
      >
        Next
      </button>
    </div>
  )
}
