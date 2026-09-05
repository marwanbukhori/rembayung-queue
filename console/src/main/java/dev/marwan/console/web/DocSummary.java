package dev.marwan.console.web;

/**
 * One row in the documentation list: enough to render a link, nothing that
 * requires opening the file.
 */
public record DocSummary(String id, String title, String group) {
}
