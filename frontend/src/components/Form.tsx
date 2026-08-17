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
    <label className="block">
      <span className="mb-1 block text-sm font-medium">{label}</span>
      {hint && <span className="mb-1 block text-xs text-muted">{hint}</span>}
      {children}
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

export function Button({
  variant = 'plain',
  ...props
}: React.ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'plain' | 'danger' }) {
  const style =
    variant === 'primary'
      ? 'bg-accent text-white'
      : variant === 'danger'
        ? 'border border-warn/50 text-warn'
        : 'border border-line bg-white'
  return (
    <button
      type="button"
      {...props}
      className={`rounded px-3 py-1.5 text-sm disabled:opacity-40 ${style}`}
    />
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
