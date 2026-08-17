package com.altrium.review;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PeerAssignmentRepository
        extends JpaRepository<PeerAssignment, Long>, JpaSpecificationExecutor<PeerAssignment> {

    /**
     * Answers "is this caller an assigned peer of this subject?" - the fact behind
     * {@code ASSIGNED_PEER} (P-3.4).
     *
     * <p>It takes the peer id as an argument rather than listing a subject's peers, so it
     * can confirm a suspicion but never satisfy a curiosity: it cannot be turned into a way
     * to discover who is reviewing whom.
     */
    boolean existsByCycleIdAndSubjectIdAndPeerId(Long cycleId, Long subjectId, Long peerId);

    long countByCycleIdAndSubjectId(Long cycleId, Long subjectId);
}
