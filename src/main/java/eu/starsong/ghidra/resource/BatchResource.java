package eu.starsong.ghidra.resource;

import eu.starsong.ghidra.hateoas.Response;
import eu.starsong.ghidra.server.GhidraContext;
import eu.starsong.ghidra.server.Resource;
import eu.starsong.ghidra.service.BatchService;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * REST resource for /batch endpoints: many edits in one transaction.
 */
public class BatchResource implements Resource {

    private final BatchService service;

    public BatchResource() {
        this.service = new BatchService();
    }

    public BatchResource(BatchService service) {
        this.service = service;
    }

    @Override
    public void register(Javalin app, Function<Context, GhidraContext> contextFactory) {
        app.post("/batch/rename-functions", ctx -> renameFunctions(contextFactory.apply(ctx)));
        app.post("/batch/set-comments", ctx -> setComments(contextFactory.apply(ctx)));
        app.post("/batch/define-data", ctx -> defineData(contextFactory.apply(ctx)));
    }

    /**
     * POST /batch/rename-functions
     * Body: {"renames": [{"address": "0x...", "old_name": "...", "new_name": "..."}, ...]}
     */
    private void renameFunctions(GhidraContext ctx) {
        var program = ctx.requireProgram();
        RenameRequest req = ctx.bodyAsClass(RenameRequest.class);
        run(ctx, "/batch/rename-functions", "functions",
            () -> service.renameFunctions(program, req != null ? req.renames : null));
    }

    /**
     * POST /batch/set-comments
     * Body: {"type": "eol", "comments": [{"address": "0x...", "comment": "...", "type": "pre"}, ...]}
     */
    private void setComments(GhidraContext ctx) {
        var program = ctx.requireProgram();
        CommentRequest req = ctx.bodyAsClass(CommentRequest.class);
        run(ctx, "/batch/set-comments", "program",
            () -> service.setComments(program, req != null ? req.comments : null, req != null ? req.type : null));
    }

    /**
     * POST /batch/define-data
     * Body: {"items": [{"address": "0x...", "type": "dword", "label": "magic", "size": 4}, ...]}
     */
    private void defineData(GhidraContext ctx) {
        var program = ctx.requireProgram();
        DefineDataRequest req = ctx.bodyAsClass(DefineDataRequest.class);
        run(ctx, "/batch/define-data", "data",
            () -> service.defineData(program, req != null ? req.items : null));
    }

    private void run(GhidraContext ctx, String self, String related, BatchOperation op) {
        try {
            Map<String, Object> result = op.run();
            ctx.json(Response.ok(ctx.ctx(), ctx.port(), result)
                .self(self)
                .link(related, "/" + related)
                .build());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Batch operation failed: " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface BatchOperation {
        Map<String, Object> run() throws Exception;
    }

    private static class RenameRequest {
        public List<BatchService.RenameItem> renames;
    }

    private static class CommentRequest {
        public String type;
        public List<BatchService.CommentItem> comments;
    }

    private static class DefineDataRequest {
        public List<BatchService.DefineDataItem> items;
    }
}
