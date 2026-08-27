package com.altrium.review;

/**
 * The parts of a review record, named so a caller can be told which of them they had
 * grounds for.
 *
 * <p>This exists because "absent" is ambiguous on its own. A record arriving without a peer
 * section could mean the caller may not see peer feedback, or that no peer has written yet,
 * and those are different sentences to put in front of somebody. The server is the only party
 * that knows which, so it says, and the client renders rather than infers.
 *
 * <p>Naming a section the caller <em>may</em> see discloses nothing about the record: it is a
 * statement about the caller's own grounds, which they are entitled to know. Naming one they
 * may not see is equally safe for the same reason. What would leak is the content, or a count
 * of it, and neither travels with this.
 */
public enum ReviewSection {
    SELF_REVIEW,
    MANAGER_REVIEW,
    PEER_REVIEWS,
    FINAL_RATING
}
