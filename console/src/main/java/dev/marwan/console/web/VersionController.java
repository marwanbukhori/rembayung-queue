package dev.marwan.console.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.marwan.console.objects.ObjectSource;

/** The commit each service runs, for the badge beside the GitHub link. Public, like every read. */
@RestController
public class VersionController {

    private final ObjectSource objects;

    public VersionController(ObjectSource objects) {
        this.objects = objects;
    }

    @GetMapping("/api/version")
    public Versions.Version version() {
        return Versions.read(objects::deployment);
    }
}
