package dev.marwan.console.objects;

import java.util.Map;

/**
 * One line as the Logs tab shows it.
 *
 * @param at     the kubelet's timestamp, nanosecond ISO; also the page's cursor
 * @param level  INFO / WARN / ERROR for our JSON lines, null for plain ones
 * @param event  the structured event name (booking.claimed ...) or null
 * @param fields the event's business fields, masked, in the order they were logged
 */
public record LogLine(String at, String level, String message, String event, Map<String, String> fields) { }
