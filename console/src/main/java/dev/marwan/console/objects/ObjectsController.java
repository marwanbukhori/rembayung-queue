package dev.marwan.console.objects;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The inspector's endpoints. GET only, so public under KeyFilter: nothing an
 * object description carries is more sensitive than the pods table already
 * shows. Raw logs, which are, arrive in a later step behind the key.
 */
@RestController
public class ObjectsController {

    private final ObjectsProvider objects;

    public ObjectsController(ObjectsProvider objects) {
        this.objects = objects;
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

    @ExceptionHandler(ObjectNotFound.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(ObjectNotFound e) {
        return e.getMessage();
    }
}
