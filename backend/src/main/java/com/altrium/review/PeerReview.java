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
 * A peer's feedback on a colleague (P-3.2 to P-3.5).
 *
 * <p><strong>Authorship is stored, in full, under the peer's name.</strong> Anonymity here
 * is a rule about who may read the row, not about what the row contains (P-3.3): the
 * manager and HR-in-scope see exactly who wrote what, and only the subject never does.
 * Storing feedback anonymously would trade away all accountability — nobody could challenge
 * a malicious review, or notice the same person writing every unkind one — to buy a
 * confidentiality the read layer already provides.
 *
 * <p>Once {@code submittedAt} is set the row is final (P-3.5), backed by the unique
 * constraint on {@code (cycle, subject, peer)}. A second attempt is a 409, not a 403: the
 * peer has permission, and it is the record that forbids the write.
 */
@Entity
@Table(name = "peer_review")
public class PeerReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cycle_id", nullable = false)
    private ReviewCycle cycle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false)
    private AppUser subject;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "peer_id", nullable = false)
    private AppUser peer;

    @Column(name = "feedback", columnDefinition = "TEXT")
    private String feedback;

    /**
     * Input to the manager's decision (P-4.2), never an ingredient in a calculation. No
     * aggregate of this column is stored or derived on read — an average shown beside the
     * manager's choice becomes a suggestion, and then a default (P-4.1).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "rating", length = 32)
    private Rating rating;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    protected PeerReview() {
        // for JPA
    }

    public PeerReview(ReviewCycle cycle, AppUser subject, AppUser peer) {
        this.cycle = cycle;
        this.subject = subject;
        this.peer = peer;
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

    public AppUser getPeer() {
        return peer;
    }

    public String getFeedback() {
        return feedback;
    }

    public void setFeedback(String feedback) {
        this.feedback = feedback;
    }

    public Rating getRating() {
        return rating;
    }

    public void setRating(Rating rating) {
        this.rating = rating;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    public boolean isSubmitted() {
        return submittedAt != null;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
