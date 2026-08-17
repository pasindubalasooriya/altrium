import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { Pager } from './Pager'

/**
 * The pager asks the server for the next page and is given rows to display, never an array to
 * cut up.
 *
 * `totalElements` is scoped in the backend's SQL, so it counts what the caller may see rather
 * than what exists. That stays true only while the client shows it unchanged: a frontend that
 * fetched everything and sliced would print a number that no longer described the rows behind
 * it, and would do so without any forbidden row appearing on screen.
 */
describe('Pager', () => {
  it('asks the caller to move page rather than slicing anything itself', async () => {
    const onPage = vi.fn()
    render(<Pager page={0} totalPages={3} totalElements={62} onPage={onPage} />)

    await userEvent.click(screen.getByRole('button', { name: /next/i }))

    expect(onPage).toHaveBeenCalledWith(1)
  })

  it('reports the totals it was given, verbatim', () => {
    render(<Pager page={1} totalPages={3} totalElements={62} onPage={vi.fn()} />)

    expect(screen.getByText(/Page 2 of 3/)).toBeInTheDocument()
    expect(screen.getByText(/62 total/)).toBeInTheDocument()
  })

  it('cannot page before the first page or past the last', () => {
    const { rerender } = render(<Pager page={0} totalPages={3} totalElements={62} onPage={vi.fn()} />)
    expect(screen.getByRole('button', { name: /previous/i })).toBeDisabled()

    rerender(<Pager page={2} totalPages={3} totalElements={62} onPage={vi.fn()} />)
    expect(screen.getByRole('button', { name: /next/i })).toBeDisabled()
  })

  it('shows a count and no controls when everything fits on one page', () => {
    render(<Pager page={0} totalPages={1} totalElements={4} onPage={vi.fn()} />)

    expect(screen.getByText('4 shown')).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })
})
