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
 * The employee's own account of the quadrimester (P-3.1). Written by the subject alone;
 * read by the subject, {@code mgr(S)} and HR-in-scope.
 *
 * <p>There is no rating field, and its absence is deliberate rather than an omission. The
 * scenario never has an employee rate themselves, and a nullable rating column is an
 * invitation — some later screen would populate it, and then a self-assigned number would
 * be sitting next to a manager-assigned one with nothing explaining the difference.
 */
@Entity
@Table(name = "self_review")
public class SelfReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cycle_id", nullable = false)
    private ReviewCycle cycle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false)
    private AppUser subject;

    @Column(name = "achievements", columnDefinition = "TEXT")
    private String achievements;

    @Column(name = "challenges", columnDefinition = "TEXT")
    private String challenges;

    @Column(name = "goals", columnDefinition = "TEXT")
    private String goals;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    protected SelfReview() {
        // for JPA
    }

    public SelfReview(ReviewCycle cycle, AppUser subject) {
        this.cycle = cycle;
        this.subject = subject;
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

    public String getAchievements() {
        return achievements;
    }

    public void setAchievements(String achievements) {
        this.achievements = achievements;
    }

    public String getChallenges() {
        return challenges;
    }

    public void setChallenges(String challenges) {
        this.challenges = challenges;
    }

    public String getGoals() {
        return goals;
    }

    public void setGoals(String goals) {
        this.goals = goals;
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
