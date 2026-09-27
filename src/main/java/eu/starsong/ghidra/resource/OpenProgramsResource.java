package eu.starsong.ghidra.resource;

import eu.starsong.ghidra.hateoas.Response;
import eu.starsong.ghidra.server.GhidraContext;
import eu.starsong.ghidra.server.Resource;
import eu.starsong.ghidra.service.OpenProgramService;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * REST resource for multi-file support: the programs open in this tool.
 *
 * <p>Any other endpoint can target one of these programs with {@code ?program=<name>}.
 */
public class OpenProgramsResource implements Resource {

    private final OpenProgramService service;

    public OpenProgramsResource() {
        this.service = new OpenProgramService();
    }

    public OpenProgramsResource(OpenProgramService service) {
        this.service = service;
    }

    @Override
    public void register(Javalin app, Function<Context, GhidraContext> contextFactory) {
        app.get("/programs/open-programs", ctx -> listOpen(contextFactory.apply(ctx)));
        app.post("/programs/open", ctx -> open(contextFactory.apply(ctx)));
        app.post("/programs/close", ctx -> close(contextFactory.apply(ctx)));
        app.post("/programs/switch", ctx -> switchTo(contextFactory.apply(ctx)));
    }

    /**
     * GET /programs/open-programs - List programs open in this tool
     */
    private void listOpen(GhidraContext ctx) {
        List<Map<String, Object>> programs = service.listOpen(ctx.tool());
        ctx.json(Response.ok(ctx.ctx(), ctx.port(), programs)
            .self("/programs/open-programs")
            .linkWithMethod("open", "/programs/open", "POST")
            .linkWithMethod("close", "/programs/close", "POST")
            .linkWithMethod("switch", "/programs/switch", "POST")
            .build());
    }

    /**
     * POST /programs/open - Open a project file in this tool. Body: {"path": "/in/project"}
     */
    private void open(GhidraContext ctx) {
        PathRequest req = ctx.bodyAsClass(PathRequest.class);
        Map<String, Object> result = service.open(ctx.tool(), req != null ? req.path : null);
        ctx.json(Response.ok(ctx.ctx(), ctx.port(), result)
            .self("/programs/open")
            .link("open_programs", "/programs/open-programs")
            .build());
    }

    /**
     * POST /programs/close - Close an open program. Body: {"name": "...", "discard": false}
     */
    private void close(GhidraContext ctx) {
        NameRequest req = ctx.bodyAsClass(NameRequest.class);
        boolean discard = req != null && Boolean.TRUE.equals(req.discard);
        Map<String, Object> result = service.close(ctx.tool(), req != null ? req.name : null, discard);
        ctx.json(Response.ok(ctx.ctx(), ctx.port(), result)
            .self("/programs/close")
            .link("open_programs", "/programs/open-programs")
            .build());
    }

    /**
     * POST /programs/switch - Make an open program current. Body: {"name": "..."}
     */
    private void switchTo(GhidraContext ctx) {
        NameRequest req = ctx.bodyAsClass(NameRequest.class);
        Map<String, Object> result = service.switchTo(ctx.tool(), req != null ? req.name : null);
        ctx.json(Response.ok(ctx.ctx(), ctx.port(), result)
            .self("/programs/switch")
            .link("open_programs", "/programs/open-programs")
            .link("program", "/program")
            .build());
    }

    private static class PathRequest {
        public String path;
    }

    private static class NameRequest {
        public String name;
        public Boolean discard;
    }
}
