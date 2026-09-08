package com.altrium.calendar;

import com.altrium.org.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One person's consent for Altrium to use their Google Calendar.
 *
 * <p>The entity exposes no getter for the stored ciphertext beyond what
 * {@link GoogleConnectionService} needs, and the refresh token never leaves this package in
 * plaintext. Nothing in a controller, a DTO or a log has a route to it.
 */
@Entity
@Table(name = "google_connection")
public class GoogleConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private AppUser user;

    @Column(name = "google_email", nullable = false, length = 320)
    private String googleEmail;

    @Column(name = "refresh_token_cipher", nullable = false, length = 1024)
    private byte[] refreshTokenCipher;

    @Column(name = "granted_scopes", nullable = false, length = 512)
    private String grantedScopes;

    @Column(name = "connected_at", insertable = false, updatable = false)
    private Instant connectedAt;

    protected GoogleConnection() {
    }

    GoogleConnection(AppUser user, String googleEmail, byte[] refreshTokenCipher, String grantedScopes) {
        this.user = user;
        replaceWith(googleEmail, refreshTokenCipher, grantedScopes);
    }

    /**
     * Reconnecting overwrites in place.
     *
     * <p>A second row would leave the first token live and unreferenced, which is a grant the
     * person believes they have replaced and in fact still holds.
     */
    void replaceWith(String googleEmail, byte[] refreshTokenCipher, String grantedScopes) {
        this.googleEmail = googleEmail;
        this.refreshTokenCipher = refreshTokenCipher;
        this.grantedScopes = grantedScopes;
    }

    public Long getId() {
        return id;
    }

    public AppUser getUser() {
        return user;
    }

    public String getGoogleEmail() {
        return googleEmail;
    }

    byte[] getRefreshTokenCipher() {
        return refreshTokenCipher;
    }

    public String getGrantedScopes() {
        return grantedScopes;
    }

    public Instant getConnectedAt() {
        return connectedAt;
    }
}
