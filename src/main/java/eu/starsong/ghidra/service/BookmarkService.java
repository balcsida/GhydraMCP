package eu.starsong.ghidra.service;

import eu.starsong.ghidra.server.GhydraServer.NotFoundException;
import eu.starsong.ghidra.util.GhidraSwing;
import eu.starsong.ghidra.util.GhidraUtil;
import eu.starsong.ghidra.util.TransactionHelper;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Bookmark;
import ghidra.program.model.listing.BookmarkManager;
import ghidra.program.model.listing.Program;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for bookmark operations.
 */
public class BookmarkService {

    public static final String DEFAULT_TYPE = "Note";

    /**
     * List all bookmarks, optionally restricted to one bookmark type.
     */
    public List<Map<String, Object>> list(Program program, String type) {
        BookmarkManager manager = program.getBookmarkManager();
        return GhidraSwing.runRead(() -> {
            Iterator<Bookmark> it = (type == null || type.isEmpty())
                ? manager.getBookmarksIterator()
                : manager.getBookmarksIterator(type);
            List<Map<String, Object>> bookmarks = new ArrayList<>();
            while (it.hasNext()) {
                bookmarks.add(toMap(it.next()));
            }
            return bookmarks;
        });
    }

    /**
     * Create (or replace) the bookmark of the given type and category at an address.
     */
    public Map<String, Object> create(Program program, String addressStr, String type,
                                      String category, String comment) throws Exception {
        Address address = requireAddress(program, addressStr);
        String bmType = (type == null || type.isEmpty()) ? DEFAULT_TYPE : type;
        String bmCategory = category != null ? category : "";
        String bmComment = comment != null ? comment : "";

        return TransactionHelper.executeInTransaction(program, "Create bookmark at " + address, () -> {
            Bookmark bm = program.getBookmarkManager().setBookmark(address, bmType, bmCategory, bmComment);
            return toMap(bm);
        });
    }

    /**
     * Delete the bookmark(s) of the given type at an address.
     */
    public Map<String, Object> delete(Program program, String addressStr, String type) throws Exception {
        Address address = requireAddress(program, addressStr);
        String bmType = (type == null || type.isEmpty()) ? DEFAULT_TYPE : type;

        int removed = TransactionHelper.executeInTransaction(program, "Delete bookmark at " + address, () -> {
            BookmarkManager manager = program.getBookmarkManager();
            int count = 0;
            for (Bookmark bm : manager.getBookmarks(address, bmType)) {
                manager.removeBookmark(bm);
                count++;
            }
            return count;
        });

        if (removed == 0) {
            throw new NotFoundException(
                "No bookmark of type '" + bmType + "' found at " + addressStr, "BOOKMARK_NOT_FOUND");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("address", address.toString());
        result.put("type", bmType);
        result.put("deleted", removed);
        return result;
    }

    private static Address requireAddress(Program program, String addressStr) {
        if (addressStr == null || addressStr.isEmpty()) {
            throw new IllegalArgumentException("address is required");
        }
        Address address = GhidraUtil.resolveAddress(program, addressStr);
        if (address == null) {
            throw new IllegalArgumentException("Invalid address: " + addressStr);
        }
        return address;
    }

    private static Map<String, Object> toMap(Bookmark bm) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("address", bm.getAddress().toString());
        entry.put("type", bm.getTypeString());
        entry.put("category", bm.getCategory());
        entry.put("comment", bm.getComment());
        return entry;
    }
}
