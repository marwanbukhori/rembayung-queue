package dev.marwan.console.objects;

import java.util.List;

/**
 * One poll of a pod's Logs tab.
 *
 * @param filter     the filter actually applied, which is not always the one asked for
 * @param restricted true when the filter was forced to events because there was no key
 * @param note       one line of context for the tab, or null
 * @param latest     the cursor to send as `since` next time
 */
public record LogPage(String pod, boolean available, String detail, String filter, boolean restricted,
                      String note, List<LogLine> lines, String latest) { }
