import type { ReactNode } from 'react'
import { isForbidden, writeMessage } from '../api/errors'

/**
 * Form furniture, and the one place a failed write is rendered.
 *
 * `WriteFailure` is where the 403/409/400 distinction becomes visible. A 409 renders here, on
 * the form, saying what the record's state is - because the caller holds the permission and
 * telling them otherwise would be false. A 403 on a *write* is rarer and means the control
 * should not have been offered, so it says so plainly rather than pretending the value was
 * wrong.
 */

export function Field({
  label,
  hint,
  children,
}: {
  label: string
  hint?: string
  children: ReactNode
}) {
  return (
    // A column with the control pushed to the bottom, so that fields sitting side by side line
    // their inputs up whether or not they carry a hint. Without `mt-auto` a hinted field is a
    // line taller than its neighbours and its input sits below theirs, which is what the cycle
    // configuration row looked like.
    //
    // The hint stays *above* the control on purpose. Several of them warn that the write cannot
    // be undone - a PIP deadline, a peer review, a calibration note - and that has to be read
    // before the box is filled in, not underneath it afterwards.
    <label className="flex flex-col">
      <span className="mb-1 block text-sm font-medium">{label}</span>
      {hint && <span className="mb-1 block text-xs text-muted">{hint}</span>}
      <div className="mt-auto">{children}</div>
    </label>
  )
}

const inputStyle =
  'w-full rounded border border-line bg-white px-3 py-2 text-sm outline-none focus:border-accent'

export function TextArea(props: React.TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea rows={4} {...props} className={inputStyle} />
}

export function TextInput(props: React.InputHTMLAttributes<HTMLInputElement>) {
  return <input {...props} className={inputStyle} />
}

export function Select(props: React.SelectHTMLAttributes<HTMLSelectElement>) {
  return <select {...props} className={inputStyle} />
}

/**
 * A button that says what it is doing.
 *
 * `busy` is the in-flight state, and it is a separate prop from `disabled` because they mean
 * different things to the person clicking. Disabled means "not available"; busy means "your
 * click landed and the server has not answered yet". Rendering the second as the first is how a
 * request that takes a second reads as a button that did nothing, and gets clicked again.
 *
 * `busyLabel` names the act in progress - "Deactivating" rather than "Deactivate" - so the
 * change is legible without watching the spinner. `aria-busy` carries the same fact to a screen
 * reader, which cannot see it at all.
 */
export function Button({
  variant = 'plain',
  busy = false,
  busyLabel,
  disabled,
  children,
  ...props
}: React.ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'plain' | 'danger'
  busy?: boolean
  busyLabel?: string
}) {
  const style =
    variant === 'primary'
      ? // Brand amber with the brand black on it, which is about 11:1. The tempting
        // `bg-brand text-white` is around 1.9:1 and is why --color-brand is documented as a
        // fill that only ever carries --color-ink.
        'bg-brand font-medium text-ink'
      : variant === 'danger'
        ? 'border border-warn/50 text-warn'
        : 'border border-line bg-white'
  return (
    <button
      type="button"
      {...props}
      // Busy implies disabled: the click has been accepted and a second one would either
      // duplicate the write or race it.
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      className={`inline-flex items-center gap-1.5 rounded px-3 py-1.5 text-sm disabled:opacity-40 ${style}`}
    >
      {busy && <Spinner />}
      {busy && busyLabel ? busyLabel : children}
    </button>
  )
}

function Spinner() {
  return (
    <svg className="h-3.5 w-3.5 animate-spin" viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <circle cx="8" cy="8" r="6" stroke="currentColor" strokeOpacity="0.25" strokeWidth="2" />
      <path d="M14 8a6 6 0 0 0-6-6" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
    </svg>
  )
}

/** Renders whatever a mutation failed with, in the register the failure actually was. */
export function WriteFailure({ error }: { error: unknown }) {
  if (!error) {
    return null
  }
  const message = writeMessage(error)
  return (
    <p
      role="alert"
      className={`text-sm ${isForbidden(error) ? 'text-warn' : 'text-warn'}`}
    >
      {message}
    </p>
  )
}

export function Card({ title, children }: { title?: string; children: ReactNode }) {
  return (
    <section className="rounded border border-line bg-white p-5">
      {title && <h2 className="mb-3 font-semibold">{title}</h2>}
      {children}
    </section>
  )
}

export function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="text-sm">
      <span className="text-muted">{label}: </span>
      {children}
    </div>
  )
}

/** Dates and timestamps, formatted in one place so they read the same everywhere. */
export function when(value: string | null | undefined): string {
  if (!value) {
    return '-'
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleDateString()
}
