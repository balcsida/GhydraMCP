package eu.starsong.ghidra.resource;

import eu.starsong.ghidra.hateoas.Paginator;
import eu.starsong.ghidra.hateoas.Response;
import eu.starsong.ghidra.server.GhidraContext;
import eu.starsong.ghidra.server.Resource;
import eu.starsong.ghidra.service.BookmarkService;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * REST resource for /bookmarks endpoints.
 */
public class BookmarkResource implements Resource {

    private final BookmarkService service;

    public BookmarkResource() {
        this.service = new BookmarkService();
    }

    public BookmarkResource(BookmarkService service) {
        this.service = service;
    }

    @Override
    public void register(Javalin app, Function<Context, GhidraContext> contextFactory) {
        app.get("/bookmarks", ctx -> list(contextFactory.apply(ctx)));
        app.post("/bookmarks", ctx -> create(contextFactory.apply(ctx)));
        app.delete("/bookmarks/{address}", ctx -> delete(contextFactory.apply(ctx)));
    }

    /**
     * GET /bookmarks - List bookmarks (optional ?type= filter) with pagination
     */
    private void list(GhidraContext ctx) {
        var program = ctx.requireProgram();
        var pagination = ctx.pagination();

        List<Map<String, Object>> bookmarks = service.list(program, ctx.queryParam("type"));

        ctx.json(Paginator.paginate(bookmarks, pagination, "/bookmarks")
            .toResponse(ctx.ctx(), ctx.port())
            .link("program", "/program")
            .linkWithMethod("create", "/bookmarks", "POST")
            .build());
    }

    /**
     * POST /bookmarks - Create a bookmark.
     * Body: {"address": "0x...", "type": "Note", "category": "...", "comment": "..."}
     */
    private void create(GhidraContext ctx) {
        var program = ctx.requireProgram();
        CreateRequest req = ctx.bodyAsClass(CreateRequest.class);
        if (req == null) {
            throw new IllegalArgumentException("Request body is required");
        }

        try {
            Map<String, Object> bookmark = service.create(program, req.address, req.type, req.category, req.comment);
            ctx.status(201);
            ctx.json(Response.ok(ctx.ctx(), ctx.port(), bookmark)
                .self("/bookmarks")
                .linkWithMethod("delete", "/bookmarks/{}?type={}", "DELETE",
                    bookmark.get("address"), bookmark.get("type"))
                .build());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create bookmark: " + e.getMessage(), e);
        }
    }

    /**
     * DELETE /bookmarks/{address}?type=Note - Delete the bookmark(s) of a type at an address
     */
    private void delete(GhidraContext ctx) {
        var program = ctx.requireProgram();
        String address = ctx.pathParam("address");

        try {
            Map<String, Object> result = service.delete(program, address, ctx.queryParam("type"));
            ctx.json(Response.ok(ctx.ctx(), ctx.port(), result)
                .link("bookmarks", "/bookmarks")
                .build());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete bookmark: " + e.getMessage(), e);
        }
    }

    private static class CreateRequest {
        public String address;
        public String type;
        public String category;
        public String comment;
    }
}
