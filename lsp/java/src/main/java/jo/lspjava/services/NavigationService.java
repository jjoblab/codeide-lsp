package jo.lspjava.services;

import jo.lspjava.api.NavigationTarget;
import jo.lspjava.api.TextRange;
import jo.lspjava.engine.CompilationEnvironment;
import jo.lspjava.engine.OperationCanceledException;
import jo.lspjava.ecj.EcjCompilerEngine;
import jo.lspjava.ecj.EcjNavigationEngine;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Service de navigation (aller à la définition) pour Java — bindings ecj.
 *
 * <p>Résolution exacte par bindings : surcharges, héritage, membres statiques
 * hérités — plus de recherche par nom. La définition est cherchée dans le
 * fichier courant.</p>
 *
 * <p>{@link #findTypeDefinition} est le menu contextuel unifié Go-to de
 * projet tiers de référence — « Type declaration » : le type du symbole au caret.</p>
 *
 * <p>{@link #findImplementations} et {@link #findSuper} couvrent les deux
 * dernières options GO TO du NavMenu du projet tiers de référence — multi-fichiers, via
 * le balayage des racines sources du projet (pattern renommage) et
 * l'overlay des tampons ouverts.</p>
 *
 * @author jo@Dev
 */
public final class NavigationService {

    private static final Logger LOG = Logger.getLogger("NavigationService");

    private final CompilationEnvironment environment;

    /** Moteur de navigation multi-fichiers (lazy — vit avec l'environnement). */
    private volatile EcjNavigationEngine navigationEngine;

    public NavigationService(CompilationEnvironment environment) {
        this.environment = environment;
    }

    /**
     * Trouve la définition du symbole à la position donnée.
     *
     * @return la plage de la déclaration, ou null
     */
    public TextRange findDefinition(String filePath, String source, int offset) {
        try {
            EcjCompilerEngine jdt = environment.jdt();
            if (!jdt.isLikelyUsable()) return null;
            return jdt.findDefinition(filePath, source, offset);
        } catch (OperationCanceledException e) {
            LOG.fine(() -> "findDefinition canceled for " + filePath + " at " + offset);
            return null;
        } catch (Throwable t) {
            // Log au lieu d'avaler silencieusement.
            LOG.log(java.util.logging.Level.WARNING,
                    "findDefinition failed for " + filePath + " at offset " + offset, t);
            return null;
        }
    }

    /**
     * Trouve la déclaration du TYPE du symbole à la position donnée
     * ({@code Foo x = …} → {@code class Foo}). Même fichier uniquement ; null
     * quand le type vient du classpath ou est un type de base.
     *
     * @return la plage du nom du type déclaré, ou null
     */
    public TextRange findTypeDefinition(String filePath, String source, int offset) {
        try {
            EcjCompilerEngine jdt = environment.jdt();
            if (!jdt.isLikelyUsable()) return null;
            return jdt.findTypeDefinition(filePath, source, offset);
        } catch (OperationCanceledException e) {
            LOG.fine(() -> "findTypeDefinition canceled for " + filePath + " at " + offset);
            return null;
        } catch (Throwable t) {
            LOG.log(java.util.logging.Level.WARNING,
                    "findTypeDefinition failed for " + filePath + " at offset " + offset, t);
            return null;
        }
    }

    /**
     * Les héritiers DIRECTS du type en contexte au caret (GO TO
     * « Implementations » du projet tiers de référence) : une référence de type sous le
     * caret, sinon la classe englobante. Cibles dans les SOURCES du projet
     * uniquement.
     *
     * @param overlay tampons ouverts (path → source vivante)
     * @return les cibles triées par libellé, jamais null
     */
    public List<NavigationTarget> findImplementations(String filePath, String source,
                                                       int offset,
                                                       Map<String, String> overlay) {
        try {
            EcjCompilerEngine jdt = environment.jdt();
            if (!jdt.isLikelyUsable()) return List.of();
            return navEngine().findImplementations(filePath, source, offset, overlay);
        } catch (OperationCanceledException e) {
            LOG.fine(() -> "findImplementations canceled for " + filePath + " at " + offset);
            return List.of();
        } catch (Throwable t) {
            LOG.log(java.util.logging.Level.WARNING,
                    "findImplementations failed for " + filePath + " at offset " + offset, t);
            return List.of();
        }
    }

    /**
     * Les cibles GO TO « Super » au caret : pour un membre qui
     * outrepasse, le même-nommé dans chaque supertype (label
     * {@code "name  ·  Super"}) ; sinon les supertypes DIRECTS du type en
     * contexte (label {@code "Simple  ·  pkg"}). Cibles dans les SOURCES du
     * projet uniquement.
     *
     * @param overlay tampons ouverts (path → source vivante)
     * @return les cibles, jamais null
     */
    public List<NavigationTarget> findSuper(String filePath, String source,
                                             int offset, Map<String, String> overlay) {
        try {
            EcjCompilerEngine jdt = environment.jdt();
            if (!jdt.isLikelyUsable()) return List.of();
            return navEngine().findSuper(filePath, source, offset, overlay);
        } catch (OperationCanceledException e) {
            LOG.fine(() -> "findSuper canceled for " + filePath + " at " + offset);
            return List.of();
        } catch (Throwable t) {
            LOG.log(java.util.logging.Level.WARNING,
                    "findSuper failed for " + filePath + " at offset " + offset, t);
            return List.of();
        }
    }

    /**
     * La DÉFINITION du symbole au caret dans un AUTRE fichier des
     * sources du projet (ADR 0209) : chemin JDT-first intra-fichier
     * d'abord ([#findDefinition]), CE chemin cross-fichiers ensuite,
     * fallback binaire en dernier (la façade orchestre l'ordre).
     *
     * @param overlay tampons ouverts (path → source vivante)
     * @return la cible (chemin + plage du nom), ou null
     */
    public EcjNavigationEngine.CibleDefinition findDefinitionProject(String filePath, String source,
                                                                     int offset,
                                                                     Map<String, String> overlay) {
        try {
            EcjCompilerEngine jdt = environment.jdt();
            if (!jdt.isLikelyUsable()) return null;
            return navEngine().findDefinitionProject(filePath, source, offset, overlay);
        } catch (OperationCanceledException e) {
            LOG.fine(() -> "findDefinitionProject canceled for " + filePath + " at " + offset);
            return null;
        } catch (Throwable t) {
            LOG.log(java.util.logging.Level.WARNING,
                    "findDefinitionProject failed for " + filePath + " at offset " + offset, t);
            return null;
        }
    }

    /**
     * La déclaration du TYPE du symbole au caret dans un AUTRE fichier
     * des sources du projet (ADR 0209) — même orchestration que
     * [#findDefinitionProject] pour la voie typeDefinition.
     *
     * @param overlay tampons ouverts (path → source vivante)
     * @return la cible (chemin + plage du nom), ou null
     */
    public EcjNavigationEngine.CibleDefinition findTypeDefinitionProject(String filePath, String source,
                                                                        int offset,
                                                                        Map<String, String> overlay) {
        try {
            EcjCompilerEngine jdt = environment.jdt();
            if (!jdt.isLikelyUsable()) return null;
            return navEngine().findTypeDefinitionProject(filePath, source, offset, overlay);
        } catch (OperationCanceledException e) {
            LOG.fine(() -> "findTypeDefinitionProject canceled for " + filePath + " at " + offset);
            return null;
        } catch (Throwable t) {
            LOG.log(java.util.logging.Level.WARNING,
                    "findTypeDefinitionProject failed for " + filePath + " at offset " + offset, t);
            return null;
        }
    }

    private EcjNavigationEngine navEngine() {
        EcjNavigationEngine e = navigationEngine;
        if (e == null) {
            synchronized (this) {
                e = navigationEngine;
                if (e == null) {
                    e = new EcjNavigationEngine(environment.jdt());
                    navigationEngine = e;
                }
            }
        }
        return e;
    }
}
