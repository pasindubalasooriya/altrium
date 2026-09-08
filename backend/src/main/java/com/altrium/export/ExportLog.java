package com.altrium.export;

import com.altrium.auth.Grounds;
import com.altrium.org.AppUser;
import com.altrium.review.ReviewCycle;
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
 * One report taken out of the system.
 *
 * <p>Append-only, and the entity exposes no setters for the same reason
 * {@code RatingCalibration} does not: a log that can be edited answers a different question
 * from the one it was built for.
 *
 * <p>It records what was taken, never what was in it. Storing the rows would double the
 * exposure this table exists to make visible.
 */
@Entity
@Table(name = "export_log")
public class ExportLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exported_by", nullable = false)
    private AppUser exportedBy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cycle_id", nullable = false)
    private ReviewCycle cycle;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", nullable = false, length = 8)
    private ExportFormat format;

    /**
     * The ground the decision was made on, not the caller's roles.
     *
     * <p>Somebody may hold HR and Leadership at once, and the two produce different files. What
     * matters afterwards is which one they actually got.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "grounds", nullable = false, length = 32)
    private Grounds grounds;

    @Column(name = "scope_note", nullable = false, length = 512)
    private String scopeNote;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected ExportLog() {
    }

    public ExportLog(AppUser exportedBy, ReviewCycle cycle, ExportFormat format,
                     Grounds grounds, String scopeNote, int rowCount) {
        this.exportedBy = exportedBy;
        this.cycle = cycle;
        this.format = format;
        this.grounds = grounds;
        this.scopeNote = scopeNote;
        this.rowCount = rowCount;
    }

    public Long getId() {
        return id;
    }

    public AppUser getExportedBy() {
        return exportedBy;
    }

    public ReviewCycle getCycle() {
        return cycle;
    }

    public ExportFormat getFormat() {
        return format;
    }

    public Grounds getGrounds() {
        return grounds;
    }

    public String getScopeNote() {
        return scopeNote;
    }

    public int getRowCount() {
        return rowCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
