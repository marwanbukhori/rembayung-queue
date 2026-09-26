package dev.marwan.console.chaos;

import java.util.Map;

import org.springframework.web.client.RestClient;

/** {@link BookingChaos} over HTTP, straight to a pod's own port. */
public class RestBookingChaos implements BookingChaos {

    static final int PORT = 8081;
    private final RestClient http;

    public RestBookingChaos(RestClient.Builder builder) {
        this.http = builder.build();
    }

    @Override
    public void start(String podIp, String fault, int seconds) {
        http.post().uri("http://" + podIp + ":" + PORT + "/internal/chaos")
                .body(Map.of("fault", fault, "seconds", seconds)).retrieve().toBodilessEntity();
    }

    @Override
    public void stop(String podIp) {
        http.delete().uri("http://" + podIp + ":" + PORT + "/internal/chaos").retrieve().toBodilessEntity();
    }
}
