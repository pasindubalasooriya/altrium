import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ManagerPicker } from './ManagerPicker'
import { installTokenProvider } from '../../api/client'

/**
 * The reporting-line picker.
 *
 * **This is not a test of who may be a manager.** Which people are eligible is decided in the
 * query and proved by `ManagerCandidateTest`, calling the endpoint directly; a component test
 * that fed the picker a list and watched it render one would prove only that the mock was
 * consistent with itself.
 *
 * What is proved here is the part that is genuinely the client's: that the picker asks the
 * server the scoped question rather than for the whole directory, and that "Nobody" is a real
 * choice, since detaching is how the top of the chain is expressed and there is no other way
 * to say it now the free-text box has gone.
 */
describe('ManagerPicker', () => {
  const fetchMock = vi.fn()

  const page = {
    content: [
      {
        id: 4,
        email: 'elena@altrium.test',
        fullName: 'Elena Vasquez',
        departmentId: 1,
        departmentName: 'Engineering',
        managerId: 2,
        managerName: 'Priya Raman',
        active: true,
        roles: ['EMPLOYEE', 'MANAGER'],
      },
    ],
    page: 0,
    size: 10,
    totalElements: 1,
    totalPages: 1,
  }

  function renderPicker(onChange: (id: number | null, name: string | null) => void) {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    return render(
      <QueryClientProvider client={client}>
        <ManagerPicker userId={5} value={2} valueName="Priya Raman" onChange={onChange} />
      </QueryClientProvider>,
    )
  }

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock)
    installTokenProvider(async () => 'stub-token')
    fetchMock.mockImplementation(
      async () =>
        new Response(JSON.stringify(page), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
    )
  })

  afterEach(() => {
    fetchMock.mockReset()
    vi.unstubAllGlobals()
  })

  /**
   * The failure this catches is a quiet one: without the parameter the request is still a 200
   * and still renders a list of people, so the picker looks like it works while offering
   * everybody, the person themselves and their own reports included.
   */
  it('asks for candidates for this person, not for the whole directory', async () => {
    renderPicker(vi.fn())

    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    const url = String(fetchMock.mock.calls[0][0])
    expect(url).toContain('managerCandidateFor=5')
  })

  it('choosing a person reports their id and name', async () => {
    const onChange = vi.fn()
    renderPicker(onChange)

    await userEvent.click(await screen.findByRole('button', { name: /Elena Vasquez/ }))
    expect(onChange).toHaveBeenCalledWith(4, 'Elena Vasquez')
  })

  it('"Nobody" detaches, which is how the top of the chain is set', async () => {
    const onChange = vi.fn()
    renderPicker(onChange)

    await userEvent.click(await screen.findByRole('button', { name: /Nobody/ }))
    expect(onChange).toHaveBeenCalledWith(null, null)
  })
})
