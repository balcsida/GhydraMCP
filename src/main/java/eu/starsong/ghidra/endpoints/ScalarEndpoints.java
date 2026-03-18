package eu.starsong.ghidra.endpoints;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.starsong.ghidra.api.ResponseBuilder;
import eu.starsong.ghidra.model.ScalarInfo;
import ghidra.framework.plugintool.PluginTool;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.scalar.Scalar;
import ghidra.util.Msg;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ScalarEndpoints extends AbstractEndpoint {

    private PluginTool tool;

    public ScalarEndpoints(Program program, int port, PluginTool tool) {
        super(program, port);
        this.tool = tool;
    }

    @Override
    protected PluginTool getTool() {
        return tool;
    }

    @Override
    public void registerEndpoints(HttpServer server) {
        server.createContext("/scalars", this::handleScalarsRequest);
    }

    private void handleScalarsRequest(HttpExchange exchange) throws IOException {
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
            String valueStr = params.get("value");
            if (valueStr == null || valueStr.isEmpty()) {
                sendErrorResponse(exchange, 400, "value parameter is required (hex with 0x prefix or decimal)", "MISSING_PARAMETER");
                return;
            }

            // Parse value: handle 0x prefix for hex, -0x for negative hex, plain decimal
            long targetValue;
            try {
                targetValue = parseScalarValue(valueStr);
            } catch (NumberFormatException e) {
                sendErrorResponse(exchange, 400, "Invalid value format: " + valueStr, "INVALID_PARAMETER");
                return;
            }

            String inFunctionFilter = params.get("in_function");
            String toFunctionFilter = params.get("to_function");
            int offset = parseIntOrDefault(params.get("offset"), 0);
            int limit = parseIntOrDefault(params.get("limit"), 100);

            FunctionManager funcMgr = program.getFunctionManager();
            MemoryBlock[] blocks = program.getMemory().getBlocks();
            List<Map<String, Object>> results = new ArrayList<>();

            for (MemoryBlock block : blocks) {
                if (!block.isInitialized()) {
                    continue;
                }

                InstructionIterator instrIter = program.getListing().getInstructions(block.getStart(), true);
                while (instrIter.hasNext()) {
                    Instruction instr = instrIter.next();
                    // Stop if we've gone past this block
                    if (instr.getAddress().compareTo(block.getEnd()) > 0) {
                        break;
                    }

                    int numOperands = instr.getNumOperands();
                    for (int i = 0; i < numOperands; i++) {
                        Object[] opObjects = instr.getOpObjects(i);
                        for (Object obj : opObjects) {
                            if (obj instanceof Scalar) {
                                Scalar scalar = (Scalar) obj;
                                if (scalar.getValue() != targetValue && scalar.getSignedValue() != targetValue) {
                                    continue;
                                }

                                // Check containing function
                                Function containingFunc = funcMgr.getFunctionContaining(instr.getAddress());
                                if (inFunctionFilter != null && !inFunctionFilter.isEmpty()) {
                                    if (containingFunc == null ||
                                        !containingFunc.getName().toLowerCase().contains(inFunctionFilter.toLowerCase())) {
                                        continue;
                                    }
                                }

                                // Check target function (scan ahead up to 10 instructions for a CALL)
                                Function targetFunc = null;
                                if (toFunctionFilter != null && !toFunctionFilter.isEmpty()) {
                                    targetFunc = findNextCallTarget(program, instr);
                                    if (targetFunc == null ||
                                        !targetFunc.getName().toLowerCase().contains(toFunctionFilter.toLowerCase())) {
                                        continue;
                                    }
                                } else {
                                    // Still try to resolve target function for context
                                    targetFunc = findNextCallTarget(program, instr);
                                }

                                // Build ScalarInfo
                                ScalarInfo.Builder builder = ScalarInfo.builder()
                                    .address(instr.getAddress().toString())
                                    .value(scalar.getValue())
                                    .hexValue("0x" + Long.toHexString(scalar.getUnsignedValue()))
                                    .bitLength(scalar.bitLength())
                                    .signed(scalar.isSigned())
                                    .operandIndex(i)
                                    .instruction(instr.getMnemonicString() + " " +
                                        instr.toString().substring(instr.getMnemonicString().length()).trim());

                                if (containingFunc != null) {
                                    builder.inFunction(containingFunc.getName())
                                           .inFunctionAddress(containingFunc.getEntryPoint().toString());
                                }
                                if (targetFunc != null) {
                                    builder.toFunction(targetFunc.getName())
                                           .toFunctionAddress(targetFunc.getEntryPoint().toString());
                                }

                                results.add(builder.build().toMap());
                            }
                        }
                    }
                }
            }

            // Build query params for pagination links
            StringBuilder queryParams = new StringBuilder("value=" + valueStr);
            if (inFunctionFilter != null) queryParams.append("&in_function=").append(inFunctionFilter);
            if (toFunctionFilter != null) queryParams.append("&to_function=").append(toFunctionFilter);

            ResponseBuilder responseBuilder = new ResponseBuilder(exchange, port)
                .success(true)
                .addLink("root", "/")
                .addLink("memory", "/memory");

            List<Map<String, Object>> paginated = applyPagination(results, offset, limit,
                responseBuilder, "/scalars", queryParams.toString());

            responseBuilder.result(paginated);
            sendJsonResponse(exchange, responseBuilder.build(), 200);

        } catch (Exception e) {
            Msg.error(this, "Error in /scalars endpoint", e);
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    /**
     * Parse a scalar value string supporting hex (0x...), negative hex (-0x...), and decimal.
     */
    private long parseScalarValue(String valueStr) {
        valueStr = valueStr.trim();
        if (valueStr.startsWith("-0x") || valueStr.startsWith("-0X")) {
            return -Long.parseUnsignedLong(valueStr.substring(3), 16);
        } else if (valueStr.startsWith("0x") || valueStr.startsWith("0X")) {
            return Long.parseUnsignedLong(valueStr.substring(2), 16);
        } else {
            return Long.parseLong(valueStr);
        }
    }

    /**
     * Scan up to 10 instructions ahead from the given instruction looking for a CALL flow reference,
     * and return the called function if found.
     */
    private Function findNextCallTarget(Program program, Instruction startInstr) {
        Instruction current = startInstr;
        for (int i = 0; i < 10 && current != null; i++) {
            // Check for CALL-type flow references
            for (ghidra.program.model.symbol.Reference ref : current.getReferencesFrom()) {
                if (ref.getReferenceType().isCall()) {
                    Function target = program.getFunctionManager().getFunctionAt(ref.getToAddress());
                    if (target != null) {
                        return target;
                    }
                }
            }
            try {
                current = program.getListing().getInstructionAfter(current.getAddress());
            } catch (Exception e) {
                break;
            }
        }
        return null;
    }
}
