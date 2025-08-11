package br.ars.user_service.config;

import br.ars.user_service.registration.RegistrationCommand;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class RegistrationExecutorsConfig {

    @Bean
    public BlockingQueue<RegistrationCommand> registrationQueue(
            @Value("${app.registration.queue.capacity:20000}") int capacity) {
        return new ArrayBlockingQueue<>(capacity);
    }

    @Bean(name = "hashPool")
    public ThreadPoolExecutor hashPool(@Value("${REG_CPU_WORKERS:8}") int workers) {
        return new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(20000), new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(name = "dbPool")
    public ThreadPoolExecutor dbPool(@Value("${REG_DB_WORKERS:10}") int workers) {
        return (ThreadPoolExecutor) Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r);
            t.setName("reg-db-" + t.getId());
            t.setDaemon(true);
            return t;
        });
    }

    @Bean(name = "ioPool")
    public ThreadPoolExecutor ioPool(@Value("${REG_IO_WORKERS:32}") int workers) {
        return (ThreadPoolExecutor) Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r);
            t.setName("reg-io-" + t.getId());
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    public Semaphore dbGate(@Value("${REG_DB_WORKERS:10}") int permits) {
        return new Semaphore(permits); // = hikari pool
    }
}
