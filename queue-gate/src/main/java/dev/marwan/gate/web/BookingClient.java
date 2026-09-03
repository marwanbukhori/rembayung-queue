package dev.marwan.gate.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class BookingClient {

    private final RestClient client;

    public BookingClient(@Value("${booking-service.base-url}") String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** Relays the downstream status and body verbatim, including errors. */
    public ResponseEntity<String> createBooking(String body) {
        return client.post()
                .uri("/bookings")
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .onStatus(status -> true, (req, res) -> { })   // never throw; relay instead
                .toEntity(String.class);
    }

    public ResponseEntity<String> confirmDeposit(long bookingId) {
        return client.post()
                .uri("/bookings/{id}/deposit", bookingId)
                .retrieve()
                .onStatus(status -> true, (req, res) -> { })
                .toEntity(String.class);
    }
}
