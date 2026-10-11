package jo.lspjava.jdt;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.internal.compiler.ASTVisitor;
import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.Argument;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.ConstructorDeclaration;
import org.eclipse.jdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.jdt.internal.compiler.ast.FieldReference;
import org.eclipse.jdt.internal.compiler.ast.LocalDeclaration;
import org.eclipse.jdt.internal.compiler.ast.MessageSend;
import org.eclipse.jdt.internal.compiler.ast.MethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.QualifiedNameReference;
import org.eclipse.jdt.internal.compiler.ast.SingleNameReference;
import org.eclipse.jdt.internal.compiler.ast.SingleTypeReference;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.Compiler;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.ICompilerRequestor;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.env.INameEnvironment;
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.lookup.Binding;
import org.eclipse.jdt.internal.compiler.lookup.BlockScope;
import org.eclipse.jdt.internal.compiler.lookup.ClassScope;
import org.eclipse.jdt.internal.compiler.lookup.CompilationUnitScope;
import org.eclipse.jdt.internal.compiler.lookup.FieldBinding;
import org.eclipse.jdt.internal.compiler.lookup.LocalVariableBinding;
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.MethodScope;
import org.eclipse.jdt.internal.compiler.lookup.PackageBinding;
import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding;
import org.eclipse.jdt.internal.compiler.lookup.Scope;
import org.eclipse.jdt.internal.compiler.lookup.SourceTypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.TagBits;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.VariableBinding;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jo.lspjava.api.Diagnostic;
import jo.lspjava.api.Severity;
import jo.lspjava.api.TextRange;
import jo.lspjava.engine.CancelToken;
import jo.lspjava.engine.OperationCanceledException;

/**
 * Moteur de diagnostics Java basé sur le <strong>compilateur interne ecj</strong>
 * ({@code org.eclipse.jdt.internal.compiler.Compiler}), en processus —
 * l'approche du projet de référence ({@code JdtResolver}), adaptée à :lspjava.
 *
 * <p>Contrairement au parseur JavaParser historique de :lspjava, ecj apporte
 * la <b>vraie</b> sémantique du compilateur : résolution de types contre le
 * classpath du projet (fourni par le Gradle Tooling), surcharges, héritage,
 * analyse de flux — et une récupération d'erreurs (statement + binding
 * recovery) qui garde l'arbre résolu même sur du code à moitié tapé.
 * Le Gradle Tooling reste LE moteur de build ; ce moteur ne sert que
 * l'édition (diagnostics temps réel dans cette phase).</p>
 *
 * <p><strong>Machine :</strong> un {@link Compiler} neuf par diagnose, sur un
 * wrapper {@link INameEnvironment} fin (exclusion du type focal) posé sur
 * l'index partagé {@link JdtCompilerEnvironment} (handles de jars + paquets
 * construits une fois). {@code resolve(unit, verifyMethods=true,
 * analyzeCode=true, generateCode=true)} avec un requestor no-op : la passe
 * de codegen est requise pour la <em>parité des avertissements</em> (dans
 * ecj 3.40, les warnings de flux — variable locale non utilisée, etc. — ne
 * sont émis que pendant le codegen ; les octets générés vont au requestor
 * no-op et ne sont jamais écrits).</p>
 *
 * <p><strong>ART :</strong> le jar ecj consommé est patché au build
 * ({@code :lspjava:patchEcjForArt}) — {@code Runtime$Version} et
 * {@code InputStream.readAllBytes/readNBytes} réécrits vers les shims
 * {@code jo.lspjava.jdt.compat.*}. Ce code n'utilise que des API API-26.</p>
 *
 * @author jo@Dev
 */
public final class JdtCompilerEngine {

    private static final Logger LOG = Logger.getLogger("JdtCompilerEngine");

    /** Kill-switch système : -Djo.lspjava.jdt.disabled=true. */
    static final boolean DISABLED =
            Boolean.getBoolean("jo.lspjava.jdt.disabled");

    /**
     * Kill-switch du fast-path de diagnostics incrémentaux (P6) :
     * -Djo.lspjava.jdt.fastdiagnose.disabled=true. Défaut : activé.
     */
    static final boolean FAST_DIAGNOSE_DISABLED =
            Boolean.getBoolean("jo.lspjava.jdt.fastdiagnose.disabled");

    /**
     * Mémo du dernier diagnose (P6, v1.5.0) — source, problèmes publiés
     * et plages des corps de niveau membre. Clé sur le CONTENU : survit
     * aux didChange (c'est le point), invalidé par dispose() (changement
     * de classpath) et à la fermeture du fichier. Accédé sous le verrou
     * de diagnose() uniquement.
     */
    private JdtFastDiagnose.Memo lastDiagnosis;

    /**
     * Arbre focalisé du dernier diagnose RAPIDE (P6 étendu, v1.6.0) —
     * conservé pour servir hover et signature help SANS re-parse.
     *
     * <p><strong>Motivation :</strong> le fast-path P6 compile déjà la
     * source focalisée (corps hôte intact, autres corps remplacés par un
     * synthétique isomorphe)… puis jette l'arbre : seuls les problèmes
     * sont mémos. Or les requêtes hover / signature help qui suivent la
     * frappe (post-debounce, même contenu) re-parsent chacune une source
     * réduite via {@link SourceFocuser} — le {@link BindingParseCache}
     * ne peut jamais servir (clé = source réduite ≠ source réelle du
     * tampon). Conserver l'arbre déjà résolu élimine ce re-parse.</p>
     *
     * <p><strong>Sécurité du service :</strong> l'arbre n'est rendu que
     * si le contenu du tampon est IDENTIQUE (clé sur le contenu — même
     * argument que le mémo P6 : même contenu = même arbre, jamais de
     * résultat périmé) ET si l'offset demandé est dans le corps hôte
     * (seule région dont le texte est garantivement intact — le reste
     * peut être masqué). La source focalisée étant ISOMORPHE (longueur
     * et positions préservées), offsets et plages réels sont utilisables
     * tels quels — aucun mapBack.</p>
     *
     * <p>Accédé sous le verrou du moteur (diagnose / resolveUnitFast /
     * dispose). Invalide par dispose() (classpath) uniquement — PAS par
     * {@link #invalidateParseCache} (appelé par didChange à chaque
     * frappe, vider l'arbre tuerait le fast-path ; la clé de contenu
     * suffit à garantir la fraîcheur).</p>
     */
    private FocusedTree lastFocusedTree;

    /**
     * Arbre focalisé retenu : contenu-clé + plage du corps hôte dans la
     * source réelle (indices des accolades) + arbre résolu.
     */
    private static final class FocusedTree {
        final String filePath;
        /** Contenu EXACT du tampon pour lequel l'arbre a été résolu. */
        final String source;
        /** Indice du « { » du corps hôte dans {@link #source}. */
        final int hostOpen;
        /** Indice du « } » du corps hôte dans {@link #source}. */
        final int hostClose;
        /** L'arbre résolu de la source focalisée (isomorphe). */
        final CompilationUnitDeclaration cud;

        FocusedTree(String filePath, String source,
                    int hostOpen, int hostClose,
                    CompilationUnitDeclaration cud) {
            this.filePath = filePath;
            this.source = source;
            this.hostOpen = hostOpen;
            this.hostClose = hostClose;
            this.cud = cud;
        }
    }

    /** Compteurs observables (tests) : diagnostics rapides vs complets. */
    private int fastDiagnoseHits;
    private int fullDiagnoseCount;

    /**
     * Compteurs observables (tests, v1.6.0) : requêtes hover / signature
     * help servies par l'arbre focalisé retenu vs par l'arbre réel du
     * parseCache (diagnose complet du même contenu).
     */
    private int fastResolveHits;
    private int cachedResolveHits;

    /** @return nombre de diagnostics servis par le fast-path P6 (tests). */
    int fastDiagnoseHits() { return fastDiagnoseHits; }

    /** @return nombre de diagnostics complets exécutés (tests). */
    int fullDiagnoseCount() { return fullDiagnoseCount; }

    /**
     * @return nombre de requêtes hover / signature help servies par
     *         l'arbre focalisé du dernier diagnose rapide (tests, v1.6.0).
     */
    int fastResolveHits() { return fastResolveHits; }

    /**
     * @return nombre de requêtes servies par l'arbre réel du parseCache
     *         (diagnose complet du même contenu — tests, v1.6.0).
     */
    int cachedResolveHits() { return cachedResolveHits; }

    private static final Pattern PACKAGE_DECL = Pattern.compile(
            "^\\s*package\\s+([A-Za-z_][\\w.]*)\\s*;", Pattern.MULTILINE);

    private final List<Path> sourceRoots;
    private final List<Path> classpathJars;
    private final long complianceLevel;

    /**
     * Index partagé (handles de jars, paquets) — construit paresseusement.
     * Volatile + lock séparé pour ensureIndex() : permet de pré-chauffer
     * l'index en arrière-plan dès initialized() sans bloquer les requêtes
     * LSP derrière le verrou synchronized de resolveUnit().
     */
    private volatile JdtCompilerEnvironment index;
    private final Object indexLock = new Object();
    private final java.util.concurrent.atomic.AtomicBoolean indexPreWarming =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Hook d'annulation coopérative de la résolution en cours. Posé par
     * {@link #resolveUnit(String, String, boolean, CancelToken)} juste
     * avant la construction de l'environnement, relu par les checkpoints
     * du wrapper INameEnvironment (findType / isPackage) des milliers de
     * fois par parse — une annulation coupe ainsi la résolution au tout
     * prochain symbole résolu, libérant le verrou intrinsèque pour la
     * requête supersédante.
     */
    private volatile CancelToken resolveCancelHook;

    /**
     * Cache de parse à entrée unique, clé sur {@code (filePath, source)}.
     *
     * <p>Une même frappe déclenche typiquement diagnostics + highlight +
     * inlay hints + signature help sur le MÊME fichier — sans cache, le
     * fichier est re-parsé et re-résolu à chaque appel. Ce cache
     * (pattern {@code BindingParseCache} du projet de référence) mutualise un seul
     * arbre résolu pour toutes les features successives d'une même frappe,
     * invalidé à la première édition.</p>
     */
    private final BindingParseCache parseCache = new BindingParseCache();

    public JdtCompilerEngine(List<File> sourceRoots,
                             List<File> classpathEntries,
                             String complianceLevel) {
        this(sourceRoots, classpathEntries, complianceLevel, null);
    }

    /**
     * @param jdkHome home du JDK fournissant l'image jrt (java.*) — sur
     *                Android/ART, le JDK installé par le terminal ; ignoré
     *                si null (fallback {@code java.home} du runtime).
     */
    public JdtCompilerEngine(List<File> sourceRoots,
                             List<File> classpathEntries,
                             String complianceLevel,
                             java.nio.file.Path jdkHome) {
        this.sourceRoots = new ArrayList<>();
        if (sourceRoots != null) {
            for (File f : sourceRoots) {
                if (f != null && f.isDirectory()) this.sourceRoots.add(f.toPath());
            }
        }
        this.classpathJars = new ArrayList<>();
        if (classpathEntries != null) {
            for (File f : classpathEntries) {
                if (f != null && f.isFile()
                        && f.getName().endsWith(".jar")) {
                    this.classpathJars.add(f.toPath());
                }
            }
        }
        this.complianceLevel = complianceOf(complianceLevel);
        this.jdkHome = jdkHome;
    }

    private final java.nio.file.Path jdkHome;

    /**
     * GRADLE_USER_HOME ({@code ~/.gradle} du layout CodeIDE) — là où le LSP
     * cherche les {@code -sources.jar} attachés aux dépendances.
     * Résolu depuis {@code ToolPaths.gradleUserHome()} côté app ; null tant
     * que non seedé (fallback : pas de résolution de noms binaires depuis
     * Gradle caches).
     */
    private java.nio.file.Path gradleUserHome;

    /**
     * Diagnostics compilateur (ecj) pour une unité source.
     *
     * @param filePath chemin absolu du fichier (pour dériver le FQCN)
     * @param source   le contenu du tampon
     * @return les diagnostics triés par offset de départ, jamais null
     *         (vide si aucune erreur/avertissement).
     */
    public synchronized List<Diagnostic> diagnose(String filePath, String source) {
        return diagnose(filePath, source, null);
    }

    /**
     * Diagnostics avec annulation coopérative.
     *
     * <p>Le token est consulté par les checkpoints INameEnvironment pendant
     * TOUTE la chaîne ecj (parse + resolve + analyze + codegen) : une
     * annulation interrompt l'unité AU PROCHAIN symbole résolu au lieu de
     * laisser finir un parse potentiellement long, puis jette
     * {@link OperationCanceledException} (l'appelant — DiagnosticService —
     * retourne simplement vide ; la publication suivante reparse).
     *</p>
     *
     * @param cancelToken token d'annulation coopérative, ou null (jamais coupé)
     */
    public synchronized List<Diagnostic> diagnose(String filePath, String source,
                                                   CancelToken cancelToken) {
        if (DISABLED) {
            throw new IllegalStateException("JDT désactivé (kill-switch)");
        }

        // ── P6 (v1.5.0) : mémo exact — même source re-diagnostiquée
        // (republish post-debounce, didOpen d'un tampon inchangé…) :
        // le résultat précédent est retourné tel quel, zéro parse.
        if (!FAST_DIAGNOSE_DISABLED && lastDiagnosis != null
                && lastDiagnosis.matches(filePath, source)) {
            fastDiagnoseHits++;
            return lastDiagnosis.problems;
        }

        // ── P6 : fast-path — édition confinée dans UN corps de méthode.
        // Re-diagnostique le seul corps édité (les autres remplacés par
        // un synthétique de même longueur, leurs problèmes portés du
        // diagnostic précédent). Retourne null au moindre doute →
        // diagnose complet ci-dessous (jamais de perte de diagnostic).
        if (!FAST_DIAGNOSE_DISABLED && lastDiagnosis != null
                && lastDiagnosis.filePath.equals(filePath)) {
            List<Diagnostic> fast = tryFastDiagnose(
                    lastDiagnosis, filePath, source, cancelToken);
            if (fast != null) {
                fastDiagnoseHits++;
                return fast;
            }
            if (cancelToken != null && cancelToken.isCancelled()) {
                throw new OperationCanceledException("diagnose annulé");
            }
        }

        // ── Chemin complet d'origine (premier diagnose, structure
        // modifiée, fast-path indisponible ou en échec).
        fullDiagnoseCount++;
        CompilationUnitDeclaration cud = resolveUnit(filePath, source, true, cancelToken);
        if (cancelToken != null && cancelToken.isCancelled()) {
            // Fin nette et rapide — pas de collecte de problèmes partiels :
            // la passe est obsolète ou abandonnée (timeout), autant ne rien
            // publier du tout pour éviter un flash de diagnostics périmés.
            throw new OperationCanceledException("diagnose annulé");
        }

        List<Diagnostic> out = new ArrayList<>();
        int[][] spans = new int[0][];
        if (cud != null && cud.compilationResult != null) {
            IProblem[] problems = cud.compilationResult.getAllProblems();
            if (problems != null) {
                // P5 : détails « classe absente » pour les hiérarchies cassées.
                // Calculé uniquement si au moins un problème de hiérarchie
                // est présent (sinon coût nul — map vide partagée).
                Map<Integer, String> hierarchyDetails = hierarchyDetails(cud, problems);
                for (IProblem p : problems) {
                    Diagnostic d = toDiagnostic(p, hierarchyDetails);
                    if (d != null) out.add(d);
                }
            }
            spans = JdtFastDiagnose.memberBodySpans(cud, source);
        }
        out.sort(Comparator.comparingInt(d -> d.range().startOffset()));
        List<Diagnostic> frozen = List.copyOf(out);
        lastDiagnosis = new JdtFastDiagnose.Memo(
                filePath, source, frozen, spans);
        return frozen;
    }

    /**
     * Fast-path P6 — orchestration (appelé sous le verrou de
     * {@link #diagnose}). Étapes : preuve de confinement (région d'édition
     * par les deux bouts + re-lexage du corps hôte), construction de la
     * source focalisée (autres corps remplacés par {@code throw null;}
     * isomorphe), parse+résolution+codegen de cette source (SANS toucher
     * au {@link BindingParseCache} — l'arbre focalisé ne doit jamais être
     * servi aux autres features), assemblage frais+portés, mémo suivant.
     *
     * @return la liste publiée, ou null → repli sur le diagnose complet
     */
    private List<Diagnostic> tryFastDiagnose(JdtFastDiagnose.Memo memo,
                                             String filePath, String source,
                                             CancelToken cancelToken) {
        JdtFastDiagnose.Confined c;
        try {
            c = JdtFastDiagnose.tryConfine(memo, source);
        } catch (Throwable bestEffort) {
            return null;  // preuve impossible → complet
        }
        if (c == null) return null;

        // Source focalisée : corps hôte intact, autres corps synthétiques.
        Object[] blanked = JdtFastDiagnose.blankOtherBodies(
                source, c.oldOpen, c.newClose, c.otherSpansNew);
        String focused = (String) blanked[0];
        @SuppressWarnings("unchecked")
        int[][] blankedSpans = (int[][]) blanked[1];

        // Parse + résolution + codegen de la source focalisée, SANS
        // polluer le cache de parse (clé (filePath, focused) — jamais
        // demandée par les features, mais l'entrée évincerait le CUD
        // complet de la frappe précédente pour rien).
        String fqcn = fqcnFor(filePath, source);
        CompilationUnitDeclaration cud = compileUnit(
                focused, fqcn, true, cancelToken);
        if (cud == null || cud.compilationResult == null) {
            if (cancelToken != null && cancelToken.isCancelled()) return null;
            return null;  // parse focalisé en échec → complet
        }
        IProblem[] problems = cud.compilationResult.getAllProblems();
        List<Diagnostic> fresh = new ArrayList<>();
        if (problems != null) {
            Map<Integer, String> hierarchyDetails =
                    hierarchyDetails(cud, problems);
            for (IProblem p : problems) {
                Diagnostic d = toDiagnostic(p, hierarchyDetails);
                if (d != null) fresh.add(d);
            }
        }

        // Texte masqué (corps remplacés) pour la suppression de la
        // fausse famille « unused ».
        StringBuilder masked = new StringBuilder();
        for (int[] span : blankedSpans) {
            masked.append(source, span[0] + 1, span[1]);
        }

        // Contreparties ANCIENNES des plages remplacées — le filtre de
        // portage teste les problèmes du mémo (coordonnées anciennes)
        // contre des plages anciennes, puis les mappe.
        int[][] blankedOld = JdtFastDiagnose.blankedOldSpans(
                memo, c.oldOpen, c.oldClose);

        List<Diagnostic> result = JdtFastDiagnose.assemble(
                fresh, memo, c, blankedSpans, blankedOld,
                masked.toString());
        List<Diagnostic> frozen = List.copyOf(result);
        lastDiagnosis = new JdtFastDiagnose.Memo(
                filePath, source, frozen,
                JdtFastDiagnose.nextSpans(c));
        // v1.6.0 : l'arbre focalisé est DÉJÀ résolu ci-dessus — on le
        // conserve pour servir hover / signature help sans re-parse
        // (garde de service : contenu identique + offset dans le corps
        // hôte [oldOpen, newClose], cf. resolveUnitFast).
        lastFocusedTree = new FocusedTree(
                filePath, source, c.oldOpen, c.newClose, cud);
        return frozen;
    }

    /**
     * Résout une unité avec bindings (parse complet du compilateur interne).
     * Exposé package-private pour la complétion ({@link JdtCompletionEngine}).
     *
     * <p><strong>Synchronisation :</strong> cette méthode est elle-même
     * {@code synchronized} — elle constitue le <em>point de passage unique</em>
     * par lequel tous les services LSP accèdent au {@link BindingParseCache}
     * (non thread-safe) et au {@link JdtCompilerEnvironment} (jars partagés).
     * Les appelants ({@link JdtCompletionEngine}, {@link JdtSignatureHelp},
     * {@link JdtInlayHints}, {@link JdtRenameEngine}, {@link JdtImportOrganizer},
     * {@link jo.lspjava.lsp.JavaTextDocumentService#documentSymbol}) n'ont pas
     * à prendre de verrou eux-mêmes : l'exclusion mutuelle est garantie par
     * cette méthode. Les méthodes historiques ({@link #diagnose},
     * {@link #hoverAt}, {@link #findDefinition}, {@link #isLikelyUsable},
     * {@link #dispose}) restent {@code synchronized} pour compatibilité —
     * le verrou est réentrant, pas de surcoût.</p>
     *
     * <p><strong>Cache :</strong> un {@link BindingParseCache} à entrée unique
     * mutualise l'arbre résolu entre toutes les features d'une même frappe
     * (diagnostics + hover + highlight + inlay hints + signature help).
     * Le cache est invalidé dès que {@code (filePath, source)} change —
     * c'est-à-dire à chaque {@code didChange} LSP.</p>
     *
     * @param generateCode true pour la parité des warnings de flux (diagnostics),
     *                     false pour hover/complétion (plus rapide).
     */
    public synchronized CompilationUnitDeclaration resolveUnit(String filePath, String source,
                                           boolean generateCode) {
        return resolveUnit(filePath, source, generateCode, null);
    }

    /**
     * Résolution avec hook d'annulation coopérative (R4). Le token actif
     * est posé dans {@link #resolveCancelHook} pendant toute la résolution
     * et relu par le wrapper INameEnvironment. À l'annulation, l'exception
     * remonte hors de {@code Compiler.resolve} et cette méthode retourne
     * null SANS toucher au cache (aucun arbre partiel n'est jamais servi).
     */
    synchronized CompilationUnitDeclaration resolveUnit(String filePath, String source,
                                           boolean generateCode, CancelToken cancelToken) {
        // Cache hit : la même frappe sert diagnostics + hover + highlight.
        CompilationUnitDeclaration cached = parseCache.get(filePath, source, generateCode);
        if (cached != null) return cached;

        ensureIndex();
        String fqcn = fqcnFor(filePath, source);
        CompilationUnitDeclaration cud = compileUnit(source, fqcn, generateCode, cancelToken);
        if (cud == null) return null;  // annulé ou échec — déjà tracé

        // Cache miss : on enregistre l'arbre pour les features suivantes
        // de la même frappe. Les autres features ont generateCode=false ;
        // si le cache a été rempli avec generateCode=true (diagnostics),
        // il est réutilisable (les bindings sont identiques — la codegen
        // ne fait que produire des octets de classe qu'on jette).
        parseCache.put(filePath, source, cud, generateCode);
        return cud;
    }

    /**
     * Invocation brute du compilateur ecj sur une source — SANS cache de
     * parse (l'appelant décide). Extraite de {@link #resolveUnit} pour le
     * fast-path P6 : la source focalisée (corps synthétiques) ne doit
     * JAMAIS entrer dans le {@link BindingParseCache} — elle n'est pas la
     * source réelle du tampon.
     *
     * <p>Appelée sous le verrou du moteur uniquement (resolveUnit et
     * tryFastDiagnose sont tous deux sous verrou).</p>
     *
     * @return l'arbre résolu, ou null (annulation coopérative ou échec
     *         de parse — tracé WARNING dans le second cas)
     */
    private CompilationUnitDeclaration compileUnit(String source, String fqcn,
                                                   boolean generateCode,
                                                   CancelToken cancelToken) {
        ensureIndex();
        INameEnvironment env = wrapEnvironment(Collections.singleton(fqcn));

        CompilerOptions options = new CompilerOptions();
        options.complianceLevel = complianceLevel;
        options.sourceLevel = complianceLevel;
        options.targetJDK = complianceLevel;
        options.performMethodsFullRecovery = true;
        options.performStatementsRecovery = true;
        options.storeAnnotations = false;

        Compiler compiler = new Compiler(
                env,
                DefaultErrorHandlingPolicies.proceedWithAllProblems(),
                options,
                result -> { },  // requestor no-op
                new DefaultProblemFactory(java.util.Locale.getDefault()));

        // Active les checkpoints pour cette résolution seulement.
        this.resolveCancelHook = cancelToken;
        try {
            CompilationUnitDeclaration cud = compiler.resolve(
                    new JdtSourceUnit(fqcn, source.toCharArray()),
                    /* verifyMethods */ true,
                    /* analyzeCode */ true,
                    /* generateCode */ generateCode);
            if (cancelToken != null && cancelToken.isCancelled()) {
                // Coupé entre deux checkpoints — publier l'arbre serait
                // inutile (la passe est obsolète/abandonnée).
                throw new OperationCanceledException("resolve annulé post-phase");
            }
            return cud;
        } catch (OperationCanceledException oc) {
            // Annulation coopérative volontaire — PAS un échec de parse :
            // on sort sans rien publier, la frappe suivante reparsera.
            return null;
        } catch (Throwable t) {
            // On trace plutôt que d'avaler silencieusement — sinon impossible
            // de diagnostiquer les ecj internal errors.
            LOG.log(java.util.logging.Level.WARNING,
                    "compileUnit failed for " + fqcn + " (degrade to null)", t);
            return null; // dégrade — l'appelant retombe sur JavaParser
        } finally {
            // Désactive les checkpoints — jamais de hook résiduel.
            this.resolveCancelHook = null;
        }
    }

    /**
     * Invalide le cache de parse pour un fichier donné.
     *
     * <p>Appelé par {@link jo.lspjava.lsp.JavaTextDocumentService#didChange}
     * quand le tampon évolue — sinon le cache servirait un arbre résolu sur
     * une version périmée du texte.</p>
     */
    public void invalidateParseCache(String filePath) {
        parseCache.invalidate(filePath);
        // NOTE P6 : le mémo de diagnostics incrémentaux N'est PAS vidé
        // ici — cette méthode est appelée par didChange À CHAQUE frappe,
        // et survivre aux didChange est précisément la raison d'être du
        // mémo. Il est clé sur le CONTENU (jamais de résultat périmé
        // servi : même contenu = même résultat, contenu différent =
        // fast-path prouvé ou diagnose complet) et borné à une entrée
        // par moteur. Seul dispose() (changement de classpath) le vide.
        // Idem pour l'arbre focalisé retenu (v1.6.0) : clé sur le
        // contenu, vidé par dispose() uniquement.
    }

    /**
     * Arbre focalisé du dernier diagnose RAPIDE, s'il est prouvément
     * valide pour cette requête (P6 étendu, v1.6.0) — hover et signature
     * help sans re-parse.
     *
     * <p><strong>Gardes (toutes obligatoires) :</strong></p>
     * <ul>
     *   <li>kill-switch P6 désactivé ;</li>
     *   <li>le fichier et le CONTENU du tampon sont IDENTIQUES à ceux du
     *       dernier diagnose rapide (clé sur le contenu : un contenu
     *       différent = un arbre qui ne décrit pas ce tampon → jamais
     *       servi, même pas pour tenter) ;</li>
     *   <li>l'offset demandé est DANS le corps hôte [« { », « } » ]
     *       inclus — la seule région de la source focalisée dont le
     *       texte est garantivement identique au tampon (les autres
     *       corps peuvent être masqués par le synthétique
     *       {@code throw null;}).</li>
     * </ul>
     *
     * <p>La source focalisée étant ISOMORPHE au tampon (longueurs et
     * positions préservées), l'appelant utilise l'offset et les plages
     * réels tels quels — aucun mapBack nécessaire. Toute requête hors
     * garde rend null → l'appelant retombe sur son chemin d'origine
     * (probe parseCache puis réduction {@link SourceFocuser}).</p>
     *
     * <p>Package-private : réservé aux services du package jdt (hover,
     * {@link JdtSignatureHelp}) — sous le verrou du moteur.</p>
     */
    synchronized CompilationUnitDeclaration resolveUnitFast(
            String filePath, String source, int offset) {
        if (DISABLED || FAST_DIAGNOSE_DISABLED) return null;
        FocusedTree f = lastFocusedTree;
        if (f == null) return null;
        if (!f.filePath.equals(filePath)) return null;
        if (!f.source.equals(source)) return null;
        if (offset < f.hostOpen || offset > f.hostClose) return null;
        fastResolveHits++;
        return f.cud;
    }

    /**
     * Arbre RÉEL déjà résolu pour CE contenu exact, s'il est au
     * parseCache (v1.6.0) — probe pure, jamais de parse.
     *
     * <p><strong>Motivation :</strong> après un diagnose COMPLET d'un
     * contenu C (premier diagnose, structure modifiée, repli du
     * fast-path), le parseCache contient l'arbre résolu de la source
     * RÉELLE C. Les requêtes hover / signature help sur ce même contenu
     * n'ont alors plus besoin de la réduction {@link SourceFocuser} :
     * l'arbre réel est un sur-ensemble fidèle (résolution à coût nul,
     * pleine fidélité, aucun mapping d'offset). Avant v1.6.0, ces
     * requêtes re-parsaient une source réduite alors que l'arbre réel
     * était déjà là — la clé du cache (source réduite) ne matchait
     * jamais la source réelle.</p>
     *
     * <p>Compatible generateCode : une entrée produite par diagnose
     * (codegen=true) est servie aux features qui demandent false (les
     * bindings sont identiques — cf. {@link BindingParseCache#get}).</p>
     *
     * <p>Package-private : réservé aux services du package jdt (hover,
     * {@link JdtSignatureHelp}) — sous le verrou du moteur.</p>
     */
    synchronized CompilationUnitDeclaration cachedUnit(
            String filePath, String source) {
        if (DISABLED) return null;
        CompilationUnitDeclaration cud = parseCache.get(filePath, source, false);
        if (cud != null) cachedResolveHits++;
        return cud;
    }

    /**
     * Information de survol (hover) au offset — signature du symbole résolu
     * par les bindings ecj (méthode, champ, variable locale, type, paquet).
     *
     * @return le markdown du hover, ou null (l'appelant retombe sur JavaParser).
     */
    public synchronized String hoverAt(String filePath, String source, int offset) {
        if (DISABLED) return null;
        // ── v1.6.0, chaîne à 3 niveaux (du moins cher au plus cher) :
        // chaque niveau ne fait rien perdre — un résultat nul ou une
        // exception retombe sur le niveau suivant, le pire cas reste le
        // chemin v1.5 (réduction + resolveUnit).
        //
        // 1. Arbre focalisé du dernier diagnose RAPIDE (P6 étendu) :
        //    déjà résolu pour CE contenu, offsets réels directement
        //    utilisables (source isomorphe — aucun mapBack).
        try {
            CompilationUnitDeclaration fast =
                    resolveUnitFast(filePath, source, offset);
            if (fast != null) {
                UnitScan scan = scanUnit(fast, source, offset);
                if (scan != null && scan.hover != null) return scan.hover;
            }
        } catch (Throwable t) {
            // Best-effort — repli sur le niveau suivant.
        }
        // 2. Arbre RÉEL du parseCache (diagnose complet de CE contenu) :
        //    déjà résolu, pleine fidélité, offsets réels.
        try {
            CompilationUnitDeclaration real = cachedUnit(filePath, source);
            if (real != null) {
                UnitScan scan = scanUnit(real, source, offset);
                if (scan != null && scan.hover != null) return scan.hover;
            }
        } catch (Throwable t) {
            // Best-effort — repli sur le niveau suivant.
        }
        // 3. Chemin v1.5 — réduction du texte avant resolveUnit
        //    (technique de java-language-server) : SourceFocuser efface
        //    le corps des méthodes hors-portée (garde les signatures),
        //    ce qui rend la résolution ecj bien moins coûteuse pour le
        //    hover — typiquement un seul appel à resolveUnit au lieu du
        //    fichier complet.
        try {
            SourceFocuser.Focused focused = SourceFocuser.focusAndMap(source, offset);
            String workSource = focused.source();
            int workOffset = focused.offset();

            CompilationUnitDeclaration cud = resolveUnit(filePath, workSource, false);
            if (cud == null || cud.scope == null) return null;
            UnitScan scan = scanUnit(cud, workSource, workOffset);
            return scan != null ? scan.hover : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Plage de la déclaration du symbole au offset (dans CE fichier).
     *
     * @return la plage du nom déclaré, ou null (l'appelant retombe sur
     *         JavaParser ou ne propose rien).
     */
    public synchronized TextRange findDefinition(String filePath, String source, int offset) {
        if (DISABLED) return null;
        try {
            // Même réduction que pour hover — le go-to-definition
            // intra-fichier n'a pas besoin du corps des autres méthodes.
            SourceFocuser.Focused focused = SourceFocuser.focusAndMap(source, offset);
            String workSource = focused.source();
            int workOffset = focused.offset();

            CompilationUnitDeclaration cud = resolveUnit(filePath, workSource, false);
            if (cud == null || cud.scope == null) return null;
            UnitScan scan = scanUnit(cud, workSource, workOffset);
            if (scan == null || scan.referenceBinding == null) return null;

            // Cherche la déclaration dont le binding correspond.
            Binding ref = scan.referenceBinding;
            for (Declaration decl : scan.declarations) {
                if (sameBinding(ref, decl.binding)) {
                    int[] nameRange = nameRangeOf(decl, workSource);
                    if (nameRange == null) return null;
                    // Les plages sont en coordonnées de la source réduite —
                    // mapBack les traduit vers la source originale (sans
                    // cela, une définition après la méthode du caret
                    // atterrirait à un offset décalé sur les gros fichiers).
                    return new TextRange(focused.mapBack(nameRange[0]),
                            focused.mapBack(nameRange[1]));
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Plage de la déclaration du TYPE du symbole au offset (dans CE
     * fichier) : le caret sur {@code Foo x = …} ou un appel renvoyant Foo
     * résout la déclaration de {@code class Foo}. Élément feuille des
     * tableaux, types paramétrés ramenés à l'original ; types binaires
     * (classpath, hors fichier courant) et types de base ({@code int}) →
     * null (aucune déclaration dans l'unité).
     *
     * <p>Portage de {@code typeDeclarationTargets} du KotlinSourceAnalyzer de
     * projet de référence ({@code symbol.type.qualifiedName → sourceOrLibrary}),
     * restreint au même fichier : le moteur ecj ne connaît pas les offsets
     * des déclarations des AUTRES fichiers.</p>
     *
     * @return la plage du nom du type déclaré, ou null
     */
    public synchronized TextRange findTypeDefinition(String filePath, String source, int offset) {
        if (DISABLED) return null;
        try {
            SourceFocuser.Focused focused = SourceFocuser.focusAndMap(source, offset);
            String workSource = focused.source();
            int workOffset = focused.offset();

            CompilationUnitDeclaration cud = resolveUnit(filePath, workSource, false);
            if (cud == null || cud.scope == null) return null;
            UnitScan scan = scanUnit(cud, workSource, workOffset);
            if (scan == null) return null;

            TypeBinding type = typeBindingOf(scan.referenceBinding);
            if (type == null) return null;

            for (Declaration decl : scan.declarations) {
                if (decl.binding == type || (decl.binding instanceof TypeBinding tb
                        && sameBinding(tb, type))) {
                    int[] nameRange = nameRangeOf(decl, workSource);
                    if (nameRange == null) return null;
                    return new TextRange(focused.mapBack(nameRange[0]),
                            focused.mapBack(nameRange[1]));
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Le TYPE du binding au caret : une référence de type se
     * suffit ; une variable/paramètre/champ donne son type ; un appel de
     * méthode donne son type de retour ; un tableau donne son élément
     * feuille ; un type paramétré est ramené à son original.
     */
    private static TypeBinding typeBindingOf(Binding b) {
        if (b == null) return null;
        try {
            TypeBinding t = null;
            if (b instanceof TypeBinding) {
                t = (TypeBinding) b;
            } else if (b instanceof VariableBinding v && v.type != null) {
                t = v.type;
            } else if (b instanceof MethodBinding m && m.returnType != null) {
                t = m.returnType;
            }
            if (t == null) return null;
            if (t.isArrayType()) {
                t = t.leafComponentType();
            }
            if (t.isParameterizedType() && t.original() != null) {
                t = t.original();
            }
            // Types sans déclaration dans l'unité : base (int/void/…),
            // variable de type (T), problème de résolution.
            if (t.isBaseType() || t.isTypeVariable() || !t.isValidBinding()) return null;
            return t;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Le FQN du type déclarant (ou du type lui-même) au caret.
     *
     * <p>Pour le fallback binaire de {@code definition}/{@code typeDefinition}
     * dans {@link jo.lspjava.lsp.JavaTextDocumentService} : quand le chemin
     * standard JDT-first ({@link #findDefinition}/{@link #findTypeDefinition})
     * ne trouve pas la cible dans les sources du projet (la cible est un
     * type binaire — JDK, JAR du classpath), on résout son FQN ici pour
     * construire une Location synthétique
     * {@code jdt://decompiled/<fqcn>.java}.</p>
     *
     * <p>Sémantique :</p>
     * <ul>
     *   <li>{@code targetType = false} : le FQN de la classe qui DÉCLARE le
     *       membre au caret (méthode ou champ). Pour une référence de type,
     *       c'est le FQN du type lui-même. Pour une variable locale, c'est
     *       le FQN du TYPE de la variable. Cible typique du GO TO Definition.</li>
     *   <li>{@code targetType = true} : le FQN du TYPE du symbole au caret.
     *       Pour une variable/paramètre/champ, c'est le type de la variable.
     *       Pour un appel de méthode, c'est le type de retour. Cible typique
     *       du GO TO Type Definition.</li>
     * </ul>
     *
     * <p><strong>Note sur les types génériques :</strong> {@code readableName()}
     * d'un {@code ReferenceBinding} comme {@code HashMap} renvoie
     * {@code "java.util.HashMap<K,V>"} (les variables de type déclarées sur la
     * classe sont incluses). On STRIPPE le suffixe {@code <...>} pour obtenir
     * le FQN nu utilisé dans l'URI {@code jdt://decompiled/...}.</p>
     *
     * @param filePath   le chemin absolu du fichier
     * @param source     le texte source
     * @param offset     l'offset du caret dans {@code source}
     * @param targetType  false → classe déclarante ; true → type du symbole
     * @return le FQN (ex. {@code java.util.HashMap}), ou null si indisponible
     */
    public synchronized String fqcnAt(String filePath, String source,
                                       int offset, boolean targetType) {
        if (DISABLED) return null;
        try {
            SourceFocuser.Focused focused = SourceFocuser.focusAndMap(source, offset);
            String workSource = focused.source();
            int workOffset = focused.offset();

            CompilationUnitDeclaration cud = resolveUnit(filePath, workSource, false);
            if (cud == null || cud.scope == null) return null;
            UnitScan scan = scanUnit(cud, workSource, workOffset);
            if (scan == null || scan.referenceBinding == null) return null;

            Binding ref = scan.referenceBinding;
            TypeBinding t;
            if (targetType) {
                // Type definition : le TYPE du symbole (var/field → type ;
                // method call → type de retour ; type reference → type lui-même).
                t = typeBindingOf(ref);
            } else {
                // Definition : la classe qui DÉCLARE le membre (méthode/champ).
                // Pour une référence de type, c'est le type lui-même.
                t = declaringTypeBindingOf(ref);
            }
            return stripGenericsFromFqn(t);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Extrait le TypeBinding de la classe qui DÉCLARE le membre
     * au caret. Pour une référence de type, retourne le type lui-même.
     * Pour un MessageBinding, retourne {@code declaringClass}. Pour un
     * FieldBinding, {@code declaringClass}. Pour une variable locale,
     * le TYPE de la variable (le type binaire c'est là que l'utilisateur
     * veut aller — pas la méthode englobante).
     */
    private static TypeBinding declaringTypeBindingOf(Binding b) {
        if (b == null) return null;
        try {
            if (b instanceof MethodBinding m) {
                return m.declaringClass;
            }
            if (b instanceof FieldBinding f) {
                return f.declaringClass;
            }
            if (b instanceof LocalVariableBinding lv && lv.type != null) {
                return lv.type;
            }
            if (b instanceof VariableBinding v && v.type != null) {
                return v.type;
            }
            if (b instanceof TypeBinding tb) {
                return tb;
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Convertit un TypeBinding en FQN nu (sans {@code <K,V>}).
     *
     * <p>{@code readableName()} sur un {@code ReferenceBinding} générique
     * (ex. HashMap) renvoie {@code "java.util.HashMap<K,V>"} — les variables
     * de type déclarées sur la classe sont incluses. On strippe le suffixe
     * {@code <...>} pour obtenir le FQN nu utilisé dans l'URI
     * {@code jdt://decompiled/<fqcn-path>.java}.</p>
     *
     * <p>Filtre AUSSI les types qui ne sont pas navigables (base types
     * {@code int}/{@code void}, variables de type {@code T}, bindings
     * invalides) — retourne null pour ceux-là.</p>
     */
    private static String stripGenericsFromFqn(TypeBinding t) {
        if (t == null) return null;
        try {
            // Ramener les paramétrés/tableaux à leur type sous-jacent.
            if (t.isArrayType()) {
                t = t.leafComponentType();
            }
            if (t.isParameterizedType() && t.original() != null) {
                t = t.original();
            }
            // Filtre les types non navigables (base, variable de type, invalide).
            if (t.isBaseType() || t.isTypeVariable() || !t.isValidBinding()) {
                return null;
            }
            String name = String.valueOf(t.readableName());
            if (name == null || name.isEmpty()) return null;
            // Strippe le suffixe <...> (variables de type déclarées sur la
            // classe — ex. HashMap<K,V> → HashMap).
            int lt = name.indexOf('<');
            return lt > 0 ? name.substring(0, lt) : name;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * @return true si le moteur a de quoi résoudre la plateforme —
     *         au moins un jar de classpath (android.jar inclus) OU une
     *         image jrt (desktop/tests). Sinon, les diagnostics ecj seraient
     *         noyés par « java.lang.Object cannot be resolved ».
     */
    public synchronized boolean isLikelyUsable() {
        if (DISABLED) return false;
        ensureIndex();
        return index.hasBinarySource();
    }

    /** Racines sources de l'environnement (renommage multi-fichiers). */
    List<Path> sourceRoots() {
        return sourceRoots;
    }

    /**
     * @return le JDK home (pour résoudre {@code src.zip} des méthodes
     *         binaires). Exposé package-private pour
     *         {@link JdtSignatureHelp}/{@link JdtInlayHints}.
     */
    java.nio.file.Path jdkHome() {
        return jdkHome;
    }

    /**
     * Définit le GRADLE_USER_HOME ({@code ~/.gradle} du layout CodeIDE).
     * Résolu par {@code ToolPaths.gradleUserHome()} côté app. Le moteur ecj
     * n'en a pas besoin directement (lui consomme le classpath livré par le
     * Gradle Tooling) — mais {@link JdtBinarySourceNames} y cherche les
     * {@code -sources.jar} attachés aux dépendances.
     *
     * <p>Idempotent : appels multiples sans effet de bord. N'invalide PAS
     * le moteur ecj (le GRADLE_USER_HOME est lu paresseusement par les
     * services, pas par le compilateur).</p>
     */
    public void setGradleUserHome(java.nio.file.Path gradleUserHome) {
        this.gradleUserHome = gradleUserHome;
    }

    /**
     * @return le GRADLE_USER_HOME configuré, ou null. Exposé package-private
     *         pour {@link JdtBinarySourceNames}/{@link JdtSignatureHelp}.
     */
    java.nio.file.Path gradleUserHome() {
        return gradleUserHome;
    }

    /**
     * Resolveur de noms de paramètres pour méthodes BINAIRES — instancié
     * paresseusement et partagé entre tous les services
     * ({@link JdtSignatureHelp}, {@link JdtInlayHints}) — sinon chaque
     * signature-help créait un nouveau {@link JdtBinarySourceNames} qui
     * ouvrait 50+ ZipFile (pool LRU) jamais disposés.
     *
     * <p>Vit dans le moteur (pas dans le service) parce que le moteur est
     * unique par serveur LSP — il vit pour la durée de vie du serveur,
     * et {@link #dispose()} ferme son pool LRU.</p>
     */
    private volatile JdtBinarySourceNames binaryNames;

    /**
     * @return le résolveur partagé (créé paresseusement à la première
     *         demande). Le pool LRU vit pour la durée du moteur.
     */
    synchronized JdtBinarySourceNames binaryNames() {
        if (binaryNames == null) {
            binaryNames = new JdtBinarySourceNames(
                    jdkHome(),
                    sourceRootsForBinaryNames(),
                    gradleUserHome);
        }
        return binaryNames;
    }

    /**
     * @return la liste des jars -sources du classpath (convention Maven
     *         {@code foo-sources.jar}). Exposé pour {@link JdtBinarySourceNames}.
     */
    List<Path> sourceRootsForBinaryNames() {
        // Pour l'instant on retourne les classpathJars filtrés par nom —
        // une vraie implémentation passerait par le Gradle Tooling qui
        // sait quel jar est le -sources de quel binaire.
        List<Path> out = new ArrayList<>();
        if (classpathJars == null) return out;
        for (Path jar : classpathJars) {
            String name = jar.getFileName().toString();
            if (name.endsWith("-sources.jar") || name.contains("-sources-")) {
                out.add(jar);
            }
        }
        return out;
    }

    /**
     * @return l'index de l'environnement partagé (recherche de types par
     *         préfixe pour la complétion).
     */
    synchronized JdtCompilerEnvironment typeIndex() {
        ensureIndex();
        return index;
    }

    /**
     * Tous les FQN dont le nom simple est EXACTEMENT
     * {@code simpleName} (jars du classpath + image jrt + sources du
     * projet), triés plus courts d'abord.
     *
     * <p>API publique pour les quick-fixes « Importer X » du
     * {@code CodeActionService} (référence non résolue) — le portage du
     * {@code AddImportQuickFixProvider} du projet de référence qui consulte
     * l'index de noms de classes depuis la lumière du diagnostic.</p>
     *
     * @param simpleName le nom simple exact (ex: {@code "List"})
     * @return les FQN candidats ({@code java.util.List},
     *         {@code java.awt.List}…), éventuellement vide
     */
    public synchronized java.util.List<String> fqnsForSimpleTypeName(String simpleName) {
        if (simpleName == null || simpleName.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        try {
            return typeIndex().fqnsForSimpleName(simpleName);
        } catch (Throwable t) {
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Ordonnanceur à thread unique pour toutes les requêtes qui accèdent au
     * moteur ecj.
     *
     * <p>Le scheduler apporte deux choses au-dessus du verrou intrinsèque
     * {@code synchronized} posé sur {@link #resolveUnit} :</p>
     * <ul>
     *   <li><b>Annulation par supersession</b> — si une nouvelle requête
     *       interactive arrive pour le même fichier avant que la précédente
     *       n'ait commencé, celle-ci est retirée de la file — c'est ce qui
     *       empêche l'empilement de complétions obsolètes sur une même
     *       frappe rapide.</li>
     *   <li><b>Timeout explicite</b> — 3 s pour les requêtes interactives,
     *       15 s pour les requêtes d'analyse. Empêche un fichier pathologique
     *       de bloquer indéfiniment le worker unique.</li>
     * </ul>
     *
     * <p>Le scheduler est créé paresseusement à la première demande et vit
     * pour la durée de vie du moteur. {@link #dispose()} l'arrête.</p>
     */
    private volatile jo.lspjava.engine.JdtEngineScheduler scheduler;

    /**
     * @return le scheduler partagé du moteur — créé paresseusement. Exposé
     *         pour que les services LSP ({@code JavaTextDocumentService}) puissent
     *         soumettre des requêtes interactives avec supersession.
     */
    public jo.lspjava.engine.JdtEngineScheduler scheduler() {
        jo.lspjava.engine.JdtEngineScheduler s = scheduler;
        if (s == null) {
            synchronized (this) {
                s = scheduler;
                if (s == null) {
                    s = new jo.lspjava.engine.JdtEngineScheduler("engine");
                    scheduler = s;
                }
            }
        }
        return s;
    }

    /** Libère les ressources de l'index (changement de classpath). */
    public synchronized void dispose() {
        parseCache.invalidate();
        // P6 : les problèmes portés par le mémo deviennent suspects dès
        // que le classpath change (un type peut se mettre/arrêter de
        // résoudre) — on repart sur des diagnostics complets.
        lastDiagnosis = null;
        // v1.6.0 : mêmes raisons pour l'arbre focalisé retenu — les
        // bindings d'un arbre résolu sous l'ancien classpath ne sont
        // plus fiables (types à présent absents/présents).
        lastFocusedTree = null;
        // L'index et le résolveur binaire ne possèdent AUCUN handle de
        // ZipFile durable (design « handle-free ») — dispose() libère
        // uniquement des structures mémoire. Aucun warning CloseGuard
        // « A resource failed to call ZipFile.close » n'est possible venant
        // de cette couche, même en cas de swap brutal.
        if (binaryNames != null) {
            binaryNames.dispose();
            binaryNames = null;
        }
        if (index != null) {
            index.dispose();
            index = null;
        }
        // Arrête le worker unique du scheduler.
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
    }

    // ─── Internes ─────────────────────────────────────────────────────

    /**
     * Retourne true si l'index de classpath est déjà construit (ou en cours
     * de construction via preWarmIndex). Utilisé par didOpen pour décider
     * s'il faut bloquer ou reporter les diagnostics.
     */
    public boolean isIndexReady() {
        return index != null;
    }

    /**
     * Pré-chauffe l'index en arrière-plan. Appelé depuis
     * {@code JavaLanguageServer.initialized()} pour démarrer la construction
     * de l'index AVANT que l'utilisateur n'ouvre un fichier. Ainsi, au
     * premier didOpen, l'index est déjà construit (ou en cours) — les
     * diagnostics apparaissent immédiatement au lieu d'attendre 2-6 s.
     */
    public void preWarmIndex() {
        if (index != null) return;
        // CAS : garantit qu'un seul thread de pré-chauffe démarre, même si
        // initialized() est notifié plusieurs fois.
        if (!indexPreWarming.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try {
                long start = System.currentTimeMillis();
                ensureIndex();
                long elapsed = System.currentTimeMillis() - start;
                LOG.info("[preWarmIndex] index construit en " + elapsed + " ms");
            } catch (Throwable e) {
                LOG.warning("[preWarmIndex] échec: " + e);
            } finally {
                indexPreWarming.set(false);
            }
        }, "lspjava-index-prewarm");
        t.setDaemon(true);
        t.start();
    }

    private void ensureIndex() {
        // Fast path : index déjà construit.
        if (index != null) return;
        // Lock SÉPARÉ (indexLock) au lieu du verrou synchronized de
        // JdtCompilerEngine : la construction de l'index en arrière-plan
        // (preWarmIndex) ne bloque pas les autres requêtes LSP qui
        // attendent le verrou synchronized de resolveUnit().
        synchronized (indexLock) {
            if (index != null) return; // double-checked
            index = new JdtCompilerEnvironment(sourceRoots, classpathJars, jdkHome);
            // Instrumentation « zéro devinette » — via le logger (jamais
            // stdout : in-process Android, polluerait logcat sans filtrage).
            int skipped = 0;
            String report = index.debugJarIndexReport();
            for (String line : report.split("\n")) {
                if (line.contains("SKIP")) skipped++;
            }
            String summary = "[JavaLspIndex] classpath=" + classpathJars.size()
                    + " indexés=" + index.debugIndexedJarCount()
                    + " paquets=" + index.debugBinaryPackageCount()
                    + " types=" + index.debugSimpleNameCount()
                    + " skippés=" + skipped;
            String probe = index.debugProbeLazyReads();
            LOG.info(summary);
            if (probe != null && !probe.isEmpty()) {
                LOG.info("[JavaLspIndex] sonde jars lazy →\n" + probe);
            }
            if (skipped > 0) {
                LOG.warning("[JavaLspIndex] jars skippés :\n" + report);
            }
        }
    }

    /**
     * Bilan d'indexation du classpath courant (une ligne par jar) —
     * exposition publique pour les logs applicatifs (Timber côté app).
     * Ne déclenche PAS la construction : reflète le dernier état.
     */
    public synchronized String debugIndexReport() {
        JdtCompilerEnvironment idx = index;
        return idx != null ? idx.debugJarIndexReport()
                : "(index non construit)";
    }

    /**
     * True si l'index binaire courant contient des
     * classes R RÉELLES (issues du build Gradle — R.jar aapt2 / classes.jar
     * de lib). Consommé par la couche diagnostics pour le downgrade honnête
     * des « R cannot be resolved » avant première construction.
     *
     * <p><strong>Sémantique moteur-absent</strong> : si l'index n'est pas
     * encore construit, on retourne {@code false} (mode hint) — situation
     * toujours transitoire avant la première passe de compilation.</p>
     */
    public synchronized boolean hasRealRClasses() {
        JdtCompilerEnvironment idx = index;
        return idx != null && idx.hasRealRClasses();
    }

    /**
     * Lecture binaire d'un type du classpath courant pour la complétion de
     * secours (sans bindings). Construit l'index si besoin.
     */
    synchronized org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader
            binaryType(String fqcn) {
        if (DISABLED) return null;
        ensureIndex();
        JdtCompilerEnvironment idx = index;
        return idx == null ? null : idx.binaryType(fqcn);
    }

    /**
     * Renvoie la source d'un type binaire (livré dans un .class
     * du classpath, sans source projet attachée) pour la fonctionnalité
     * « GO TO Definition sur cible binaire ».
     *
     * <p>Stratégie en deux étapes :</p>
     * <ol>
     *   <li><b>Source attachée</b> — cherche un {@code .java} dans
     *       {@code src.zip} du JDK, dans les {@code -sources.jar} du
     *       classpath, puis dans les caches Gradle (via
     *       {@link JdtBinarySourceNames#readSourceAttachment}). Si
     *       trouvé, retourne le texte verbatim (fidelity maximale,
     *       paramètres nommés, javadoc préservée).</li>
     *   <li><b>Stub décompilé</b> — sinon, lit le {@link ClassFileReader}
     *       via {@link #binaryType(String)} et génère un squelette
     *       Java synthétique : en-tête {@code // Decompiled from <fqcn>.class},
     *       déclaration {@code package} + {@code public class/interface},
     *       superclasse + interfaces, champs (signature + modifiers),
     *       méthodes (signature + modifiers + {@code throw new RuntimeException("decompiled stub");}).
     *       Aucun corps réel n'est reconstruit — c'est un squelette de
     *       navigation, pas une recompilation (CFR/Procyon seraient
     *       surdimensionnés pour ce besoin).</li>
     * </ol>
     *
     * @param fqcn fully-qualified name (ex. {@code java.util.HashMap})
     * @return la source (attachée ou stub), ou {@code null} si le type
     *         n'est ni dans une source attachée ni dans le classpath binaire
     */
    public synchronized String decompiledSource(String fqcn) {
        if (fqcn == null || fqcn.isEmpty()) return null;
        if (DISABLED) return null;

        // 1. Source attachée (src.zip du JDK, -sources.jar, caches Gradle).
        try {
            String attached = binaryNames().readSourceAttachment(fqcn);
            if (attached != null) return attached;
        } catch (Throwable t) {
            // Best-effort — retombe sur le stub (tracé en debug).
            LOG.log(Level.FINE, "Lecture de la source attachée échouée — repli sur le stub", t);
        }

        // 2. Stub décompilé depuis le ClassFileReader.
        try {
            ensureIndex();
            JdtCompilerEnvironment idx = index;
            if (idx == null) return null;
            org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader reader =
                    idx.binaryType(fqcn);
            if (reader == null) return null;
            return ClassFileStubBuilder.build(fqcn, reader);
        } catch (Throwable t) {
            LOG.log(Level.FINE, "Construction du stub décompilé échouée", t);
            return null;
        }
    }

    /**
     * Vérifie qu'un FQN est un type binaire (disponible dans le
     * classpath compilé, sans source projet). Utilisé par la couche
     * navigation pour décider si un GO TO Definition qui n'a pas trouvé
     * de source projet doit retomber sur la source décompilée.
     *
     * <p>Heuristique en deux étapes :</p>
     * <ol>
     *   <li>Index classpath — {@link JdtCompilerEnvironment#binaryType(String)}
     *       renvoie un {@code ClassFileReader} si le FQN est dans un JAR
     *       du classpath projet.</li>
     *   <li><b>Fallback JDK / sources</b> — sinon, essaie
     *       {@link JdtBinarySourceNames#readSourceAttachment(String)} :
     *       si la source est trouvable dans {@code src.zip} du JDK ou un
     *       {@code -sources.jar}, c'est aussi un type binaire (JDK).</li>
     * </ol>
     *
     * @param fqcn fully-qualified name
     * @return true si le type est binaire (classpath ou source attachée)
     */
    public synchronized boolean isBinaryType(String fqcn) {
        if (fqcn == null || fqcn.isEmpty() || DISABLED) return false;
        try {
            ensureIndex();
            JdtCompilerEnvironment idx = index;
            if (idx != null && idx.binaryType(fqcn) != null) return true;
        } catch (Throwable t) {
            // Best-effort — continue au fallback (tracé en debug).
            LOG.log(Level.FINE, "Recherche du type binaire échouée — repli source attachée", t);
        }
        // Fallback JDK / source-attached.
        try {
            return binaryNames().readSourceAttachment(fqcn) != null;
        } catch (Throwable t) {
            LOG.log(Level.FINE, "Lecture de la source attachée échouée", t);
            return false;
        }
    }

    /**
     * Wrapper INameEnvironment fin par appel : porte uniquement l'exclusion
     * du type focal (évite la définition dupliquée) au-dessus de l'index
     * partagé — le pattern JdtNameEnvironment/JdtEnvironmentCache de
     * projet de référence. {@code cleanup()} est un no-op : ecj l'appelle entre les
     * phases de résolution, les handles partagés doivent survivre.
     */
    private INameEnvironment wrapEnvironment(Set<String> excludedFqcns) {
        return new INameEnvironment() {
            @Override
            public NameEnvironmentAnswer findType(char[][] compoundTypeName) {
                return resolve(join(compoundTypeName));
            }

            @Override
            public NameEnvironmentAnswer findType(char[] typeName, char[][] packageName) {
                String pkg = join(packageName);
                String fqcn = pkg.isEmpty()
                        ? new String(typeName) : pkg + "." + new String(typeName);
                return resolve(fqcn);
            }

            private NameEnvironmentAnswer resolve(String fqcn) {
                // CHECKPOINT d'annulation coopérative. findType est
                // appelé des milliers de fois par résolution (chaque symbole
                // contre jars/jrt) — c'est notre point de coupure gratuit :
                // un diagnose en cours s'interrompt ici dès que sa passe est
                // supersédée par un didChange plus récent, libérant le verrou
                // resolveUnit pour la complétion interactive (l'exception
                // traverse Compiler.resolve et est absorbée par resolveUnit).
                CancelToken h = resolveCancelHook;
                if (h != null && h.isCancelled()) {
                    throw new OperationCanceledException(
                            "résolution interrompue (checkpoint name-env)");
                }
                if (excludedFqcns.contains(fqcn)) return null;
                return index.find(fqcn);
            }

            @Override
            public boolean isPackage(char[][] parentPackageName, char[] packageName) {
                // Deuxième checkpoint (les résolutions appellent aussi
                // isPackage en rafale pendant la résolution des imports).
                CancelToken h = resolveCancelHook;
                if (h != null && h.isCancelled()) {
                    throw new OperationCanceledException(
                            "résolution interrompue (checkpoint isPackage)");
                }
                String parent = parentPackageName == null ? "" : join(parentPackageName);
                String pkg = parent.isEmpty()
                        ? new String(packageName)
                        : parent + "." + new String(packageName);
                return index.isPackage(pkg);
            }

            @Override
            public void cleanup() {
                // No-op — l'index partagé survit aux phases de résolution.
            }
        };
    }

    /**
     * FQCN d'une unité : si le fichier est sous une racine source, le chemin
     * relatif le donne ; sinon on lit la déclaration de package et on prend
     * le nom du fichier.
     *
     * <p><strong>Racine la plus longue d'abord.</strong> Si la première
     * racine matchée gagnait, la racine projet (workingDir, souvent ajoutée
     * en premier par l'app hôte) matcherait tout fichier du projet avant les
     * vrais source sets → FQCN composé de segments de chemin
     * ({@code app.src.main.java.com.example.r.MainActivity}) → « The
     * declared package does not match the expected package » sur device.
     * En prenant la racine la plus spécifique (la plus profonde), le vrai
     * source set ({@code app/src/main/java}) gagne toujours contre la
     * racine projet, quel que soit l'ordre d'ajout.</p>
     */
    private String fqcnFor(String filePath, String source) {
        if (filePath != null) {
            Path p = Paths.get(filePath);
            Path best = null;
            for (Path root : sourceRoots) {
                if (p.startsWith(root)
                        && (best == null || root.getNameCount() > best.getNameCount())) {
                    best = root;
                }
            }
            if (best != null) {
                String rel = best.relativize(p).toString()
                        .replace('\\', '/');
                if (rel.endsWith(".java")) {
                    rel = rel.substring(0, rel.length() - 5);
                }
                return rel.replace('/', '.');
            }
        }
        // Fallback : déclaration de package + nom de fichier.
        String pkg = "";
        if (source != null) {
            Matcher m = PACKAGE_DECL.matcher(source);
            if (m.find()) pkg = m.group(1);
        }
        String simple = "Anonymous";
        if (filePath != null) {
            String name = new File(filePath).getName();
            if (name.endsWith(".java")) name = name.substring(0, name.length() - 5);
            if (!name.isEmpty()) simple = name;
        }
        return pkg.isEmpty() ? simple : pkg + "." + simple;
    }

    /**
     * Mappe un IProblem ecj vers l'API Diagnostic (offsets → TextRange).
     *
     * @param hierarchyDetails détails P5 (v1.4.0) — pour chaque problème de
     *        hiérarchie cassée positionné en un offset connu, le suffixe
     *        « absent du classpath : FQN (requis par…) » à concaténer au
     *        message ; map vide si aucun problème de hiérarchie
     */
    private static Diagnostic toDiagnostic(IProblem p,
                                           Map<Integer, String> hierarchyDetails) {
        if (p == null) return null;
        // Sans localisation (tâches, problèmes globaux) — ignoré.
        if (p.getSourceStart() < 0 && p.getSourceEnd() < 0) return null;

        Severity severity;
        if (p.isError()) {
            severity = Severity.ERROR;
        } else if (p.isWarning()) {
            severity = Severity.WARNING;
        } else {
            severity = Severity.INFORMATION;
        }

        int start = Math.max(0, p.getSourceStart());
        // IProblem.getSourceEnd() est INCLUSIF ; TextRange est exclusif en fin.
        int end = Math.max(start + 1, p.getSourceEnd() + 1);

        // Arguments STRUCTURÉS du problème ecj — transportés jusqu'aux
        // quick-fixes pour qu'ils branchent sur l'ID et les arguments
        // (pattern JavaProblemCodes du projet de référence), PAS sur le message
        // localisé qui peut varier avec la Locale de l'utilisateur.
        String[] rawArgs = p.getArguments();
        java.util.List<String> args = rawArgs == null || rawArgs.length == 0
                ? java.util.List.of()
                : java.util.List.of(rawArgs);

        // P5 : enrichissement du message de hiérarchie incohérente — le
        // message d'ecj ne nomme que le sous-type cassé, jamais l'ancêtre
        // réellement absent du classpath (l'info dont l'utilisateur a
        // besoin pour ajouter LA bonne dépendance). Best-effort : sans
        // détail à cet offset, le message d'origine est publié tel quel.
        String message = p.getMessage();
        if (p.getID() == IProblem.HierarchyHasProblems && hierarchyDetails != null) {
            String detail = hierarchyDetails.get(p.getSourceStart());
            if (detail != null) {
                message = message + " — " + detail;
            }
        }

        return new Diagnostic(
                new TextRange(start, end),
                severity,
                message,
                "ecj:" + p.getID(),
                "jo.lspjava.jdt",
                args);
    }

    // ── P5 (v1.4.0) : diagnostics de hiérarchie enrichis ─────────────
    //
    // Portage de enrichHierarchyProblems (JdtResolver.kt, projet de référence) :
    // « The hierarchy of the type X is inconsistent » n'est pas actionnable
    // (quel ancêtre manque ? quelle dépendance ajouter ?). L'arbre résolu
    // est en main au moment du diagnose : pour chaque type source dont la
    // hiérarchie est marquée cassée, on parcourt le graphe des super-types
    // pour nommer le premier binding non résoluble et le supertype qui
    // l'exige :
    //
    //   La hiérarchie de X est incohérente — absent du classpath :
    //   com.foo.Bar (requis par com.foo.Baz)

    /**
     * Plafond de profondeur du graphe des super-types — garde-fou contre
     * les hiérarchies pathologiques (cycles résolus partiellement, types
     * paramétrés auto-référents). 64 niveaux couvre largement les
     * hiérarchies réelles (la plus profonde du JDK ≈ 10).
     */
    private static final int MAX_HIERARCHY_DEPTH = 64;

    /**
     * Détails d'hiérarchie par position — retourne une map
     * {@code sourceStart → suffixe de message} pour les types dont la
     * hiérarchie est cassée, en parcourant les bindings de l'arbre résolu.
     *
     * <p>Le problème ecj est rapporté à la position du NOM du type
     * ({@code type.sourceStart()}) : le rapprochement détail ↔ problème se
     * fait donc par position — correspondance exacte, sans collision (un
     * même offset ne peut désigner qu'une seule déclaration).</p>
     *
     * <p>Coût quasi nul en pratique : la map n'est calculée que si au moins
     * un problème {@code HierarchyHasProblems} figure dans le lot, et la
     * traversée ne touche que les types marqués (les hiérarchies saines ne
     * sont jamais descendues).</p>
     *
     * @return map possiblement vide (jamais null) — aucune modification
     *         du message si vide
     */
    private static Map<Integer, String> hierarchyDetails(
            CompilationUnitDeclaration cud, IProblem[] problems) {
        boolean anyHierarchyProblem = false;
        for (IProblem p : problems) {
            if (p.getID() == IProblem.HierarchyHasProblems) {
                anyHierarchyProblem = true;
                break;
            }
        }
        if (!anyHierarchyProblem) return Collections.emptyMap();

        Map<Integer, String> detailByPos = new HashMap<>();
        forEachSourceType(cud.types, binding -> {
            String detail = hierarchyDetailFor(binding);
            if (detail == null) return;
            try {
                detailByPos.put(binding.sourceStart(), detail);
            } catch (Throwable bestEffort) {
                // sourceStart() imprévisible sur un binding exotique —
                // best-effort : ce type garde le message d'origine.
            }
        });
        return detailByPos;
    }

    /**
     * Suffixe de message pour UN type dont la hiérarchie est cassée :
     * collecte les ancêtres non résolubles (DFS superclass + interfaces),
     * puis formate « absent(s) du classpath : FQN (requis par Via), … ».
     *
     * @return le suffixe, ou null si la hiérarchie est saine, si aucun
     *         ancêtre manquant n'est identifiable, ou en cas d'échec de la
     *         traversée (best-effort : le message d'origine est conservé)
     */
    private static String hierarchyDetailFor(SourceTypeBinding binding) {
        if ((binding.tagBits & TagBits.HierarchyHasProblems) == 0) return null;
        // FQN manquant → FQN du supertype qui l'exige (ordre d'insertion
        // stable : le premier chemin exploré est le plus pertinent).
        Map<String, String> missing = new LinkedHashMap<>();
        try {
            collectMissingAncestors(binding, missing, new HashSet<>(), 0);
        } catch (Throwable bestEffort) {
            return null;
        }
        if (missing.isEmpty()) return null;

        StringJoiner joiner = new StringJoiner(", ");
        for (Map.Entry<String, String> e : missing.entrySet()) {
            String via = e.getValue();
            joiner.add(via.isEmpty() || via.equals(e.getKey())
                    ? e.getKey()
                    : e.getKey() + " (requis par " + via + ")");
        }
        return (missing.size() > 1 ? "absents du classpath : "
                                   : "absent du classpath : ") + joiner;
    }

    /**
     * Visite chaque binding de type source de {@code types}, y compris les
     * types membres imbriqués (portage exact de la traversée récursive de
     * projet de référence — les types imbriqués cassés produisent leur propre
     * problème à leur propre position).
     */
    private static void forEachSourceType(TypeDeclaration[] types,
                                          Consumer<SourceTypeBinding> action) {
        if (types == null) return;
        for (TypeDeclaration t : types) {
            if (t != null && t.binding != null) action.accept(t.binding);
            forEachSourceType(t.memberTypes, action);
        }
    }

    /**
     * Parcours en profondeur des super-classes et super-interfaces de
     * {@code type}, enregistrant chaque binding non résoluble (absent du
     * classpath) : {@code FQN manquant → FQN du supertype qui l'exige}.
     *
     * <p>Un supertype valide dont la PROPRE hiérarchie est marquée cassée
     * est descendu : la feuille manquante se trouve derrière lui (cas
     * typique : un jar contient {@code B extends A} mais pas {@code A} —
     * le binding de B est valide, c'est A qu'il faut nommer, « requis par
     * B »). Le set des vus + le plafond de profondeur protègent des
     * cycles.</p>
     */
    private static void collectMissingAncestors(ReferenceBinding type,
                                                Map<String, String> out,
                                                Set<ReferenceBinding> seen,
                                                int depth) {
        if (depth > MAX_HIERARCHY_DEPTH || !seen.add(type)) return;
        List<ReferenceBinding> supers = new ArrayList<>(4);
        try {
            ReferenceBinding superclass = type.superclass();
            if (superclass != null) supers.add(superclass);
        } catch (Throwable bestEffort) {
            // Lecture best-effort — un binding exotique n'interrompt pas
            // la collecte des autres branches.
        }
        try {
            ReferenceBinding[] interfaces = type.superInterfaces();
            if (interfaces != null) {
                for (ReferenceBinding iface : interfaces) {
                    if (iface != null) supers.add(iface);
                }
            }
        } catch (Throwable bestEffort) {
            // Idem pour les interfaces.
        }
        for (ReferenceBinding s : supers) {
            if (!s.isValidBinding()) {
                out.putIfAbsent(new String(s.readableName()),
                                new String(type.readableName()));
            } else if ((s.tagBits & TagBits.HierarchyHasProblems) != 0) {
                collectMissingAncestors(s, out, seen, depth + 1);
            }
        }
    }

    private static long complianceOf(String level) {
        if (level == null) return ClassFileConstants.JDK17;
        String s = level.startsWith("1.") ? level.substring(2) : level;
        int n;
        try {
            n = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return ClassFileConstants.JDK17;
        }
        switch (n) {
            case 8: return ClassFileConstants.JDK1_8;
            case 9: return ClassFileConstants.JDK9;
            case 11: return ClassFileConstants.JDK11;
            case 17: return ClassFileConstants.JDK17;
            case 21: return ClassFileConstants.JDK21;
            default: return ClassFileConstants.JDK17;
        }
    }

    // ─── Scan d'unité : hover + déclarations en UNE traversée ──────────

    /** Une déclaration collectée (binding + plage + nom). */
    private static final class Declaration {
        final Binding binding;
        final int start;
        final int end;
        final char[] name;

        Declaration(Binding binding, int start, int end, char[] name) {
            this.binding = binding;
            this.start = start;
            this.end = end;
            this.name = name;
        }
    }

    /** Résultat du scan : hover du nœud au caret + binding de référence +
     *  toutes les déclarations de l'unité. */
    private static final class UnitScan {
        String hover;
        Binding referenceBinding;
        final List<Declaration> declarations = new ArrayList<>();
    }

    /**
     * Traverse l'AST résolu : collecte toutes les déclarations (pour la
     * navigation) et capture le nœud le plus interne couvrant l'offset
     * (pour le hover). Pattern ContextAnalyzer du projet de référence, adapté.
     */
    private static UnitScan scanUnit(CompilationUnitDeclaration cud,
                                     String source, int offset) {
        UnitScan scan = new UnitScan();
        // "Meilleur" nœud hover = le plus petit qui couvre l'offset.
        final int[] bestLen = {Integer.MAX_VALUE};

        ASTVisitor visitor = new ASTVisitor() {
            void consider(ASTNode node, Binding binding, TypeBinding resolvedType) {
                if (node.sourceStart > offset || node.sourceEnd < offset) return;
                int len = node.sourceEnd - node.sourceStart;
                if (len >= bestLen[0]) return;
                String hover = hoverOf(binding, resolvedType);
                if (hover == null) return;
                bestLen[0] = len;
                scan.hover = hover;
                scan.referenceBinding = binding != null ? binding : resolvedType;
            }

            void declare(ASTNode node, Binding binding, char[] name) {
                if (binding != null && name != null && name.length > 0) {
                    scan.declarations.add(new Declaration(
                            binding, node.sourceStart, node.sourceEnd, name));
                }
            }

            @Override
            public boolean visit(TypeDeclaration td, CompilationUnitScope s) {
                declare(td, td.binding, td.name);
                consider(td, td.binding, null);
                return true;
            }

            @Override
            public boolean visit(TypeDeclaration td, ClassScope s) {
                declare(td, td.binding, td.name);
                consider(td, td.binding, null);
                return true;
            }

            @Override
            public boolean visit(TypeDeclaration td, BlockScope s) {
                declare(td, td.binding, td.name);
                consider(td, td.binding, null);
                return true;
            }

            @Override
            public boolean visit(MethodDeclaration md, ClassScope s) {
                declare(md, md.binding, md.selector);
                consider(md, md.binding, null);
                return true;
            }

            @Override
            public boolean visit(ConstructorDeclaration cd, ClassScope s) {
                declare(cd, cd.binding, cd.selector);
                consider(cd, cd.binding, null);
                return true;
            }

            @Override
            public boolean visit(FieldDeclaration fd, MethodScope s) {
                declare(fd, fd.binding, fd.name);
                return true;
            }

            @Override
            public boolean visit(LocalDeclaration ld, BlockScope s) {
                declare(ld, ld.binding, ld.name);
                consider(ld, ld.binding, null);
                return true;
            }

            @Override
            public boolean visit(Argument a, BlockScope s) {
                declare(a, a.binding, a.name);
                return true;
            }

            @Override
            public boolean visit(MessageSend ms, BlockScope s) {
                consider(ms, ms.binding, null);
                return true;
            }

            @Override
            public boolean visit(SingleNameReference ref, BlockScope s) {
                consider(ref, ref.binding, null);
                return true;
            }

            @Override
            public boolean visit(SingleNameReference ref, ClassScope s) {
                consider(ref, ref.binding, null);
                return true;
            }

            @Override
            public boolean visit(QualifiedNameReference ref, BlockScope s) {
                consider(ref, ref.binding, null);
                return true;
            }

            @Override
            public boolean visit(QualifiedNameReference ref, ClassScope s) {
                consider(ref, ref.binding, null);
                return true;
            }

            @Override
            public boolean visit(FieldReference ref, BlockScope s) {
                consider(ref, ref.binding, null);
                return true;
            }

            @Override
            public boolean visit(FieldReference ref, ClassScope s) {
                consider(ref, ref.binding, null);
                return true;
            }

            @Override
            public boolean visit(SingleTypeReference ref, BlockScope s) {
                consider(ref, null, ref.resolvedType);
                return true;
            }

            @Override
            public boolean visit(SingleTypeReference ref, ClassScope s) {
                consider(ref, null, ref.resolvedType);
                return true;
            }
        };

        try {
            JdtAst.traverseResilient(cud, visitor);
        } catch (Throwable t) {
            // Traverse partielle — on garde ce qui a été collecté
            // (tracée en debug).
            LOG.log(Level.FINE, "Traversée de l'AST interrompue — résultats partiels", t);
        }
        return scan;
    }

    /** Formate le hover markdown d'un binding (pattern InternalMembers.display). */
    private static String hoverOf(Binding binding, TypeBinding resolvedType) {
        StringBuilder sb = new StringBuilder("```java\n");
        try {
            if (binding instanceof MethodBinding m && m.isValidBinding()) {
                if (m.isConstructor()) {
                    sb.append(display(m.declaringClass)).append('(');
                    appendParams(sb, m);
                    sb.append(')');
                } else {
                    if (m.isStatic()) sb.append("static ");
                    if (m.isFinal()) sb.append("final ");
                    sb.append(display(m.returnType)).append(' ')
                            .append(String.valueOf(m.selector)).append('(');
                    appendParams(sb, m);
                    sb.append(')');
                }
            } else if (binding instanceof FieldBinding f && f.isValidBinding()) {
                if (f.isStatic()) sb.append("static ");
                if (f.isFinal()) sb.append("final ");
                sb.append(display(f.type)).append(' ').append(String.valueOf(f.name));
            } else if (binding instanceof LocalVariableBinding l
                    && l.type != null) {
                sb.append(display(l.type)).append(' ')
                        .append(String.valueOf(l.name));
            } else if (binding instanceof ReferenceBinding t && t.isValidBinding()) {
                sb.append(kindOf(t)).append(' ').append(readable(t));
            } else if (binding instanceof PackageBinding pkg) {
                sb.append("package ").append(readable(pkg));
            } else if (resolvedType != null && resolvedType.isValidBinding()) {
                sb.append(kindOf(asReference(resolvedType))).append(' ')
                        .append(readable(resolvedType));
            } else {
                return null;
            }
        } catch (Throwable t) {
            return null;
        }
        sb.append("\n```");
        return sb.toString();
    }

    private static void appendParams(StringBuilder sb, MethodBinding m) {
        for (int i = 0; i < m.parameters.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(display(m.parameters[i]));
        }
    }

    /** Nom court portant les génériques (pattern InternalMembers.display). */
    static String display(TypeBinding t) {
        if (t == null) return "?";
        try {
            return String.valueOf(t.shortReadableName())
                    .replace("? super ", "").replace("? extends ", "");
        } catch (Throwable e) {
            return "?";
        }
    }

    static String readable(TypeBinding t) {
        if (t == null) return "?";
        try {
            return String.valueOf(t.readableName());
        } catch (Throwable e) {
            return "?";
        }
    }

    private static String readable(Binding b) {
        try {
            return String.valueOf(b.readableName());
        } catch (Throwable e) {
            return "?";
        }
    }

    private static ReferenceBinding asReference(TypeBinding t) {
        return t instanceof ReferenceBinding rb ? rb : null;
    }

    private static String kindOf(ReferenceBinding t) {
        if (t == null) return "class";
        // isAnnotationType() DOIT être testé avant isInterface() car une
        // annotation EST une interface en Java (JLS 9.6) — sinon toutes les
        // annotations renverraient "interface" au lieu de "@interface".
        if (t.isAnnotationType()) return "@interface";
        if (t.isInterface()) return "interface";
        if (t.isEnum()) return "enum";
        return "class";
    }

    /**
     * Identité de bindings tolérante : le binding d'une référence peut être
     * la version paramétrée de celui de la déclaration ({@code original()}).
     */
    static boolean sameBinding(Binding a, Binding b) {
        if (a == null || b == null) return false;
        if (a == b) return true;
        try {
            if (a instanceof MethodBinding ma && b instanceof MethodBinding mb) {
                MethodBinding oa = ma.original() != null ? ma.original() : ma;
                MethodBinding ob = mb.original() != null ? mb.original() : mb;
                if (oa == ob) return true;
                return String.valueOf(oa.selector).equals(String.valueOf(ob.selector))
                        && oa.parameters.length == ob.parameters.length
                        && oa.declaringClass != null && ob.declaringClass != null
                        && String.valueOf(oa.declaringClass.readableName())
                                .equals(String.valueOf(ob.declaringClass.readableName()));
            }
            if (a instanceof FieldBinding fa && b instanceof FieldBinding fb) {
                return String.valueOf(fa.name).equals(String.valueOf(fb.name))
                        && fa.declaringClass != null && fb.declaringClass != null
                        && String.valueOf(fa.declaringClass.readableName())
                                .equals(String.valueOf(fb.declaringClass.readableName()));
            }
            // LocalVariableBinding : comparaison par (a) identité Java
            // (cas simple où les deux bindings pointent vers la même
            // déclaration) et (b) à défaut par nom + position source (deux
            // bindings distincts peuvent désigner la même locale si ecj
            // crée des bindings paramétrés, mais c'est rare pour les
            // locales).
            if (a instanceof LocalVariableBinding la && b instanceof LocalVariableBinding lb) {
                if (la == lb) return true;
                // Comparaison par nom + scope englobant.
                if (la.name != null && lb.name != null
                        && String.valueOf(la.name).equals(String.valueOf(lb.name))) {
                    // Même nom — on accepte (les locales shadowing dans
                    // différents scopes ne devraient pas arriver ici car
                    // findDefinition cherche dans le même fichier).
                    return true;
                }
                return false;
            }
        } catch (Throwable t) {
            // Comparaison best-effort — tracée en debug.
            LOG.log(Level.FINE, "Comparaison de bindings échouée — traités comme distincts", t);
        }
        return false;
    }

    /**
     * Plage du NOM d'une déclaration : cherche le nom dans la source à partir
     * du début de la déclaration, avec vérification de frontière de mot
     * (évite de matcher le type du champ).
     */
    private static int[] nameRangeOf(Declaration decl, String source) {
        String name = String.valueOf(decl.name);
        int from = Math.max(0, decl.start);
        int to = Math.min(source.length(), decl.end + 1);
        int idx = from;
        while ((idx = source.indexOf(name, idx)) >= 0 && idx < to) {
            int end = idx + name.length();
            boolean leftOk = idx == 0
                    || !Character.isJavaIdentifierPart(source.charAt(idx - 1));
            boolean rightOk = end >= source.length()
                    || !Character.isJavaIdentifierPart(source.charAt(end));
            if (leftOk && rightOk) {
                return new int[]{idx, end};
            }
            idx = end;
        }
        return null;
    }

    private static String join(char[][] parts) {
        if (parts == null || parts.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('.');
            sb.append(parts[i]);
        }
        return sb.toString();
    }
}
