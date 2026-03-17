package eu.starsong.ghidra.endpoints;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.starsong.ghidra.api.ResponseBuilder;
import eu.starsong.ghidra.util.TransactionHelper;
import ghidra.framework.plugintool.PluginTool;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressFactory;
import ghidra.program.model.listing.Bookmark;
import ghidra.program.model.listing.BookmarkManager;
import ghidra.program.model.listing.Program;
import ghidra.util.Msg;

import java.io.IOException;
import java.util.*;

public class BookmarkEndpoints extends AbstractEndpoint {

    private PluginTool tool;

    public BookmarkEndpoints(Program program, int port) {
        super(program, port);
    }

    public BookmarkEndpoints(Program program, int port, PluginTool tool) {
        super(program, port);
        this.tool = tool;
    }

    @Override
    protected PluginTool getTool() {
        return tool;
    }

    @Override
    public void registerEndpoints(HttpServer server) {
        server.createContext("/bookmarks", this::handleBookmarks);
    }

    private void handleBookmarks(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            Program program = getProgram(exchange);
            if (program == null) {
                sendErrorResponse(exchange, 400, "No program loaded", "NO_PROGRAM_LOADED");
                return;
            }

            switch (method) {
                case "GET":
                    handleListBookmarks(exchange, program);
                    break;
                case "POST":
                    handleCreateBookmark(exchange, program);
                    break;
                case "DELETE":
                    handleDeleteBookmark(exchange, program);
                    break;
                default:
                    sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
            }
        } catch (Exception e) {
            Msg.error(this, "Error in /bookmarks endpoint", e);
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    private void handleListBookmarks(HttpExchange exchange, Program program) throws IOException {
        Map<String, String> params = parseQueryParams(exchange);
        int offset = parseIntOrDefault(params.get("offset"), 0);
        int limit = parseIntOrDefault(params.get("limit"), 100);

        BookmarkManager bmManager = program.getBookmarkManager();
        Iterator<Bookmark> iter = bmManager.getBookmarksIterator();
        List<Map<String, Object>> allBookmarks = new ArrayList<>();

        while (iter.hasNext()) {
            Bookmark bm = iter.next();
            Map<String, Object> entry = new HashMap<>();
            entry.put("address", bm.getAddress().toString());
            entry.put("type", bm.getTypeString());
            entry.put("category", bm.getCategory());
            entry.put("comment", bm.getComment());
            allBookmarks.add(entry);
        }

        ResponseBuilder builder = new ResponseBuilder(exchange, port)
            .success(true)
            .addLink("program", "/program")
            .addLink("create", "/bookmarks", "POST")
            .addLink("delete", "/bookmarks", "DELETE");

        List<Map<String, Object>> paginated = applyPagination(allBookmarks, offset, limit, builder, "/bookmarks");
        builder.result(paginated);

        sendJsonResponse(exchange, builder.build(), 200);
    }

    private void handleCreateBookmark(HttpExchange exchange, Program program) throws IOException {
        Map<String, String> params = parseJsonPostParams(exchange);
        String addressStr = params.get("address");
        String type = params.get("type");
        String category = params.get("category");
        String comment = params.get("comment");

        if (addressStr == null || addressStr.isEmpty()) {
            sendErrorResponse(exchange, 400, "address parameter is required", "MISSING_PARAMETER");
            return;
        }
        if (type == null || type.isEmpty()) {
            type = "Note";
        }
        if (category == null) {
            category = "";
        }
        if (comment == null) {
            comment = "";
        }

        AddressFactory addressFactory = program.getAddressFactory();
        Address address;
        try {
            address = addressFactory.getAddress(addressStr);
        } catch (Exception e) {
            sendErrorResponse(exchange, 400, "Invalid address format: " + addressStr, "INVALID_ADDRESS");
            return;
        }

        if (address == null) {
            sendErrorResponse(exchange, 400, "Invalid address: " + addressStr, "INVALID_ADDRESS");
            return;
        }

        final String bmType = type;
        final String bmCategory = category;
        final String bmComment = comment;

        try {
            TransactionHelper.executeInTransaction(program, "Create bookmark at " + address, () -> {
                BookmarkManager bmManager = program.getBookmarkManager();
                bmManager.setBookmark(address, bmType, bmCategory, bmComment);
                return true;
            });

            Map<String, Object> result = new HashMap<>();
            result.put("address", address.toString());
            result.put("type", bmType);
            result.put("category", bmCategory);
            result.put("comment", bmComment);

            ResponseBuilder builder = new ResponseBuilder(exchange, port)
                .success(true)
                .result(result)
                .addLink("self", "/bookmarks")
                .addLink("list", "/bookmarks");

            sendJsonResponse(exchange, builder.build(), 201);

        } catch (Exception e) {
            Msg.error(this, "Failed to create bookmark", e);
            sendErrorResponse(exchange, 500, "Failed to create bookmark: " + e.getMessage(), "BOOKMARK_CREATE_FAILED");
        }
    }

    private void handleDeleteBookmark(HttpExchange exchange, Program program) throws IOException {
        Map<String, String> params = parseJsonPostParams(exchange);
        String addressStr = params.get("address");
        String type = params.get("type");

        if (addressStr == null || addressStr.isEmpty()) {
            sendErrorResponse(exchange, 400, "address parameter is required", "MISSING_PARAMETER");
            return;
        }
        if (type == null || type.isEmpty()) {
            type = "Note";
        }

        AddressFactory addressFactory = program.getAddressFactory();
        Address address;
        try {
            address = addressFactory.getAddress(addressStr);
        } catch (Exception e) {
            sendErrorResponse(exchange, 400, "Invalid address format: " + addressStr, "INVALID_ADDRESS");
            return;
        }

        if (address == null) {
            sendErrorResponse(exchange, 400, "Invalid address: " + addressStr, "INVALID_ADDRESS");
            return;
        }

        final String bmType = type;

        try {
            boolean removed = TransactionHelper.executeInTransaction(program, "Delete bookmark at " + address, () -> {
                BookmarkManager bmManager = program.getBookmarkManager();
                Bookmark bm = bmManager.getBookmark(address, bmType, "");
                if (bm == null) {
                    // Try to find any bookmark of the given type at this address
                    Bookmark[] bookmarks = bmManager.getBookmarks(address);
                    for (Bookmark b : bookmarks) {
                        if (b.getTypeString().equals(bmType)) {
                            bmManager.removeBookmark(b);
                            return true;
                        }
                    }
                    return false;
                }
                bmManager.removeBookmark(bm);
                return true;
            });

            if (removed) {
                Map<String, Object> result = new HashMap<>();
                result.put("address", address.toString());
                result.put("type", bmType);
                result.put("deleted", true);

                ResponseBuilder builder = new ResponseBuilder(exchange, port)
                    .success(true)
                    .result(result)
                    .addLink("self", "/bookmarks")
                    .addLink("list", "/bookmarks");

                sendJsonResponse(exchange, builder.build(), 200);
            } else {
                sendErrorResponse(exchange, 404, "No bookmark of type '" + bmType + "' found at " + addressStr, "BOOKMARK_NOT_FOUND");
            }

        } catch (Exception e) {
            Msg.error(this, "Failed to delete bookmark", e);
            sendErrorResponse(exchange, 500, "Failed to delete bookmark: " + e.getMessage(), "BOOKMARK_DELETE_FAILED");
        }
    }
}
