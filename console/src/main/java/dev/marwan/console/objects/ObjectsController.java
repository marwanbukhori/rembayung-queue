package dev.marwan.console.objects;

import dev.marwan.console.auth.AccessKey;
import dev.marwan.console.auth.KeyFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The inspector's endpoints. GET only, so public under KeyFilter: nothing an
 * object description carries is more sensitive than the pods table already
 * shows. Raw logs, which are, arrive in a later step behind the key.
 */
@RestController
public class ObjectsController {

    private final ObjectsProvider objects;
    private final PodLogs logs;
    private final AccessKey key;

    public ObjectsController(ObjectsProvider objects, PodLogs logs, AccessKey key) {
        this.objects = objects;
        this.logs = logs;
        this.key = key;
    }

    /**
     * A pod's log lines since a cursor. Public like every GET, but what it
     * returns depends on the key: without one, app events only. The rule is
     * applied in PodLogs, here only decided - by the key the request presents,
     * never by what it asks for.
     */
    @GetMapping("/api/pods/{name}/logs")
    public LogPage logs(@PathVariable String name,
                        @RequestParam(required = false) String since,
                        @RequestParam(defaultValue = "all") String filter,
                        HttpServletRequest request) {
        String presented = request.getHeader(KeyFilter.HEADER);
        if (presented == null) {
            presented = request.getParameter(KeyFilter.QUERY_PARAM);
        }
        return logs.read(name, since, filter, key.accepts(presented));
    }

    @GetMapping("/api/objects/{kind}/{name}")
    public ObjectDetail describe(@PathVariable String kind, @PathVariable String name) {
        return objects.describe(kind, name);
    }

    /** Only Jobs are listed: they are the one kind the graph has no fixed box for. */
    @GetMapping("/api/objects")
    public List<ObjectSummary> list(@RequestParam String kind) {
        if (!ObjectKind.JOB.path().equals(kind)) {
            throw new ObjectNotFound("only jobs are listed");
        }
        return objects.recentJobs();
    }

    /**
     * A fixed JSON body, never the message. The message echoes the requested
     * kind and name, and a bare String here was served as text/html: a link
     * carrying markup in the path ran script on this public origin.
     */
    @ExceptionHandler(ObjectNotFound.class)
    public ResponseEntity<Map<String, String>> notFound(ObjectNotFound e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "NOT_FOUND"));
    }
}
