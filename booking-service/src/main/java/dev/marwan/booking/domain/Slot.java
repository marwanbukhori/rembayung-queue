package dev.marwan.booking.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "slots")
public class Slot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Column(name = "service_time", nullable = false, length = 5)
    private String serviceTime;

    @Column(nullable = false)
    private int capacity;

    @Column(name = "seats_taken", nullable = false)
    private int seatsTaken;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "sandbox_expires_at")
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    private Instant sandboxExpiresAt;

    protected Slot() { }

    public Slot(LocalDate serviceDate, String serviceTime, int capacity) {
        this.serviceDate = serviceDate;
        this.serviceTime = serviceTime;
        this.capacity = capacity;
        this.seatsTaken = 0;
    }

    public int remainingSeats() {
        return capacity - seatsTaken;
    }

    public boolean canAccommodate(int partySize) {
        return remainingSeats() >= partySize;
    }

    public void takeSeats(int partySize) {
        if (partySize <= 0) {
            throw new IllegalArgumentException("partySize must be positive but was " + partySize);
        }
        if (!canAccommodate(partySize)) {
            throw new IllegalStateException(
                    "Cannot take " + partySize + " seats; only " + remainingSeats() + " remain");
        }
        this.seatsTaken += partySize;
    }

    public void releaseSeats(int partySize) {
        if (partySize <= 0) {
            throw new IllegalArgumentException("partySize must be positive but was " + partySize);
        }
        if (partySize > seatsTaken) {
            throw new IllegalStateException("Cannot release more seats than are taken");
        }
        this.seatsTaken -= partySize;
    }

    public Instant getSandboxExpiresAt() { return sandboxExpiresAt; }

    public void expireAsSandboxAt(Instant when) { this.sandboxExpiresAt = when; }

    public Long getId() { return id; }
    public LocalDate getServiceDate() { return serviceDate; }
    public String getServiceTime() { return serviceTime; }
    public int getCapacity() { return capacity; }
    public int getSeatsTaken() { return seatsTaken; }
}
