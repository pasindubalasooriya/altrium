package com.altrium.review;

/**
 * The performance scale (P-4.5). Exactly three values, in this order.
 *
 * <p>Deliberately an enum with no numeric weight attached. A rating that can be averaged
 * gets averaged, and P-4.1 requires the final rating to be <em>chosen</em> by the manager,
 * never computed from the peer ratings. Nothing here permits arithmetic.
 *
 * <p>The names must match the check constraints in V3.
 */
public enum Rating {
    NEEDS_IMPROVEMENT,
    MEETS_EXPECTATIONS,
    EXCEEDS_EXPECTATIONS
}
