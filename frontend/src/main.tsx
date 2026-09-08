import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AltriumAuthProvider } from './auth/AltriumAuthProvider'
import { RequireAuth } from './auth/RequireAuth'
import { AppRoutes } from './routes'
import { ApiError } from './api/errors'
import './index.css'

/**
 * Retry policy, chosen rather than defaulted.
 *
 * A 403 is a decision, not a hiccup. Retrying it three times delays telling the person what
 * happened and puts three denials in the server's log where one belongs. The same goes for a
 * 409: the record's state will not have changed because we asked again.
 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (attempt, error) => {
        if (error instanceof ApiError && [400, 401, 403, 404, 409].includes(error.status)) {
          return false
        }
        return attempt < 2
      },
      // Almost everything here changes in somebody *else's* session: HR co-sign a plan, HR
      // sign a rating off, a peer submits. Nothing in this browser is mutating, so nothing
      // invalidates the cache, and the tab shows an answer from before the other person acted -
      // an employee left on "you are not on an improvement plan" after HR had co-signed one.
      //
      // Refetching when the window regains focus is the cheapest fix that matches how the app
      // is actually used: two accounts, two windows, switching between them. There is no
      // polling and no push in Sprint 1, so this is the only thing that closes the gap without
      // asking people to reload.
      refetchOnWindowFocus: true,
    },
    mutations: { retry: false },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AltriumAuthProvider>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <RequireAuth>
            <AppRoutes />
          </RequireAuth>
        </BrowserRouter>
      </QueryClientProvider>
    </AltriumAuthProvider>
  </StrictMode>,
)
