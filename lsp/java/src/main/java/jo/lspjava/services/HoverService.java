package jo.lspjava.services;

import jo.lspjava.engine.CompilationEnvironment;
import jo.lspjava.engine.OperationCanceledException;
import jo.lspjava.jdt.JdtCompilerEngine;

import java.util.logging.Logger;

/**
 * Service d'information au survol (hover) pour Java — bindings ecj.
 *
 * <p>Le hover affiche la signature exacte du symbole sous le caret :
 * méthodes (paramètres + type de retour, génériques substitués), champs,
 * variables locales, types (avec leur nature), paquets.</p>
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
     * Hover au offset — bindings ecj.
     *
     * @return le markdown du hover, ou null (rien à afficher).
     */
    public String hoverAt(String filePath, String source, int offset) {
        try {
            JdtCompilerEngine jdt = environment.jdt();
            if (!jdt.isLikelyUsable()) return null;
            return jdt.hoverAt(filePath, source, offset);
        } catch (OperationCanceledException e) {
            // Annulation — cas normal, log en FINE seulement.
            LOG.fine(() -> "hover canceled for " + filePath + " at " + offset);
            return null;
        } catch (Throwable t) {
            // Ne pas avaler silencieusement : sans log, opacité totale en
            // production.
            LOG.log(java.util.logging.Level.WARNING,
                    "hoverAt failed for " + filePath + " at offset " + offset, t);
            return null;
        }
    }
}
