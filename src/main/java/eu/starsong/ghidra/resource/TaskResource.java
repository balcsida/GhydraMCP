package eu.starsong.ghidra.resource;

import eu.starsong.ghidra.hateoas.Response;
import eu.starsong.ghidra.server.GhidraContext;
import eu.starsong.ghidra.server.GhydraServer.NotFoundException;
import eu.starsong.ghidra.server.Resource;
import eu.starsong.ghidra.service.DecompilerService;
import eu.starsong.ghidra.service.FunctionService;
import eu.starsong.ghidra.service.TaskService;
import eu.starsong.ghidra.service.TaskService.AsyncTask;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * REST resource for asynchronous decompilation and task polling:
 * POST /functions/decompile-async, GET /tasks/{id}, GET /tasks/{id}/result.
 */
public class TaskResource implements Resource {

    private static final int DEFAULT_TIMEOUT = 300;

    private final TaskService taskService;
    private final FunctionService functionService;
    private final DecompilerService decompilerService;

    public TaskResource(TaskService taskService, FunctionService functionService,
                        DecompilerService decompilerService) {
        this.taskService = taskService;
        this.functionService = functionService;
        this.decompilerService = decompilerService;
    }

    @Override
    public void register(Javalin app, Function<Context, GhidraContext> contextFactory) {
        app.post("/functions/decompile-async", ctx -> decompileAsync(contextFactory.apply(ctx)));
        app.get("/tasks/{id}", ctx -> status(contextFactory.apply(ctx)));
        app.get("/tasks/{id}/result", ctx -> result(contextFactory.apply(ctx)));
    }

    /**
     * POST /functions/decompile-async - Queue a decompilation and return a task id (202).
     * Body: {"address": "0x...", "name": "ns::func", "timeout": 300}
     */
    private void decompileAsync(GhidraContext ctx) {
        var program = ctx.requireProgram();
        DecompileRequest req = ctx.bodyAsClass(DecompileRequest.class);
        if (req == null || (isBlank(req.address) && isBlank(req.name))) {
            throw new IllegalArgumentException("Either address or name is required");
        }
        int timeout = req.timeout != null && req.timeout > 0 ? req.timeout : DEFAULT_TIMEOUT;

        ghidra.program.model.listing.Function function = null;
        if (!isBlank(req.address)) {
            function = functionService.findByAddress(program, req.address);
            if (function == null) {
                function = functionService.findContaining(program, req.address);
            }
        }
        if (function == null && !isBlank(req.name)) {
            function = functionService.findByName(program, req.name);
        }
        if (function == null) {
            throw new NotFoundException("Function not found", "FUNCTION_NOT_FOUND");
        }

        AsyncTask task = taskService.submitDecompile(decompilerService, program, function, timeout);

        ctx.status(202);
        ctx.json(Response.ok(ctx.ctx(), ctx.port(), task.describe())
            .link("task_status", "/tasks/{}", task.id)
            .link("task_result", "/tasks/{}/result", task.id)
            .build());
    }

    /**
     * GET /tasks/{id} - Task status
     */
    private void status(GhidraContext ctx) {
        AsyncTask task = requireTask(ctx);
        Response response = Response.ok(ctx.ctx(), ctx.port(), task.describe())
            .self("/tasks/{}", task.id);
        if (task.isFinished()) {
            response.link("result", "/tasks/{}/result", task.id);
        }
        ctx.json(response.build());
    }

    /**
     * GET /tasks/{id}/result - Task result; the task is forgotten once a finished result
     * has been returned. Answers 202 while the task is still pending or running.
     */
    private void result(GhidraContext ctx) {
        AsyncTask task = requireTask(ctx);

        if (!task.isFinished()) {
            Map<String, Object> pending = task.describe();
            pending.put("message", "Task is not yet complete");
            ctx.status(202);
            ctx.json(Response.ok(ctx.ctx(), ctx.port(), pending)
                .link("task_status", "/tasks/{}", task.id)
                .build());
            return;
        }

        taskService.remove(task.id);

        if (TaskService.FAILED.equals(task.status())) {
            ctx.json(Response.error(ctx.ctx(), ctx.port(), "TASK_FAILED",
                task.error() != null ? task.error() : "Task failed").build());
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>(task.describe());
        data.put("decompiled", task.result());
        ctx.json(Response.ok(ctx.ctx(), ctx.port(), data).build());
    }

    private AsyncTask requireTask(GhidraContext ctx) {
        String id = ctx.pathParam("id");
        AsyncTask task = taskService.get(id);
        if (task == null) {
            throw new NotFoundException("Task not found: " + id, "TASK_NOT_FOUND");
        }
        return task;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }

    private static class DecompileRequest {
        public String address;
        public String name;
        public Integer timeout;
    }
}
