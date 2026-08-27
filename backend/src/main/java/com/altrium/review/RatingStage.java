package com.altrium.review;

/**
 * Where a rating has got to, as the two people who have to act on it need to see it.
 *
 * <p>The review cycle hands the rating back and forth: the manager sets it, HR sign it off, the
 * manager shares it. Neither side is told when their turn comes - no email goes out in Sprint 1
 * - so each was waiting on a screen they had no reason to open. This is what the list rows and
 * the navigation read to say "there is something here for you".
 *
 * <p><b>It is not sent to everybody.</b> Whether HR have been through a rating is part of the
 * calibration trail, and {@code READ_RATING_AUDIT} has no {@code SELF} ground (P-4.7), so the
 * subject's own row carries null rather than a value. Null is not a fifth state; it means the
 * caller was not entitled to ask.
 */
public enum RatingStage {

    /** No rating yet. The manager's turn, and nothing for HR to look at. */
    NOT_SET,

    /** Set and waiting for HR to calibrate or approve it (P-4.8). HR's turn. */
    AWAITING_SIGN_OFF,

    /** HR have been through it. The manager's turn again: it can be shared now. */
    SIGNED_OFF,

    /** Shared with the employee. Nobody's turn; the cycle is done for this person. */
    SHARED;

    static RatingStage of(FinalRating rating, boolean signedOff) {
        if (rating == null) {
            return NOT_SET;
        }
        if (rating.isReleased()) {
            return SHARED;
        }
        return signedOff ? SIGNED_OFF : AWAITING_SIGN_OFF;
    }
}
