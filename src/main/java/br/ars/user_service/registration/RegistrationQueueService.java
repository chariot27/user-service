package br.ars.user_service.registration;

import br.ars.user_service.dto.RegisterRequest;
import br.ars.user_service.service.UserService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Fila de registros que usa o método síncrono UserService.register(...)
 * como base de processamento. Mantém backpressure (fila limitada),
 * concorrência controlada (dbPool + dbGate) e retries leves.
 */
@Service
public class RegistrationQueueService {

    private final BlockingQueue<RegistrationCommand> queue;
    private final ThreadPoolExecutor dbPool; // reusa o pool “DB” para controlar concorrência
    private final Semaphore dbGate;          // = tamanho do pool / Hikari
    private final UserService userService;
    private final int maxRetries;
    private final long backoffMs;

    public RegistrationQueueService(
            BlockingQueue<RegistrationCommand> queue,
            @Qualifier("dbPool") ThreadPoolExecutor dbPool,
            Semaphore dbGate,
            UserService userService,
            @Value("${app.registration.max-retries:3}") int maxRetries,
            @Value("${app.registration.retry-backoff-ms:200}") long backoffMs) {
        this.queue = queue;
        this.dbPool = dbPool;
        this.dbGate = dbGate;
        this.userService = userService;
        this.maxRetries = maxRetries;
        this.backoffMs = backoffMs;
    }

    /** Enfileira sem bloquear. Retorna false se fila cheia (backpressure). */
    public boolean offer(RegistrationCommand cmd) {
        return queue.offer(cmd);
    }

    @PostConstruct
    void startFeeder() {
        // Único feeder que drena a fila e despacha para o pool
        Thread feeder = new Thread(this::consumeLoop, "reg-feeder");
        feeder.setDaemon(true);
        feeder.start();
    }

    private void consumeLoop() {
        while (true) {
            try {
                RegistrationCommand cmd = queue.take();
                dbPool.submit(() -> processWithRetries(cmd));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Executa o registro usando UserService.register(...) com retries e semáforo. */
    private void processWithRetries(RegistrationCommand cmd) {
        RegisterRequest req = cmd.request();
        MultipartFile avatar = cmd.avatar();

        int attempt = 0;
        while (true) {
            try {
                // Idempotência rápida: se já existe, encerra sem erro
                if (userService.findByEmail(req.getEmail()).isPresent()) {
                    return;
                }

                // Controla concorrência (alinha com Hikari)
                dbGate.acquire();
                try {
                    // Usa seu método original como base
                    userService.register(req, avatar);
                    return; // sucesso
                } finally {
                    dbGate.release();
                }

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;

            } catch (IllegalArgumentException iae) {
                // Caso clássico: "Email já cadastrado." do próprio register(...)
                String msg = iae.getMessage() != null ? iae.getMessage().toLowerCase() : "";
                if (msg.contains("email já cadastrado")) return; // idempotente
                // Outros IllegalArgumentException (ex.: senha vazia) não fazem sentido retry
                return;

            } catch (RuntimeException ex) {
                // Falhas transitórias (DB/CDN) → retry exponencial leve
                if (++attempt >= maxRetries) {
                    // aqui você pode logar com requestId pra rastrear
                    return;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(backoffMs * attempt);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    // Métricas simples
    public int queueSize() { return queue.size(); }
    public int activeDb() { return dbPool.getActiveCount(); }
}
