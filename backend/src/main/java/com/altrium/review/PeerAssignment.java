package com.altrium.review;

import com.altrium.org.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One of the two peers {@code mgr(S)} nominated for a subject this cycle (P-3.6).
 *
 * <p>This table is also the authorization fact behind {@code ASSIGNED_PEER}: it is what the
 * peer-review feature reads to populate {@link com.altrium.auth.ArtifactState}, so that
 * "only the two assigned peers may write" (P-3.4) is a decision the central service makes
 * rather than a check the endpoint performs.
 *
 * <p>Exactly-two is enforced in the service. A unique constraint can forbid a duplicate
 * pairing but cannot require a count, and a database trigger would put the rule somewhere
 * {@code AuthorizationService} could not see it.
 */
@Entity
@Table(name = "peer_assignment")
public class PeerAssignment {

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by")
    private AppUser assignedBy;

    @Column(name = "assigned_at", insertable = false, updatable = false)
    private Instant assignedAt;

    protected PeerAssignment() {
        // for JPA
    }

    public PeerAssignment(ReviewCycle cycle, AppUser subject, AppUser peer, AppUser assignedBy) {
        this.cycle = cycle;
        this.subject = subject;
        this.peer = peer;
        this.assignedBy = assignedBy;
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

    public AppUser getAssignedBy() {
        return assignedBy;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }
}
