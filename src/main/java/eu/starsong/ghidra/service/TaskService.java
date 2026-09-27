package eu.starsong.ghidra.service;

import eu.starsong.ghidra.dto.DecompileResultDto;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.util.Msg;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Registry of background tasks (currently asynchronous decompilation) that clients poll
 * for completion instead of holding an HTTP request open.
 *
 * <p>One instance per plugin: its worker threads are stopped in {@link #dispose()}.
 * Finished tasks whose result is never fetched are evicted after {@link #RETENTION}.
 */
public class TaskService {

    public static final String PENDING = "pending";
    public static final String RUNNING = "running";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";

    private static final Duration RETENTION = Duration.ofHours(1);

    private final Map<String, AsyncTask> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executor;

    public TaskService() {
        AtomicInteger threadCount = new AtomicInteger();
        int workers = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        this.executor = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "ghydra-async-" + threadCount.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * A background task and its outcome.
     */
    public static class AsyncTask {
        public final String id;
        public final String kind;
        public final String functionName;
        public final String functionAddress;
        public final Instant createdAt = Instant.now();
        volatile String status = PENDING;
        volatile String result;
        volatile String error;
        volatile Instant finishedAt;

        AsyncTask(String kind, String functionName, String functionAddress) {
            this.id = UUID.randomUUID().toString();
            this.kind = kind;
            this.functionName = functionName;
            this.functionAddress = functionAddress;
        }

        public String status() { return status; }
        public String result() { return result; }
        public String error() { return error; }

        public boolean isFinished() {
            return COMPLETED.equals(status) || FAILED.equals(status);
        }

        public Map<String, Object> describe() {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("task_id", id);
            info.put("kind", kind);
            info.put("status", status);
            info.put("function", functionName);
            info.put("address", functionAddress);
            info.put("created_at", createdAt.toString());
            if (finishedAt != null) {
                info.put("finished_at", finishedAt.toString());
            }
            if (error != null) {
                info.put("error", error);
            }
            return info;
        }
    }

    /**
     * Queue a decompilation of {@code function} and return its task immediately.
     */
    public AsyncTask submitDecompile(DecompilerService decompilerService, Program program,
                                     Function function, int timeout) {
        evictExpired();
        AsyncTask task = new AsyncTask("decompile", function.getName(true),
            function.getEntryPoint().toString());
        tasks.put(task.id, task);

        executor.submit(() -> {
            task.status = RUNNING;
            try {
                DecompileResultDto dto = decompilerService.decompileFunction(program, function, timeout);
                if (dto.success()) {
                    task.result = dto.decompilation();
                    task.status = COMPLETED;
                } else {
                    task.error = dto.errorMessage() != null ? dto.errorMessage() : "Decompilation returned no result";
                    task.status = FAILED;
                }
            } catch (Exception e) {
                Msg.error(this, "Async decompilation failed for " + task.functionName, e);
                task.error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                task.status = FAILED;
            } finally {
                task.finishedAt = Instant.now();
            }
        });
        return task;
    }

    public AsyncTask get(String id) {
        return id == null ? null : tasks.get(id);
    }

    public void remove(String id) {
        tasks.remove(id);
    }

    /** Stop the worker threads and forget all tasks. Call on plugin shutdown. */
    public void dispose() {
        executor.shutdownNow();
        tasks.clear();
    }

    private void evictExpired() {
        Instant cutoff = Instant.now().minus(RETENTION);
        tasks.values().removeIf(t -> t.finishedAt != null && t.finishedAt.isBefore(cutoff));
    }
}
