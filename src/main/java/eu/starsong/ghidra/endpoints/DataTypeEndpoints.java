package eu.starsong.ghidra.endpoints;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.starsong.ghidra.api.ResponseBuilder;
import eu.starsong.ghidra.util.TransactionHelper;
import ghidra.framework.plugintool.PluginTool;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.listing.Program;
import ghidra.util.Msg;

import java.io.IOException;
import java.util.*;

/**
 * Endpoints for managing custom data types (structs, unions, enums).
 * Implements HATEOAS-compliant REST API for data type manipulation.
 */
public class DataTypeEndpoints extends AbstractEndpoint {

    private PluginTool tool;

    public DataTypeEndpoints(Program program, int port) {
        super(program, port);
    }

    public DataTypeEndpoints(Program program, int port, PluginTool tool) {
        super(program, port);
        this.tool = tool;
    }

    @Override
    protected PluginTool getTool() {
        return tool;
    }

    @Override
    public void registerEndpoints(HttpServer server) {
        server.createContext("/datatypes", this::handleDataTypes);
        server.createContext("/datatypes/struct", this::handleCreateStruct);
        server.createContext("/datatypes/enum", this::handleCreateEnum);
        server.createContext("/datatypes/union", this::handleCreateUnion);
        server.createContext("/datatypes/apply", this::handleApplyDataType);
    }

    /**
     * Handle GET /datatypes - List all data types
     */
    private void handleDataTypes(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
                return;
            }

            Program program = getProgram(exchange);
            if (program == null) {
                sendErrorResponse(exchange, 400, "No program loaded", "NO_PROGRAM_LOADED");
                return;
            }

            Map<String, String> params = parseQueryParams(exchange);
            int offset = parseIntOrDefault(params.get("offset"), 0);
            int limit = parseIntOrDefault(params.get("limit"), 100);
            String category = params.get("category");
            String kind = params.get("kind"); // struct, enum, union

            DataTypeManager dtm = program.getDataTypeManager();
            List<Map<String, Object>> dataTypes = new ArrayList<>();

            // Iterate through all data types
            Iterator<DataType> iterator = dtm.getAllDataTypes();
            while (iterator.hasNext()) {
                DataType dt = iterator.next();

                // Apply filters
                if (category != null && !dt.getCategoryPath().getPath().contains(category)) {
                    continue;
                }

                if (kind != null) {
                    boolean match = false;
                    if (kind.equals("struct") && dt instanceof Structure) match = true;
                    if (kind.equals("enum") && dt instanceof ghidra.program.model.data.Enum) match = true;
                    if (kind.equals("union") && dt instanceof Union) match = true;
                    if (!match) continue;
                }

                Map<String, Object> dtInfo = new HashMap<>();
                dtInfo.put("name", dt.getName());
                dtInfo.put("displayName", dt.getDisplayName());
                dtInfo.put("category", dt.getCategoryPath().getPath());
                dtInfo.put("length", dt.getLength());

                // Add type-specific information
                if (dt instanceof Structure) {
                    dtInfo.put("kind", "struct");
                    dtInfo.put("numComponents", ((Structure) dt).getNumComponents());
                } else if (dt instanceof ghidra.program.model.data.Enum) {
                    dtInfo.put("kind", "enum");
                    dtInfo.put("numValues", ((ghidra.program.model.data.Enum) dt).getCount());
                } else if (dt instanceof Union) {
                    dtInfo.put("kind", "union");
                    dtInfo.put("numComponents", ((Union) dt).getNumComponents());
                } else {
                    dtInfo.put("kind", "other");
                }

                dataTypes.add(dtInfo);
            }

            // Build response
            ResponseBuilder builder = new ResponseBuilder(exchange, port)
                    .success(true);

            // Apply pagination
            List<Map<String, Object>> paginated = applyPagination(
                    dataTypes, offset, limit, builder, "/datatypes",
                    buildQueryString(params));

            builder.result(paginated);
            builder.addLink("self", "/datatypes");
            builder.addLink("create_struct", "/datatypes/struct", "POST");
            builder.addLink("create_enum", "/datatypes/enum", "POST");
            builder.addLink("create_union", "/datatypes/union", "POST");

            sendJsonResponse(exchange, builder.build(), 200);

        } catch (Exception e) {
            Msg.error(this, "Error in /datatypes endpoint", e);
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage());
        }
    }

    /**
     * Handle POST /datatypes/struct - Create a new structure
     */
    private void handleCreateStruct(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
                return;
            }

            Program program = getProgram(exchange);
            if (program == null) {
                sendErrorResponse(exchange, 400, "No program loaded", "NO_PROGRAM_LOADED");
                return;
            }

            Map<String, String> params = parseJsonPostParams(exchange);
            String name = params.get("name");
            String fieldsJson = params.get("fields"); // JSON array of {name, type, size}
            String category = params.getOrDefault("category", "/");

            if (name == null || name.isEmpty()) {
                sendErrorResponse(exchange, 400, "Missing required parameter: name", "MISSING_PARAMETER");
                return;
            }

            try {
                Map<String, Object> result = TransactionHelper.executeInTransaction(
                        program, "Create struct " + name, () -> {
                            DataTypeManager dtm = program.getDataTypeManager();

                            // Create the structure
                            CategoryPath categoryPath = new CategoryPath(category);
                            Structure struct = new StructureDataType(categoryPath, name, 0, dtm);

                            // Parse and add fields if provided
                            int fieldsAdded = 0;
                            if (fieldsJson != null && !fieldsJson.isEmpty()) {
                                JsonArray fieldsArr = gson.fromJson(fieldsJson, JsonArray.class);
                                for (JsonElement el : fieldsArr) {
                                    JsonObject fieldObj = el.getAsJsonObject();
                                    String fieldName = fieldObj.get("name").getAsString();
                                    String fieldType = fieldObj.get("type").getAsString();
                                    int fieldSize = fieldObj.has("size") ? fieldObj.get("size").getAsInt() : -1;
                                    DataType fieldDt = resolveDataType(dtm, fieldType, fieldSize);
                                    struct.add(fieldDt, fieldDt.getLength(), fieldName, null);
                                    fieldsAdded++;
                                }
                            }

                            // Add to data type manager
                            DataType added = dtm.addDataType(struct, null);

                            // Build result
                            Map<String, Object> resultMap = new HashMap<>();
                            resultMap.put("name", added.getName());
                            resultMap.put("category", added.getCategoryPath().getPath());
                            resultMap.put("length", added.getLength());
                            resultMap.put("kind", "struct");
                            resultMap.put("fieldsAdded", fieldsAdded);

                            return resultMap;
                        });

                ResponseBuilder builder = new ResponseBuilder(exchange, port)
                        .success(true)
                        .result(result);

                builder.addLink("self", "/datatypes/struct");
                builder.addLink("datatypes", "/datatypes");

                sendJsonResponse(exchange, builder.build(), 201);

            } catch (Exception e) {
                Msg.error(this, "Error creating struct", e);
                sendErrorResponse(exchange, 500, "Failed to create struct: " + e.getMessage());
            }

        } catch (Exception e) {
            Msg.error(this, "Error in /datatypes/struct endpoint", e);
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage());
        }
    }

    /**
     * Handle POST /datatypes/enum - Create a new enumeration
     */
    private void handleCreateEnum(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
                return;
            }

            Program program = getProgram(exchange);
            if (program == null) {
                sendErrorResponse(exchange, 400, "No program loaded", "NO_PROGRAM_LOADED");
                return;
            }

            Map<String, String> params = parseJsonPostParams(exchange);
            String name = params.get("name");
            String valuesJson = params.get("values"); // JSON object of {name: value}
            String category = params.getOrDefault("category", "/");
            int size = parseIntOrDefault(params.get("size"), 4); // Default to 4 bytes

            if (name == null || name.isEmpty()) {
                sendErrorResponse(exchange, 400, "Missing required parameter: name", "MISSING_PARAMETER");
                return;
            }

            try {
                Map<String, Object> result = TransactionHelper.executeInTransaction(
                        program, "Create enum " + name, () -> {
                            DataTypeManager dtm = program.getDataTypeManager();

                            // Create the enum
                            CategoryPath categoryPath = new CategoryPath(category);
                            EnumDataType enumDt = new EnumDataType(categoryPath, name, size, dtm);

                            // Parse and add values if provided
                            int valuesAdded = 0;
                            if (valuesJson != null && !valuesJson.isEmpty()) {
                                JsonObject valuesObj = gson.fromJson(valuesJson, JsonObject.class);
                                for (Map.Entry<String, JsonElement> entry : valuesObj.entrySet()) {
                                    enumDt.add(entry.getKey(), entry.getValue().getAsLong());
                                    valuesAdded++;
                                }
                            }

                            // Add to data type manager
                            DataType added = dtm.addDataType(enumDt, null);

                            // Build result
                            Map<String, Object> resultMap = new HashMap<>();
                            resultMap.put("name", added.getName());
                            resultMap.put("category", added.getCategoryPath().getPath());
                            resultMap.put("length", added.getLength());
                            resultMap.put("kind", "enum");
                            resultMap.put("valuesAdded", valuesAdded);

                            return resultMap;
                        });

                ResponseBuilder builder = new ResponseBuilder(exchange, port)
                        .success(true)
                        .result(result);

                builder.addLink("self", "/datatypes/enum");
                builder.addLink("datatypes", "/datatypes");

                sendJsonResponse(exchange, builder.build(), 201);

            } catch (Exception e) {
                Msg.error(this, "Error creating enum", e);
                sendErrorResponse(exchange, 500, "Failed to create enum: " + e.getMessage());
            }

        } catch (Exception e) {
            Msg.error(this, "Error in /datatypes/enum endpoint", e);
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage());
        }
    }

    /**
     * Handle POST /datatypes/union - Create a new union
     */
    private void handleCreateUnion(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
                return;
            }

            Program program = getProgram(exchange);
            if (program == null) {
                sendErrorResponse(exchange, 400, "No program loaded", "NO_PROGRAM_LOADED");
                return;
            }

            Map<String, String> params = parseJsonPostParams(exchange);
            String name = params.get("name");
            String fieldsJson = params.get("fields"); // JSON array of {name, type}
            String category = params.getOrDefault("category", "/");

            if (name == null || name.isEmpty()) {
                sendErrorResponse(exchange, 400, "Missing required parameter: name", "MISSING_PARAMETER");
                return;
            }

            try {
                Map<String, Object> result = TransactionHelper.executeInTransaction(
                        program, "Create union " + name, () -> {
                            DataTypeManager dtm = program.getDataTypeManager();

                            // Create the union
                            CategoryPath categoryPath = new CategoryPath(category);
                            UnionDataType union = new UnionDataType(categoryPath, name, dtm);

                            // Parse and add fields if provided
                            int fieldsAdded = 0;
                            if (fieldsJson != null && !fieldsJson.isEmpty()) {
                                JsonArray fieldsArr = gson.fromJson(fieldsJson, JsonArray.class);
                                for (JsonElement el : fieldsArr) {
                                    JsonObject fieldObj = el.getAsJsonObject();
                                    String fieldName = fieldObj.get("name").getAsString();
                                    String fieldType = fieldObj.get("type").getAsString();
                                    int fieldSize = fieldObj.has("size") ? fieldObj.get("size").getAsInt() : -1;
                                    DataType fieldDt = resolveDataType(dtm, fieldType, fieldSize);
                                    union.add(fieldDt, fieldDt.getLength(), fieldName, null);
                                    fieldsAdded++;
                                }
                            }

                            // Add to data type manager
                            DataType added = dtm.addDataType(union, null);

                            // Build result
                            Map<String, Object> resultMap = new HashMap<>();
                            resultMap.put("name", added.getName());
                            resultMap.put("category", added.getCategoryPath().getPath());
                            resultMap.put("length", added.getLength());
                            resultMap.put("kind", "union");
                            resultMap.put("fieldsAdded", fieldsAdded);

                            return resultMap;
                        });

                ResponseBuilder builder = new ResponseBuilder(exchange, port)
                        .success(true)
                        .result(result);

                builder.addLink("self", "/datatypes/union");
                builder.addLink("datatypes", "/datatypes");

                sendJsonResponse(exchange, builder.build(), 201);

            } catch (Exception e) {
                Msg.error(this, "Error creating union", e);
                sendErrorResponse(exchange, 500, "Failed to create union: " + e.getMessage());
            }

        } catch (Exception e) {
            Msg.error(this, "Error in /datatypes/union endpoint", e);
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage());
        }
    }

    /**
     * Handle POST /datatypes/apply - Apply a data type at a memory address
     */
    private void handleApplyDataType(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
                return;
            }

            Program program = getProgram(exchange);
            if (program == null) {
                sendErrorResponse(exchange, 400, "No program loaded", "NO_PROGRAM_LOADED");
                return;
            }

            Map<String, String> params = parseJsonPostParams(exchange);
            String addressStr = params.get("address");
            String typeName = params.get("type_name");

            if (addressStr == null || addressStr.isEmpty()) {
                sendErrorResponse(exchange, 400, "Missing required parameter: address", "MISSING_PARAMETER");
                return;
            }
            if (typeName == null || typeName.isEmpty()) {
                sendErrorResponse(exchange, 400, "Missing required parameter: type_name", "MISSING_PARAMETER");
                return;
            }

            try {
                Map<String, Object> result = TransactionHelper.executeInTransaction(
                        program, "Apply data type " + typeName + " at " + addressStr, () -> {
                            DataTypeManager dtm = program.getDataTypeManager();
                            Address addr = program.getAddressFactory().getAddress(addressStr);
                            if (addr == null) {
                                throw new IllegalArgumentException("Invalid address: " + addressStr);
                            }

                            // Search for the data type by name
                            DataType dt = null;
                            Iterator<DataType> iter = dtm.getAllDataTypes();
                            while (iter.hasNext()) {
                                DataType candidate = iter.next();
                                if (candidate.getName().equals(typeName)) {
                                    dt = candidate;
                                    break;
                                }
                            }
                            if (dt == null) {
                                throw new IllegalArgumentException("Data type not found: " + typeName);
                            }

                            // Clear any existing data at the address and apply the type
                            Listing listing = program.getListing();
                            listing.clearCodeUnits(addr, addr.add(dt.getLength() - 1), false);
                            Data newData = listing.createData(addr, dt);

                            Map<String, Object> resultMap = new HashMap<>();
                            resultMap.put("address", addr.toString());
                            resultMap.put("type", newData.getDataType().getName());
                            resultMap.put("length", newData.getLength());
                            return resultMap;
                        });

                ResponseBuilder builder = new ResponseBuilder(exchange, port)
                        .success(true)
                        .result(result);

                builder.addLink("self", "/datatypes/apply");
                builder.addLink("datatypes", "/datatypes");

                sendJsonResponse(exchange, builder.build(), 200);

            } catch (Exception e) {
                Msg.error(this, "Error applying data type", e);
                sendErrorResponse(exchange, 500, "Failed to apply data type: " + e.getMessage());
            }

        } catch (Exception e) {
            Msg.error(this, "Error in /datatypes/apply endpoint", e);
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage());
        }
    }

    /**
     * Resolve a data type by name, handling common primitive types and falling back
     * to searching the program's data type manager.
     */
    private DataType resolveDataType(DataTypeManager dtm, String typeName, int size) {
        switch (typeName.toLowerCase()) {
            case "int":
                return new IntegerDataType();
            case "uint":
                return new UnsignedIntegerDataType();
            case "byte":
                return new ByteDataType();
            case "ubyte":
                return new UnsignedCharDataType();
            case "short":
                return new ShortDataType();
            case "ushort":
                return new UnsignedShortDataType();
            case "long":
                return new LongDataType();
            case "ulong":
                return new UnsignedLongDataType();
            case "longlong":
                return new LongLongDataType();
            case "ulonglong":
                return new UnsignedLongLongDataType();
            case "float":
                return new FloatDataType();
            case "double":
                return new DoubleDataType();
            case "char":
                return new CharDataType();
            case "bool":
                return new BooleanDataType();
            case "void":
                return new VoidDataType();
            case "pointer":
            case "void*":
            case "pvoid":
                return new PointerDataType();
            default:
                // Search the data type manager for a matching type
                Iterator<DataType> iter = dtm.getAllDataTypes();
                while (iter.hasNext()) {
                    DataType dt = iter.next();
                    if (dt.getName().equalsIgnoreCase(typeName)) {
                        return dt;
                    }
                }
                throw new IllegalArgumentException("Unknown data type: " + typeName);
        }
    }

    /**
     * Build query string from parameters
     */
    private String buildQueryString(Map<String, String> params) {
        StringBuilder query = new StringBuilder();

        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getKey().equals("offset") || entry.getKey().equals("limit")) {
                continue; // Skip pagination params
            }
            if (query.length() > 0) {
                query.append("&");
            }
            query.append(entry.getKey()).append("=").append(entry.getValue());
        }

        return query.toString();
    }
}
