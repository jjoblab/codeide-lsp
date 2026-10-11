package jo.lspjava.engine;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests du token d'annulation coopératif ({@link CancelToken}).
 *
 * @author jo@Dev
 */
class CancelTokenTest {

    @Test
    void neverTokenIsNeverCancelled() {
        assertFalse(CancelToken.NEVER.isCancelled(),
                "Le token NEVER ne doit jamais être annulé");
    }

    @Test
    void manualTokenStartsNotCancelled() {
        try (CancelToken t = CancelToken.manual()) {
            assertFalse(t.isCancelled());
            t.cancel();
            assertTrue(t.isCancelled(), "cancel() doit marquer le token comme annulé");
            // Idempotent : un deuxième check reste true.
            assertTrue(t.isCancelled());
        }
    }

    @Test
    void timeoutTokenFiresAfterDeadline() throws InterruptedException {
        // Token à 50 ms — doit s'annuler après ce délai.
        try (CancelToken t = CancelToken.timeout(50, TimeUnit.MILLISECONDS)) {
            assertFalse(t.isCancelled(), "Token frais — pas encore annulé");
            Thread.sleep(120);
            assertTrue(t.isCancelled(),
                    "Après le timeout, le token doit être annulé");
        }
    }

    @Test
    void cancelInterruptsAttachedThread() throws InterruptedException {
        try (CancelToken t = CancelToken.manual()) {
            t.attachToCurrentThread();
            AtomicReference<Boolean> wasInterrupted = new AtomicReference<>(false);
            Thread worker = new Thread(() -> {
                t.attachToCurrentThread();
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    wasInterrupted.set(true);
                }
            });
            worker.start();
            // Annule après 100 ms — le sleep doit être interrompu.
            Thread.sleep(100);
            t.cancel();
            worker.join(500);
            assertFalse(worker.isAlive(), "Le thread worker doit s'arrêter après cancel()");
            // Le thread principal a aussi reçu interrupt() — on clear le flag.
            Thread.interrupted();
        }
    }

    @Test
    void closeClearsWorkerReference() {
        CancelToken t = CancelToken.manual();
        t.attachToCurrentThread();
        t.close();
        // Pas de fuite — close() ne doit pas planter.
        assertFalse(t.isCancelled(), "close() ne déclenche pas cancel()");
    }
}
