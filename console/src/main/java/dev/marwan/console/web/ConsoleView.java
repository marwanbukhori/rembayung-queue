package dev.marwan.console.web;

import dev.marwan.console.cluster.PodHealth;
import dev.marwan.console.state.DemoState;

/**
 * Everything one poll of the page needs: the drop's numbers, and the pods.
 *
 * The two carry their own availability rather than sharing one, because they
 * fail independently and usefully — a visitor whose sandbox expired still wants
 * to see the cluster is healthy, and an operator whose Kubernetes token lapsed
 * still wants to see the seats.
 *
 * Tasks 6 and 7 add fields here for the issued key and the namespace budget.
 */
public record ConsoleView(DemoState drop, PodHealth pods) { }
