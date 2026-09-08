package com.altrium.reminder;

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
import java.time.LocalDate;

/**
 * One reminder that was sent. Append-only, and no setters, like the export log.
 *
 * <p>It exists to stop a second send rather than to be read by the application. The unique key
 * on {@code (item_type, item_id, recipient_id, sent_on)} is what does the work: two sweeps
 * running at once would both pass an application-level check and both send, and only the
 * database can refuse the second.
 */
@Entity
@Table(name = "reminder_log")
public class ReminderLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 24)
    private ReminderItemType itemType;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private AppUser recipient;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "sent_on", nullable = false)
    private LocalDate sentOn;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected ReminderLog() {
    }

    public ReminderLog(ReminderItemType itemType, Long itemId, AppUser recipient,
                       LocalDate dueDate, LocalDate sentOn) {
        this.itemType = itemType;
        this.itemId = itemId;
        this.recipient = recipient;
        this.dueDate = dueDate;
        this.sentOn = sentOn;
    }

    public Long getId() {
        return id;
    }

    public ReminderItemType getItemType() {
        return itemType;
    }

    public Long getItemId() {
        return itemId;
    }

    public AppUser getRecipient() {
        return recipient;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public LocalDate getSentOn() {
        return sentOn;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
