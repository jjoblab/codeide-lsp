package jo.lspjava.services;

import jo.lspjava.api.*;
import jo.lspjava.engine.CancelToken;
import jo.lspjava.engine.CompilationEnvironment;
import jo.lspjava.engine.JdtEngineScheduler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service de diagnostics sémantiques pour Java.
 *
 * @author jo@Dev
 */
public final class DiagnosticService {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger("DiagnosticService");

    private final CompilationEnvironment environment;

    /**
     * Tokens d'annulation des passes de diagnostics EN VOL — un par fichier
     * au plus. Quand un nouveau didChange déclenche une passe pour un
     * fichier dont une passe est encore en cours (le diagnose ecj tient le
     * verrou resolveUnit), le token ANCIEN est annulé : sa résolution
     * s'interrompt au prochain checkpoint INameEnvironment
     * (OperationCanceledException) et libère le verrou quasi immédiatement —
     * sans cela, chaque diagnose en cours bloquerait les requêtes
     * interactives jusqu'à SA FIN (lag à la frappe).
     */
    private final ConcurrentHashMap<String, CancelToken> inflight = new ConcurrentHashMap<>();

    public DiagnosticService(CompilationEnvironment environment) {
        this.environment = environment;
    }

    /**
     * Diagnostics d'une unité source — compilateur ecj in-process.
     *
     * <p>Les bindings donnent la vraie sémantique du compilateur : résolution
     * de types contre le classpath du projet (Gradle Tooling), surcharges,
     * héritage, analyse de flux. Le Gradle Tooling reste le moteur de BUILD ;
     * ecj ne sert que l'édition.</p>
     *
     * <p><strong>Supersession coopérative :</strong> un appel pour un
     * fichier annule la passe PRÉCÉDENTE en vol sur ce même fichier (token
     * {@code prev.cancel()}), puis s'exécute sous son PROPRE token borné par
     * {@link JdtEngineScheduler#ANALYSIS_TIMEOUT_MS} — coupé aussi si sa
     * propre passe explose la borne temps (fichier pathologique). Complète la
     * supersession « file d'attente » assurée par le scheduler : une tâche
     * DÉJÀ DÉMARRÉE est elle aussi coupée.</p>
     *
     * @throws RuntimeException si le moteur ecj échoue (classpath vide,
     *         kill-switch) — l'appelant gère la dégradation.
     */
    public List<Diagnostic> diagnose(String filePath, String source) {
        var jdt = environment.jdt();
        if (!jdt.isLikelyUsable()) {
            return List.of();
        }
        try (CancelToken token = CancelToken.timeout(
                JdtEngineScheduler.ANALYSIS_TIMEOUT_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS)) {
            // Coupure COOPÉRATIVE de la passe antérieure en vol —
            // polling pur dans les checkpoints, jamais d'interruption du
            // thread worker (pas d'attachToCurrentThread ici).
            CancelToken prev = inflight.put(filePath, token);
            if (prev != null && prev != token) {
                prev.cancel();
            }
            try {
                return jdt.diagnose(filePath, source, token);
            } finally {
                inflight.remove(filePath, token);
            }
        } catch (jo.lspjava.engine.OperationCanceledException oc) {
            // Passe supersédée ou timeoutée — rien à publier ; la prochaine
            // publication reparsira sur le texte à jour.
            return List.of();
        } catch (Throwable t) {
            // On trace plutôt que d'avaler silencieusement (échec moteur ecj
            // — classpath vide, kill-switch, erreur interne).
            LOG.warning("diagnose échoué pour " + filePath + " : " + t);
            return List.of();
        }
    }
}
