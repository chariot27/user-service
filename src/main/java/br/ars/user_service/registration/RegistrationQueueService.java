package br.ars.user_service.registration;

import br.ars.user_service.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationQueueService {

    private final UserService userService;

    private final BlockingQueue<RegistrationCommand> queue = new LinkedBlockingQueue<>(500);
    private final ExecutorService workers = Executors.newFixedThreadPool(2); // ajuste se precisar

    @PostConstruct
    void startWorkers() {
        for (int i = 0; i < 2; i++) {
            workers.submit(this::loop);
        }
        log.info("[RegQueue] Workers iniciados.");
    }

    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                RegistrationCommand cmd = queue.take();
                log.info("[RegQueue] Consumindo item da fila. hasAvatarBytes={}", cmd.getAvatarBytes() != null);

                userService.register(
                        cmd.getRequest(),
                        cmd.getAvatarBytes(),
                        cmd.getFilename(),
                        cmd.getContentType()
                );

                log.info("[RegQueue] Registro processado com sucesso para email={}", cmd.getRequest().getEmail());
            } catch (Exception ex) {
                log.error("[RegQueue] Erro processando registro: {}", ex.getMessage(), ex);
            }
        }
    }

    public boolean offer(RegistrationCommand cmd) {
        boolean ok = queue.offer(cmd);
        if (!ok) log.warn("[RegQueue] Queue cheia ao tentar offer.");
        return ok;
    }

    public int queueSize() { return queue.size(); }
}
