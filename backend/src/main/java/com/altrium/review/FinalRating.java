package com.altrium.review;

import com.altrium.org.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The rating for a subject in a cycle (P-4.1 to P-4.4).
 *
 * <p>Chosen by {@code mgr(S)}, then possibly adjusted by HR calibration (P-4.3), which
 * appends to {@code rating_calibration} rather than overwriting history. The current value
 * lives here; how it got here lives there.
 *
 * <p>{@code releasedAt} is the gate on the subject's own view (P-4.4). A rating that HR is
 * still normalising across the department is not the subject's to read — and because that
 * is enforced as a state gate in {@code AuthorizationService}, calling the endpoint directly
 * with a known id is refused rather than merely hidden by a screen that does not render it.
 */
@Entity
@Table(name = "final_rating")
public class FinalRating {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cycle_id", nullable = false)
    private ReviewCycle cycle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false)
    private AppUser subject;

    @Enumerated(EnumType.STRING)
    @Column(name = "rating", nullable = false, length = 32)
    private Rating rating;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "set_by", nullable = false)
    private AppUser setBy;

    @Column(name = "set_at", insertable = false, updatable = false)
    private Instant setAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    protected FinalRating() {
        // for JPA
    }

    public FinalRating(ReviewCycle cycle, AppUser subject, Rating rating, AppUser setBy) {
        this.cycle = cycle;
        this.subject = subject;
        this.rating = rating;
        this.setBy = setBy;
    }

    public Long getId() {
        return id;
    }

    public ReviewCycle getCycle() {
        return cycle;
    }

    public AppUser getSubject() {
        return subject;
    }

    public Rating getRating() {
        return rating;
    }

    public void setRating(Rating rating) {
        this.rating = rating;
    }

    public AppUser getSetBy() {
        return setBy;
    }

    public Instant getSetAt() {
        return setAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(Instant releasedAt) {
        this.releasedAt = releasedAt;
    }

    /** Feeds the P-4.4 state gate. */
    public boolean isReleased() {
        return releasedAt != null;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
