package dev.marwan.console.objects;

/**
 * What a public host said when asked for "/".
 *
 * @param status      HTTP status, or -1 when nothing answered
 * @param appAnswered the body came from our app (JSON), not the router's HTML
 *                    page, which answers 503 when a Route has nothing behind it
 * @param error       why nothing answered, or null
 */
public record RouteProbe(int status, long millis, boolean appAnswered, String error) {

    static RouteProbe failed(String error) {
        return new RouteProbe(-1, -1, false, error);
    }
}
