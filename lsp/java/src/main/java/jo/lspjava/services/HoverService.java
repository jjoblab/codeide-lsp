package jo.lspjava.services;

import jo.lspjava.engine.CompilationEnvironment;
import jo.lspjava.engine.OperationCanceledException;
import jo.lspjava.ecj.EcjCompilerEngine;

import java.util.logging.Logger;

/**
 * Service d'information au survol (hover) pour Java — bindings ecj.
 *
 * <p>Le survol RICHE (S2-a) porte la signature exacte du symbole sous le
 * caret (méthodes — paramètres + type de retour, génériques substitués —,
 * champs, variables locales, types avec leur nature, paquets) PLUS la
 * javadoc nettoyée de sa déclaration quand elle vit dans la même unité de
 * compilation. Le format LSP (PLAINTEXT signature seule / MARKDOWN
 * signature + documentation) est choisi par le handler.</p>
 *
 * @author jo@Dev
 */
public final class HoverService {

    private static final Logger LOG = Logger.getLogger("HoverService");

    private final CompilationEnvironment environment;

    public HoverService(CompilationEnvironment environment) {
        this.environment = environment;
    }

    /**
     * Survol riche au offset — bindings ecj.
     *
     * @return la signature (+ documentation si disponible), ou null (rien à
     *         afficher).
     */
    public EcjCompilerEngine.SurvolRiche survolRicheAt(
            String filePath, String source, int offset) {
        try {
            EcjCompilerEngine jdt = environment.jdtPourFichier(filePath);
            if (!jdt.isLikelyUsable()) return null;
            return jdt.hoverRicheAt(filePath, source, offset);
        } catch (OperationCanceledException e) {
            // Annulation — cas normal, log en FINE seulement.
            LOG.fine(() -> "survol riche annulé pour " + filePath + " at " + offset);
            return null;
        } catch (Throwable t) {
            // Ne pas avaler silencieusement : sans log, opacité totale en
            // production.
            LOG.log(java.util.logging.Level.WARNING,
                    "survolRicheAt failed for " + filePath + " at offset " + offset, t);
            return null;
        }
    }
}
