package eu.starsong.ghidra.endpoints;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.starsong.ghidra.api.ResponseBuilder;
import eu.starsong.ghidra.util.DecompilerCache;
import eu.starsong.ghidra.util.HttpUtil;
import ghidra.framework.plugintool.PluginTool;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.util.Msg;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Endpoints for asynchronous decompilation with task polling.
 * Provides /functions/decompile-async and /tasks/* endpoints.
 */
public class AsyncEndpoints extends AbstractEndpoint {

    private PluginTool tool;

    private static final ConcurrentHashMap<String, AsyncTask> tasks = new ConcurrentHashMap<>();
    private static final ExecutorService executor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2));

    /**
     * Represents an asynchronous task with status tracking.
     */
    static class AsyncTask {
        final String id;
        volatile String status;   // pending, running, completed, failed
        volatile String result;
        volatile String error;
        final Instant createdAt;

        AsyncTask(String id) {
            this.id = id;
            this.status = "pending";
            this.createdAt = Instant.now();
        }
    }

    public AsyncEndpoints(Program program, int port, PluginTool tool, DecompilerCache cache) {
        super(program, port, cache);
        this.tool = tool;
    }

    @Override
    protected PluginTool getTool() {
        return tool;
    }

    @Override
    public void registerEndpoints(HttpServer server) {
        server.createContext("/functions/decompile-async",
                HttpUtil.safeHandler(this::handleDecompileAsync, port));
        server.createContext("/tasks/", HttpUtil.safeHandler(this::handleTaskRequest, port));
    }

    /**
     * POST /functions/decompile-async
     * Body: { "address": "0x...", "name": "funcName", "timeout": 300 }
     * Returns a task_id immediately.
     */
    private void handleDecompileAsync(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
            return;
        }

        Program program = getProgram(exchange);
        if (program == null) {
            sendErrorResponse(exchange, 400, "No program is currently loaded", "NO_PROGRAM_LOADED");
            return;
        }

        Map<String, String> params = parseJsonPostParams(exchange);
        String address = params.get("address");
        String name = params.get("name");
        int timeout = parseIntOrDefault(params.get("timeout"), 300);

        // Resolve the function
        Function function = null;
        if (address != null && !address.isEmpty()) {
            function = findFunctionByAddress(program, address);
        }
        if (function == null && name != null && !name.isEmpty()) {
            function = findFunctionByName(program, name);
        }
        if (function == null) {
            sendErrorResponse(exchange, 404, "Function not found", "FUNCTION_NOT_FOUND");
            return;
        }

        // Create async task
        String taskId = UUID.randomUUID().toString();
        AsyncTask task = new AsyncTask(taskId);
        tasks.put(taskId, task);

        // Capture references for the background thread
        final Function targetFunction = function;
        final DecompilerCache cache = getDecompilerCache();

        executor.submit(() -> {
            task.status = "running";
            try {
                String code;
                if (cache != null) {
                    code = cache.getDecompiledCode(targetFunction, timeout);
                } else {
                    code = eu.starsong.ghidra.util.GhidraUtil.decompileFunction(
                            targetFunction, true, timeout);
                }
                if (code != null) {
                    task.result = code;
                    task.status = "completed";
                } else {
                    task.error = "Decompilation returned no result";
                    task.status = "failed";
                }
            } catch (Exception e) {
                Msg.error(this, "Async decompilation failed for " + targetFunction.getName(), e);
                task.error = e.getMessage();
                task.status = "failed";
            }
        });

        // Return the task_id immediately
        Map<String, Object> result = new HashMap<>();
        result.put("task_id", taskId);
        result.put("status", task.status);

        ResponseBuilder builder = new ResponseBuilder(exchange, port)
                .success(true)
                .result(result);
        builder.addLink("task_status", "/tasks/" + taskId);
        builder.addLink("task_result", "/tasks/" + taskId + "/result");
        sendJsonResponse(exchange, builder.build(), 202);
    }

    /**
     * Routes /tasks/{task_id} and /tasks/{task_id}/result
     */
    private void handleTaskRequest(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        // Strip the /tasks/ prefix
        String remainder = path.substring("/tasks/".length());

        boolean isResultRequest = false;
        String taskId;
        if (remainder.endsWith("/result")) {
            isResultRequest = true;
            taskId = remainder.substring(0, remainder.length() - "/result".length());
        } else {
            // Strip trailing slash if present
            taskId = remainder.endsWith("/") ? remainder.substring(0, remainder.length() - 1) : remainder;
        }

        AsyncTask task = tasks.get(taskId);
        if (task == null) {
            sendErrorResponse(exchange, 404, "Task not found", "TASK_NOT_FOUND");
            return;
        }

        if (isResultRequest) {
            handleTaskResult(exchange, task);
        } else {
            handleTaskStatus(exchange, task);
        }
    }

    /**
     * GET /tasks/{task_id} - Return task status
     */
    private void handleTaskStatus(HttpExchange exchange, AsyncTask task) throws IOException {
        Map<String, Object> result = new HashMap<>();
        result.put("task_id", task.id);
        result.put("status", task.status);
        result.put("created_at", task.createdAt.toString());
        if (task.error != null) {
            result.put("error", task.error);
        }

        ResponseBuilder builder = new ResponseBuilder(exchange, port)
                .success(true)
                .result(result);
        builder.addLink("self", "/tasks/" + task.id);
        if ("completed".equals(task.status) || "failed".equals(task.status)) {
            builder.addLink("result", "/tasks/" + task.id + "/result");
        }
        sendJsonResponse(exchange, builder.build(), 200);
    }

    /**
     * GET /tasks/{task_id}/result - Return result and clean up the task
     */
    private void handleTaskResult(HttpExchange exchange, AsyncTask task) throws IOException {
        if (!"completed".equals(task.status) && !"failed".equals(task.status)) {
            Map<String, Object> result = new HashMap<>();
            result.put("task_id", task.id);
            result.put("status", task.status);
            result.put("message", "Task is not yet complete");

            ResponseBuilder builder = new ResponseBuilder(exchange, port)
                    .success(false)
                    .result(result);
            builder.addLink("task_status", "/tasks/" + task.id);
            sendJsonResponse(exchange, builder.build(), 202);
            return;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("task_id", task.id);
        result.put("status", task.status);
        result.put("created_at", task.createdAt.toString());

        if ("completed".equals(task.status)) {
            result.put("decompiled", task.result);
        }
        if (task.error != null) {
            result.put("error", task.error);
        }

        ResponseBuilder builder = new ResponseBuilder(exchange, port)
                .success("completed".equals(task.status))
                .result(result);
        sendJsonResponse(exchange, builder.build(), 200);

        // Clean up the task after result is retrieved
        tasks.remove(task.id);
    }

    // --- Function lookup helpers ---

    private Function findFunctionByAddress(Program program, String addressString) {
        try {
            ghidra.program.model.address.Address address =
                    program.getAddressFactory().getAddress(addressString);
            Function func = program.getFunctionManager().getFunctionAt(address);
            if (func == null) {
                func = program.getFunctionManager().getFunctionContaining(address);
            }
            return func;
        } catch (Exception e) {
            return null;
        }
    }

    private Function findFunctionByName(Program program, String name) {
        for (Function f : program.getFunctionManager().getFunctions(true)) {
            if (f.getName().equals(name)) {
                return f;
            }
        }
        return null;
    }
}
