package eu.starsong.ghidra.service;

import eu.starsong.ghidra.util.GhidraUtil;
import eu.starsong.ghidra.util.TransactionHelper;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.DataType;
import ghidra.program.model.listing.CommentType;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.listing.Program;
import ghidra.program.model.symbol.SourceType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Batch mutations that apply many edits in a single program transaction.
 *
 * <p>Each item is attempted independently: a bad item is reported with a failure status
 * instead of aborting the whole batch, and the successful items commit together.
 */
public class BatchService {

    // Request items are plain classes (not records) so any Gson version Ghidra ships
    // can deserialize them.

    public static class RenameItem {
        public String address;
        public String old_name;
        public String new_name;
    }

    public static class CommentItem {
        public String address;
        public String comment;
        public String type;
    }

    public static class DefineDataItem {
        public String address;
        public String type;
        public String label;
        public Integer size;
    }

    /**
     * Rename functions located by address (preferred) or by current name.
     * New names may be fully qualified ("ns::name"), which moves the function.
     */
    public Map<String, Object> renameFunctions(Program program, List<RenameItem> renames) throws Exception {
        requireItems(renames, "renames");
        List<Map<String, Object>> results = TransactionHelper.executeInTransaction(program,
            "Batch rename functions", () -> {
                List<Map<String, Object>> out = new ArrayList<>();
                for (RenameItem item : renames) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    out.add(r);
                    if (item == null || isBlank(item.new_name)) {
                        r.put("status", "missing_new_name");
                        continue;
                    }
                    r.put("new_name", item.new_name);
                    try {
                        Function fn = null;
                        if (!isBlank(item.address)) {
                            r.put("address", item.address);
                            Address addr = GhidraUtil.resolveAddress(program, item.address);
                            if (addr != null) {
                                fn = program.getFunctionManager().getFunctionAt(addr);
                            }
                        }
                        if (fn == null && !isBlank(item.old_name)) {
                            r.put("old_name", item.old_name);
                            fn = GhidraUtil.findFunctionByName(program, item.old_name);
                        }
                        if (fn == null) {
                            r.put("status", "not_found");
                            continue;
                        }
                        String original = fn.getName(true);
                        GhidraUtil.applyQualifiedName(program, fn.getSymbol(), item.new_name, SourceType.USER_DEFINED);
                        r.put("address", fn.getEntryPoint().toString());
                        r.put("original_name", original);
                        r.put("status", "renamed");
                    } catch (Exception e) {
                        r.put("status", "failed");
                        r.put("error", e.getMessage());
                    }
                }
                return out;
            });
        return summarize(results, "renamed");
    }

    /**
     * Set comments at many addresses. An item's own "type" wins over the batch default.
     */
    public Map<String, Object> setComments(Program program, List<CommentItem> comments, String defaultType)
            throws Exception {
        requireItems(comments, "comments");
        String fallbackType = isBlank(defaultType) ? "eol" : defaultType;
        List<Map<String, Object>> results = TransactionHelper.executeInTransaction(program,
            "Batch set comments", () -> {
                Listing listing = program.getListing();
                List<Map<String, Object>> out = new ArrayList<>();
                for (CommentItem item : comments) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    out.add(r);
                    if (item == null || isBlank(item.address)) {
                        r.put("status", "missing_address");
                        continue;
                    }
                    r.put("address", item.address);
                    String typeName = isBlank(item.type) ? fallbackType : item.type;
                    r.put("type", typeName);
                    CommentType commentType = parseCommentType(typeName);
                    if (commentType == null) {
                        r.put("status", "invalid_type");
                        continue;
                    }
                    Address addr = GhidraUtil.resolveAddress(program, item.address);
                    if (addr == null) {
                        r.put("status", "invalid_address");
                        continue;
                    }
                    try {
                        listing.setComment(addr, commentType, item.comment);
                        r.put("status", "set");
                    } catch (Exception e) {
                        r.put("status", "failed");
                        r.put("error", e.getMessage());
                    }
                }
                return out;
            });
        return summarize(results, "set");
    }

    /**
     * Define typed data (and optionally a label) at many addresses.
     */
    public Map<String, Object> defineData(Program program, List<DefineDataItem> items) throws Exception {
        requireItems(items, "items");
        List<Map<String, Object>> results = TransactionHelper.executeInTransaction(program,
            "Batch define data", () -> {
                Listing listing = program.getListing();
                List<Map<String, Object>> out = new ArrayList<>();
                for (DefineDataItem item : items) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    out.add(r);
                    if (item == null || isBlank(item.address) || isBlank(item.type)) {
                        r.put("status", "missing_address_or_type");
                        continue;
                    }
                    r.put("address", item.address);
                    r.put("type", item.type);
                    Address addr = GhidraUtil.resolveAddress(program, item.address);
                    if (addr == null) {
                        r.put("status", "invalid_address");
                        continue;
                    }
                    DataType dataType = GhidraUtil.resolveDataType(program, item.type);
                    if (dataType == null) {
                        r.put("status", "unknown_type");
                        continue;
                    }
                    int length = item.size != null && item.size > 0 ? item.size : dataType.getLength();
                    if (length <= 0) {
                        r.put("status", "size_required");
                        continue;
                    }
                    try {
                        listing.clearCodeUnits(addr, addr.add(length - 1), false);
                        Data data = listing.createData(addr, dataType, length);
                        if (!isBlank(item.label)) {
                            GhidraUtil.createLabelWithName(program, addr, item.label, SourceType.USER_DEFINED);
                            r.put("label", item.label);
                        }
                        r.put("size", data.getLength());
                        r.put("status", "defined");
                    } catch (Exception e) {
                        r.put("status", "failed");
                        r.put("error", e.getMessage());
                    }
                }
                return out;
            });
        return summarize(results, "defined");
    }

    private static CommentType parseCommentType(String s) {
        return switch (s.toLowerCase()) {
            case "plate" -> CommentType.PLATE;
            case "pre" -> CommentType.PRE;
            case "post" -> CommentType.POST;
            case "eol" -> CommentType.EOL;
            case "repeatable" -> CommentType.REPEATABLE;
            default -> null;
        };
    }

    private static Map<String, Object> summarize(List<Map<String, Object>> results, String successStatus) {
        long ok = results.stream().filter(r -> successStatus.equals(r.get("status"))).count();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", results.size());
        summary.put("successful", ok);
        summary.put("failed", results.size() - ok);
        summary.put("results", results);
        return summary;
    }

    private static void requireItems(List<?> items, String field) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Missing or empty '" + field + "' array");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }
}
