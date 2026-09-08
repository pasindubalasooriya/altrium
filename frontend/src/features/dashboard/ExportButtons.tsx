import { useMutation } from '@tanstack/react-query'
import { api } from '../../api/client'
import { Button, WriteFailure } from '../../components/Form'

/**
 * Taking the report on screen out of the system (P-8.1).
 *
 * Placed beside the report it exports rather than on a page of its own, so what the file will
 * contain is whatever the person is looking at. An "Exports" screen with a cycle picker would
 * be a second place deciding what a report covers.
 *
 * **Not rendered for a manager**, who is refused by the server. That is cosmetic, as every
 * conditional in this app is: the endpoint returns 403 to a manager who calls it directly, and
 * hiding a button is not what stops them.
 */
export function ExportButtons({ cycleId }: { cycleId: number }) {
  const download = useMutation({
    mutationFn: async (format: 'xlsx' | 'pdf') => {
      const { blob, filename } = await api.download(`/api/exports/cycles/${cycleId}`, { format })

      // The browser has no other way to save bytes that arrived over an authenticated fetch.
      // Revoked immediately after the click, because an object URL keeps the whole blob in
      // memory until it is released and a person exporting all afternoon would accumulate them.
      const href = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = href
      link.download = filename
      link.click()
      URL.revokeObjectURL(href)
    },
  })

  return (
    <div className="flex flex-wrap items-center gap-2">
      <Button
        type="button"
        onClick={() => download.mutate('xlsx')}
        busy={download.isPending}
        busyLabel="Preparing"
      >
        Export spreadsheet
      </Button>
      <Button
        type="button"
        onClick={() => download.mutate('pdf')}
        busy={download.isPending}
        busyLabel="Preparing"
      >
        Export PDF
      </Button>
      <p className="text-xs text-muted">
        Covers the same people as this page. Downloads are recorded.
      </p>
      <WriteFailure error={download.error} />
    </div>
  )
}
