package br.ars.user_service.queue;

import org.springframework.stereotype.Service;

import java.util.concurrent.*;
import java.util.function.Supplier;

@Service
public class DbRequestQueueService {

    private final BlockingQueue<Runnable> queue;
    private final ExecutorService dbExecutor;

    public DbRequestQueueService() {
        int workers = 10; // igual ao Hikari pool
        this.queue = new ArrayBlockingQueue<>(5000);
        this.dbExecutor = new ThreadPoolExecutor(
                workers,
                workers,
                0L,
                TimeUnit.MILLISECONDS,
                queue
        );
    }

    public <T> CompletableFuture<T> submit(Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            dbExecutor.submit(() -> {
                try {
                    T result = task.get();
                    future.complete(result);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(new RuntimeException("Fila cheia, tente novamente mais tarde."));
        }
        return future;
    }

    public int getQueueSize() {
        return queue.size();
    }
}
