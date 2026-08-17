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

    /**
     * A subject's two assigned peers.
     *
     * <p><strong>Only ever called behind an {@code ASSIGN_PEERS} decision</strong>, which is
     * {@code mgr(S)} alone (P-1.3). Handed to the subject, this list is precisely the
     * authorship P-3.3 exists to withhold - and it would be an easy method to reuse, which is
     * why the constraint is written here rather than assumed at the call site.
     */
    java.util.List<PeerAssignment> findByCycleIdAndSubjectId(Long cycleId, Long subjectId);

    /**
     * What this caller has been asked to write, this cycle.
     *
     * <p>Keyed on the peer, so it only ever returns the caller's own workload. This is the one
     * direction of the assignment table that is safe to expose to the person named in it: you
     * may know whom you must review, never who must review you.
     */
    java.util.List<PeerAssignment> findByCycleIdAndPeerId(Long cycleId, Long peerId);

    void deleteByCycleIdAndSubjectId(Long cycleId, Long subjectId);
}
