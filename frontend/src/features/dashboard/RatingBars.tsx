import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { RATINGS, RATING_LABELS } from '../../api/types'

/**
 * Colours for the three ratings, in one place.
 *
 * Shared so that Needs improvement is the same colour on Leadership's screen as on a
 * manager's. Two definitions would drift, and a rating that changed colour between screens
 * would be read as a different thing.
 */
export const RATING_COLOURS: Record<string, string> = {
  NEEDS_IMPROVEMENT: '#D9A441',
  MEETS_EXPECTATIONS: '#A96F14',
  EXCEEDS_EXPECTATIONS: '#6B4708',
}

/**
 * A rating distribution, drawn the same way wherever it appears.
 *
 * **Built from the scale, not from the keys that arrived.** The server omits a rating nobody
 * was given rather than sending a zero, which is right for a response and wrong for an axis:
 * a chart whose bars changed between cycles would invite comparisons nobody was making. So
 * the three bars are always the same three, and an omitted rating is drawn as zero here,
 * where it means "nobody" rather than "not told".
 *
 * The distinction matters because it does not hold everywhere. On a screen showing one
 * person, an absent value can mean withheld; on an aggregate the caller is entitled to, an
 * absent rating can only mean nobody received it.
 */
export function RatingBars({ distribution, empty }: {
  distribution: Partial<Record<string, number>>
  /** What to say when no rating has been set at all. */
  empty: string
}) {
  const bars = RATINGS.map((rating) => ({
    rating,
    label: RATING_LABELS[rating],
    total: distribution[rating] ?? 0,
  }))
  const rated = bars.reduce((sum, bar) => sum + bar.total, 0)

  if (rated === 0) {
    return <p className="text-sm text-muted">{empty}</p>
  }

  return (
    <div className="h-64 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={bars} barCategoryGap="28%" margin={{ top: 8, right: 8, bottom: 8, left: 0 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="oklch(0.9 0.008 80)" vertical={false} />
          <XAxis dataKey="label" tick={{ fontSize: 12 }} stroke="oklch(0.53 0.015 80)" />
          <YAxis allowDecimals={false} tick={{ fontSize: 12 }} stroke="oklch(0.53 0.015 80)" />
          <Tooltip
            cursor={{ fill: 'oklch(0.95 0.006 80)' }}
            formatter={(value) => [`${Number(value)} people`, 'Rated'] as [string, string]}
          />
          <Bar dataKey="total" radius={[4, 4, 0, 0]}>
            {bars.map((bar) => (
              <Cell key={bar.rating} fill={RATING_COLOURS[bar.rating]} />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}
