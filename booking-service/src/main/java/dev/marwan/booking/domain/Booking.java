package dev.marwan.booking.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "slot_id", nullable = false)
    private Long slotId;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(name = "party_size", nullable = false)
    private int partySize;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingStatus status;

    @Column(name = "deposit_cents", nullable = false)
    private long depositCents;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected Booking() { }

    public Booking(Long slotId, String phone, int partySize, long depositCents,
                   String idempotencyKey, Instant createdAt, Instant expiresAt) {
        this.slotId = slotId;
        this.phone = phone;
        this.partySize = partySize;
        this.status = BookingStatus.PENDING_DEPOSIT;
        this.depositCents = depositCents;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public Long getSlotId() { return slotId; }
    public String getPhone() { return phone; }
    public int getPartySize() { return partySize; }
    public BookingStatus getStatus() { return status; }
    public void setStatus(BookingStatus status) { this.status = status; }
    public long getDepositCents() { return depositCents; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getExpiresAt() { return expiresAt; }
}
