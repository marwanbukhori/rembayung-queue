package dev.marwan.console.chaos;

/**
 * booking-service's in-app faults, addressed to one pod at a time.
 *
 * Through the Service a request reaches one pod of two or more, so a drill
 * would hit half the capacity and an early end would miss half the time. The
 * console therefore speaks to every booking-service pod by its IP.
 */
public interface BookingChaos {

    void start(String podIp, String fault, int seconds);

    void stop(String podIp);
}
