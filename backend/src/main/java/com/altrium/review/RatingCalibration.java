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
 * One HR adjustment to a final rating (P-4.3). Append-only: rows are inserted, never updated
 * or deleted, and the entity exposes no setters for that reason.
 *
 * <p>{@code ratingBefore} is what makes calibration visible at all. Without it, an adjusted
 * rating is indistinguishable from a manager who simply chose differently, and the
 * normalisation the client asked for becomes untraceable - which is the opposite of the
 * accountability it exists to provide.
 */
@Entity
@Table(name = "rating_calibration")
public class RatingCalibration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "final_rating_id", nullable = false)
    private FinalRating finalRating;

    @Enumerated(EnumType.STRING)
    @Column(name = "rating_before", nullable = false, length = 32)
    private Rating ratingBefore;

    @Enumerated(EnumType.STRING)
    @Column(name = "rating_after", nullable = false, length = 32)
    private Rating ratingAfter;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "calibrated_by", nullable = false)
    private AppUser calibratedBy;

    @Column(name = "calibrated_at", insertable = false, updatable = false)
    private Instant calibratedAt;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    protected RatingCalibration() {
        // for JPA
    }

    public RatingCalibration(FinalRating finalRating, Rating ratingBefore, Rating ratingAfter,
                             AppUser calibratedBy, String note) {
        this.finalRating = finalRating;
        this.ratingBefore = ratingBefore;
        this.ratingAfter = ratingAfter;
        this.calibratedBy = calibratedBy;
        this.note = note;
    }

    public Long getId() {
        return id;
    }

    public FinalRating getFinalRating() {
        return finalRating;
    }

    public Rating getRatingBefore() {
        return ratingBefore;
    }

    public Rating getRatingAfter() {
        return ratingAfter;
    }

    public AppUser getCalibratedBy() {
        return calibratedBy;
    }

    public Instant getCalibratedAt() {
        return calibratedAt;
    }

    public String getNote() {
        return note;
    }
}
