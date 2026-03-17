package eu.starsong.ghidra.endpoints;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import eu.starsong.ghidra.api.ResponseBuilder;
import eu.starsong.ghidra.util.GhidraUtil;
import eu.starsong.ghidra.util.HttpUtil;
import eu.starsong.ghidra.util.TransactionHelper;
import ghidra.framework.plugintool.PluginTool;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.DataType;
import ghidra.program.model.listing.CommentType;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.listing.Program;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.model.symbol.SymbolTable;
import ghidra.util.Msg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoints for batch operations on functions, comments, and data.
 */
public class BatchEndpoints extends AbstractEndpoint {

    private PluginTool tool;

    public BatchEndpoints(Program program, int port) {
        super(program, port);
    }

    public BatchEndpoints(Program program, int port, PluginTool tool) {
        super(program, port);
        this.tool = tool;
    }

    @Override
    protected PluginTool getTool() {
        return tool;
    }

    @Override
    public void registerEndpoints(HttpServer server) {
        server.createContext("/batch/rename-functions", HttpUtil.safeHandler(exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                handleBatchRenameFunctions(exchange);
            } else {
                sendErrorResponse(exchange, 405, "Method Not Allowed");
            }
        }, port));

        server.createContext("/batch/set-comments", HttpUtil.safeHandler(exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                handleBatchSetComments(exchange);
            } else {
                sendErrorResponse(exchange, 405, "Method Not Allowed");
            }
        }, port));

        server.createContext("/batch/define-data", HttpUtil.safeHandler(exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                handleBatchDefineData(exchange);
            } else {
                sendErrorResponse(exchange, 405, "Method Not Allowed");
            }
        }, port));
    }

    /**
     * POST /batch/rename-functions
     * Body: { "renames": [ { "old_name": "...", "address": "0x...", "new_name": "..." }, ... ] }
     * Renames multiple functions in a single transaction.
     */
    private void handleBatchRenameFunctions(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        try {
            Program program = getProgram(exchange);
            if (program == null) {
                sendErrorResponse(exchange, 400, "No program loaded", "NO_PROGRAM_LOADED");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject json = gson.fromJson(body, JsonObject.class);
            JsonArray renames = json.getAsJsonArray("renames");

            if (renames == null || renames.isEmpty()) {
                sendErrorResponse(exchange, 400, "Missing or empty 'renames' array", "MISSING_PARAMETERS");
                return;
            }

            List<Map<String, Object>> results = new ArrayList<>();

            try {
                TransactionHelper.executeInTransaction(program, "Batch rename functions", () -> {
                    FunctionManager funcMgr = program.getFunctionManager();

                    for (int i = 0; i < renames.size(); i++) {
                        JsonObject item = renames.get(i).getAsJsonObject();
                        String newName = item.get("new_name").getAsString();
                        Map<String, Object> result = new HashMap<>();
                        result.put("new_name", newName);

                        Function function = null;

                        // Try to find by address first
                        if (item.has("address") && !item.get("address").isJsonNull()) {
                            String addressStr = item.get("address").getAsString();
                            result.put("address", addressStr);
                            Address addr = program.getAddressFactory().getAddress(addressStr);
                            if (addr != null) {
                                function = funcMgr.getFunctionAt(addr);
                            }
                        }

                        // Fall back to old_name lookup
                        if (function == null && item.has("old_name") && !item.get("old_name").isJsonNull()) {
                            String oldName = item.get("old_name").getAsString();
                            result.put("old_name", oldName);
                            for (Function f : funcMgr.getFunctions(true)) {
                                if (f.getName().equals(oldName)) {
                                    function = f;
                                    break;
                                }
                            }
                        }

                        if (function != null) {
                            String originalName = function.getName();
                            function.setName(newName, SourceType.USER_DEFINED);
                            result.put("status", "renamed");
                            result.put("original_name", originalName);
                        } else {
                            result.put("status", "not_found");
                        }

                        results.add(result);
                    }
                    return null;
                });
            } catch (TransactionHelper.TransactionException e) {
                Msg.error(this, "Batch rename functions transaction failed", e);
                sendErrorResponse(exchange, 500, "Transaction failed: " + e.getMessage(), "TRANSACTION_ERROR");
                return;
            }

            long successCount = results.stream().filter(r -> "renamed".equals(r.get("status"))).count();
            Map<String, Object> resultData = new HashMap<>();
            resultData.put("total", renames.size());
            resultData.put("successful", successCount);
            resultData.put("failed", renames.size() - successCount);
            resultData.put("results", results);

            ResponseBuilder builder = new ResponseBuilder(exchange, port)
                .success(true)
                .result(resultData)
                .addLink("self", "/batch/rename-functions")
                .addLink("functions", "/functions");

            sendJsonResponse(exchange, builder.build(), 200);

        } catch (Exception e) {
            Msg.error(this, "Error in batch rename functions", e);
            sendErrorResponse(exchange, 500, "Error: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    /**
     * POST /batch/set-comments
     * Body: { "comments": [ { "address": "0x...", "comment": "...", "type": "eol" }, ... ] }
     * Sets multiple comments in a single transaction.
     */
    private void handleBatchSetComments(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        try {
            Program program = getProgram(exchange);
            if (program == null) {
                sendErrorResponse(exchange, 400, "No program loaded", "NO_PROGRAM_LOADED");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject json = gson.fromJson(body, JsonObject.class);
            JsonArray comments = json.getAsJsonArray("comments");

            if (comments == null || comments.isEmpty()) {
                sendErrorResponse(exchange, 400, "Missing or empty 'comments' array", "MISSING_PARAMETERS");
                return;
            }

            List<Map<String, Object>> results = new ArrayList<>();

            try {
                TransactionHelper.executeInTransaction(program, "Batch set comments", () -> {
                    Listing listing = program.getListing();

                    for (int i = 0; i < comments.size(); i++) {
                        JsonObject item = comments.get(i).getAsJsonObject();
                        String addressStr = item.get("address").getAsString();
                        String comment = item.get("comment").getAsString();
                        String typeStr = item.has("type") && !item.get("type").isJsonNull()
                            ? item.get("type").getAsString() : "eol";

                        Map<String, Object> result = new HashMap<>();
                        result.put("address", addressStr);

                        Address addr = program.getAddressFactory().getAddress(addressStr);
                        if (addr == null) {
                            result.put("status", "invalid_address");
                            results.add(result);
                            continue;
                        }

                        CommentType commentType = getCommentType(typeStr);
                        listing.setComment(addr, commentType, comment);
                        result.put("status", "set");
                        result.put("type", typeStr);
                        results.add(result);
                    }
                    return null;
                });
            } catch (TransactionHelper.TransactionException e) {
                Msg.error(this, "Batch set comments transaction failed", e);
                sendErrorResponse(exchange, 500, "Transaction failed: " + e.getMessage(), "TRANSACTION_ERROR");
                return;
            }

            long successCount = results.stream().filter(r -> "set".equals(r.get("status"))).count();
            Map<String, Object> resultData = new HashMap<>();
            resultData.put("total", comments.size());
            resultData.put("successful", successCount);
            resultData.put("failed", comments.size() - successCount);
            resultData.put("results", results);

            ResponseBuilder builder = new ResponseBuilder(exchange, port)
                .success(true)
                .result(resultData)
                .addLink("self", "/batch/set-comments");

            sendJsonResponse(exchange, builder.build(), 200);

        } catch (Exception e) {
            Msg.error(this, "Error in batch set comments", e);
            sendErrorResponse(exchange, 500, "Error: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    /**
     * POST /batch/define-data
     * Body: { "items": [ { "address": "0x...", "type": "dword", "label": "my_var" }, ... ] }
     * Defines multiple data items in a single transaction.
     */
    private void handleBatchDefineData(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        try {
            Program program = getProgram(exchange);
            if (program == null) {
                sendErrorResponse(exchange, 400, "No program loaded", "NO_PROGRAM_LOADED");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject json = gson.fromJson(body, JsonObject.class);
            JsonArray items = json.getAsJsonArray("items");

            if (items == null || items.isEmpty()) {
                sendErrorResponse(exchange, 400, "Missing or empty 'items' array", "MISSING_PARAMETERS");
                return;
            }

            List<Map<String, Object>> results = new ArrayList<>();

            try {
                TransactionHelper.executeInTransaction(program, "Batch define data", () -> {
                    Listing listing = program.getListing();
                    SymbolTable symTable = program.getSymbolTable();

                    for (int i = 0; i < items.size(); i++) {
                        JsonObject item = items.get(i).getAsJsonObject();
                        String addressStr = item.get("address").getAsString();
                        String typeStr = item.get("type").getAsString();
                        String label = item.has("label") && !item.get("label").isJsonNull()
                            ? item.get("label").getAsString() : null;

                        Map<String, Object> result = new HashMap<>();
                        result.put("address", addressStr);
                        result.put("type", typeStr);

                        Address addr = program.getAddressFactory().getAddress(addressStr);
                        if (addr == null) {
                            result.put("status", "invalid_address");
                            results.add(result);
                            continue;
                        }

                        DataType dataType = GhidraUtil.resolveDataType(program, typeStr);
                        if (dataType == null) {
                            result.put("status", "unknown_type");
                            results.add(result);
                            continue;
                        }

                        // Clear existing code units at this address range
                        int dataSize = dataType.getLength();
                        if (dataSize <= 0) dataSize = 4;
                        listing.clearCodeUnits(addr, addr.add(dataSize - 1), false);

                        Data newData = listing.createData(addr, dataType);
                        if (newData == null) {
                            result.put("status", "failed");
                            results.add(result);
                            continue;
                        }

                        if (label != null && !label.isEmpty()) {
                            symTable.createLabel(addr, label, SourceType.USER_DEFINED);
                            result.put("label", label);
                        }

                        result.put("status", "defined");
                        result.put("size", newData.getLength());
                        results.add(result);
                    }
                    return null;
                });
            } catch (TransactionHelper.TransactionException e) {
                Msg.error(this, "Batch define data transaction failed", e);
                sendErrorResponse(exchange, 500, "Transaction failed: " + e.getMessage(), "TRANSACTION_ERROR");
                return;
            }

            long successCount = results.stream().filter(r -> "defined".equals(r.get("status"))).count();
            Map<String, Object> resultData = new HashMap<>();
            resultData.put("total", items.size());
            resultData.put("successful", successCount);
            resultData.put("failed", items.size() - successCount);
            resultData.put("results", results);

            ResponseBuilder builder = new ResponseBuilder(exchange, port)
                .success(true)
                .result(resultData)
                .addLink("self", "/batch/define-data")
                .addLink("data", "/data");

            sendJsonResponse(exchange, builder.build(), 200);

        } catch (Exception e) {
            Msg.error(this, "Error in batch define data", e);
            sendErrorResponse(exchange, 500, "Error: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    /**
     * Convert comment type string to Ghidra's CommentType enum.
     */
    private CommentType getCommentType(String commentType) {
        switch (commentType.toLowerCase()) {
            case "plate":
                return CommentType.PLATE;
            case "pre":
                return CommentType.PRE;
            case "post":
                return CommentType.POST;
            case "eol":
                return CommentType.EOL;
            case "repeatable":
                return CommentType.REPEATABLE;
            default:
                return CommentType.EOL;
        }
    }
}
