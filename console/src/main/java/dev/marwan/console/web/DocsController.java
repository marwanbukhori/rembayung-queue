package dev.marwan.console.web;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves the notes, specs and plans baked into the image (see the {@code docs}
 * resource in {@code pom.xml}) as rendered HTML.
 *
 * <h2>The id is the only place a browser string touches the filesystem</h2>
 * Every file actually copied into the image is enumerated exactly once, at
 * startup, into {@link #docs}. Both endpoints below only ever read from that
 * fixed map — the request path is never appended to a directory to build a
 * file path. An id nobody enumerated (a traversal attempt, a typo, anything)
 * simply is not a key, so it resolves to 404 the same way a typo would; there
 * is nothing to filter because there is no path left to construct.
 */
@RestController
@RequestMapping("/api/docs")
public class DocsController {

    private final Map<String, Doc> docs;
    private final Parser parser = Parser.builder().build();
    private final HtmlRenderer renderer = HtmlRenderer.builder().build();

    public DocsController() throws IOException {
        this.docs = load();
    }

    @GetMapping
    public List<DocSummary> list() {
        return docs.values().stream()
                .sorted(Comparator.comparing(Doc::groupOrder).thenComparing(Doc::id))
                .map(d -> new DocSummary(d.id(), d.title(), d.group()))
                .toList();
    }

    @GetMapping(value = "/{id}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> render(@PathVariable String id) {
        Doc doc = docs.get(id);
        if (doc == null) {
            return ResponseEntity.notFound().build();
        }
        String html = renderer.render(parser.parse(doc.markdown()));
        return ResponseEntity.ok(html);
    }

    private static Map<String, Doc> load() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath*:docs/**/*.md");
        Map<String, Doc> found = new LinkedHashMap<>();
        for (Resource resource : resources) {
            String uri = describe(resource);
            String group = groupOf(uri);
            String id = idOf(resource.getFilename());
            String markdown = read(resource);
            String title = titleOf(markdown, id);
            // Filenames are unique across notes, specs and plans; the later
            // resource would otherwise silently shadow the earlier one.
            found.merge(id, new Doc(id, title, group, markdown), (a, b) -> {
                throw new IllegalStateException("duplicate documentation id: " + id);
            });
        }
        return Map.copyOf(found);
    }

    private static String describe(Resource resource) {
        try {
            return resource.getURI().toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String groupOf(String uri) {
        if (uri.contains("/superpowers/specs/")) {
            return "specs";
        }
        if (uri.contains("/superpowers/plans/")) {
            return "plans";
        }
        return "notes";
    }

    private static String idOf(String filename) {
        if (filename == null) {
            throw new IllegalStateException("a documentation resource had no filename");
        }
        return filename.endsWith(".md") ? filename.substring(0, filename.length() - 3) : filename;
    }

    private static String read(Resource resource) throws IOException {
        try (var in = resource.getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        }
    }

    /** The text of the file's first heading, or the id when it has none. */
    private static String titleOf(String markdown, String id) {
        return markdown.lines()
                .map(String::strip)
                .filter(line -> line.startsWith("# "))
                .findFirst()
                .map(line -> line.substring(2).strip())
                .orElse(id);
    }

    private record Doc(String id, String title, String group, String markdown) {
        int groupOrder() {
            return switch (group) {
                case "specs" -> 0;
                case "notes" -> 1;
                case "plans" -> 2;
                default -> 3;
            };
        }
    }
}
