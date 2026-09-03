package dev.marwan.booking.web;

import dev.marwan.booking.api.BookingRequest;
import dev.marwan.booking.api.BookingResult;
import dev.marwan.booking.service.BookingService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResult create(@RequestBody BookingRequest request) {
        return bookingService.book(request);
    }

    @PostMapping("/{id}/deposit")
    public BookingResult deposit(@PathVariable Long id) {
        return bookingService.confirmDeposit(id);
    }
}
