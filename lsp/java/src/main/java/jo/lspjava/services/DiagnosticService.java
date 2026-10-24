package jo.lspjava.services;

import jo.lspjava.api.*;
import jo.lspjava.engine.CancelToken;
import jo.lspjava.engine.CompilationEnvironment;
import jo.lspjava.engine.EcjEngineScheduler;

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
     * {@link EcjEngineScheduler#ANALYSIS_TIMEOUT_MS} — coupé aussi si sa
     * propre passe explose la borne temps (fichier pathologique). Complète la
     * supersession « file d'attente » assurée par le scheduler : une tâche
     * DÉJÀ DÉMARRÉE est elle aussi coupée.</p>
     *
     * <p><strong>Lot h4 « LSP instantané » (v0.101.0) :</strong> quand la
     * plateforme manque (avant la première sync, jrt absent —
     * {@code isLikelyUsable()} faux), la SYNTAXE SEULE est publiée (parse
     * sans résolution, filtre bit {@code IProblem.Syntax}) au lieu d'un
     * silence : accolades non fermées et point-virgules manquants sont
     * signalés DÈS le didOpen. Le live-republish du ContextListener
     * remplace ces diagnostics de repli par le régime plein dès que le
     * classpath arrive — la montée en régime est automatique.</p>
     *
     * @throws RuntimeException si le moteur ecj échoue (classpath vide,
     *         kill-switch) — l'appelant gère la dégradation.
     */
    public List<Diagnostic> diagnose(String filePath, String source) {
        // ADR 0215 (découverte D6) : moteur de SON module — un type du
        // même paquet (sans import) ne résout que sur les RACINES
        // SOURCES du module, qui ne vivent que dans SON contexte.
        var jdt = environment.jdtPourFichier(filePath);
        if (!jdt.isLikelyUsable()) {
            // Lot h4 : mode instantané — la syntaxe seule plutôt que le
            // silence (le régime plein remplace à la montée de plateforme).
            return jdt.diagnoseSyntax(filePath, source);
        }
        try (CancelToken token = CancelToken.timeout(
                EcjEngineScheduler.ANALYSIS_TIMEOUT_MS,
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
