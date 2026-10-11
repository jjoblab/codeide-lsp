package jo.lspjava.jdt;

import org.eclipse.jdt.internal.compiler.ASTVisitor;
import org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.ASTNode;
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
import org.eclipse.jdt.internal.compiler.ast.Argument;
import org.eclipse.jdt.internal.compiler.lookup.Binding;
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.BlockScope;
import org.eclipse.jdt.internal.compiler.lookup.ClassScope;
import org.eclipse.jdt.internal.compiler.lookup.CompilationUnitScope;
import org.eclipse.jdt.internal.compiler.lookup.MethodScope;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Moteur de navigation GO TO « Implementations » et « Super »
 * (portage des {@code implementationTargets} / {@code superTargets} du
 * KotlinSourceAnalyzer du projet de référence, adapté au moteur ecj de CodeIDE).
 *
 * <h2>Sémantique (parité projet de référence)</h2>
 * <ul>
 *   <li><b>Implementations</b> — les héritiers DIRECTS du type en contexte
 *       (une référence de type au caret, sinon la classe englobante). Chaque
 *       cible est une déclaration dans les SOURCES du projet — CodeIDE n'a
 *       pas de vue décompilée des héritiers binaires, elles sont omises
 *       (« Nothing found in source. » quand il n'en reste aucune).</li>
 *   <li><b>Super</b> — pour un membre qui outrepasse (override) : le
 *       même-nommé dans chaque supertype (label {@code "name  ·  Super"}) ;
 *       sinon les supertypes DIRECTS du type en contexte
 *       (label {@code "Simple  ·  pkg"}).</li>
 * </ul>
 *
 * <h2>Index de sous-types</h2>
 * Là où projet de référence interroge son {@code SubtypeIndex} (producteurs binaire
 * + Java + Kotlin), le moteur ecj ne connaît que l'unité courante : on
 * BALAIE donc les racines sources du projet (pattern
 * {@link JdtRenameEngine#renameProject} — pré-filtre textuel sur le nom
 * simple, puis résolution ecj par fichier candidat, overlay des tampons
 * ouverts consulté AVANT le disque). Bornes : {@link #MAX_WALK_FILES}
 * fichiers et {@link #WALK_BUDGET_MS} ms (résultat partiel rendu plutôt
 * que bloquer).
 *
 * @author jo@Dev
 */
public final class JdtNavigationEngine {

    /** Cap du balayage projet (fichiers candidats résolus). */
    static final int MAX_WALK_FILES = 400;

    /** Budget wall-clock du balayage projet (résultat partiel au-delà). */
    static final long WALK_BUDGET_MS = 8_000L;

    private final JdtCompilerEngine engine;

    public JdtNavigationEngine(JdtCompilerEngine engine) {
        this.engine = engine;
    }

    // ════════════════════════════════════════════════════════════════
    // GO TO — Implementations
    // ════════════════════════════════════════════════════════════════

    /**
     * Les héritiers DIRECTS du type en contexte au caret — une référence de
     * type sous le caret, sinon la classe englobante (sémantique
     * {@code contextTypeFqn} du projet de référence : le type d'une VARIABLE ne
     * compte PAS, seule une référence/une déclaration de type compte).
     *
     * @param filePath chemin du fichier courant
     * @param source   texte VIVANT du fichier courant
     * @param offset   offset du caret dans {@code source}
     * @param overlay  tampons ouverts des AUTRES fichiers (path → source)
     * @return les cibles triées par libellé (peut être vide)
     */
    public List<jo.lspjava.api.NavigationTarget> findImplementations(String filePath, String source,
                                            int offset, Map<String, String> overlay) {
        if (JdtCompilerEngine.DISABLED) return List.of();
        try {
            Context ctx = resolveContext(filePath, source, offset);
            TypeBinding target = ctx.contextType();
            if (!(target instanceof ReferenceBinding rb)) return List.of();
            String targetFqn = String.valueOf(rb.readableName());
            if (targetFqn.isEmpty() || targetFqn.contains("<")) return List.of();
            String simple = simpleName(targetFqn);
            if (simple.isEmpty()) return List.of();

            Map<String, String> ov = overlay == null ? Map.of() : overlay;
            List<jo.lspjava.api.NavigationTarget> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            long deadline = System.currentTimeMillis() + WALK_BUDGET_MS;
            int[] resolved = {0};
            for (Path file : candidateFiles(filePath)) {
                if (resolved[0] >= MAX_WALK_FILES) break;
                if (System.currentTimeMillis() > deadline) break;
                String abs = file.toAbsolutePath().toString();
                String content = contentOf(abs, filePath, source, ov);
                if (content == null || !content.contains(simple)) continue;
                resolved[0]++;
                collectDirectSubtypes(abs, content, rb, targetFqn, out, seen);
            }
            out.sort(Comparator.comparing(h -> h.label));
            return out;
        } catch (Throwable t) {
            return List.of();
        }
    }

    /**
     * Scan d'UNE unité résolue : toute déclaration de type (tous niveaux
     * d'imbrication) dont le superclass / un superInterfaces DIRECT résout
     * vers {@code targetFqn} devient une cible.
     */
    private void collectDirectSubtypes(String abs, String content,
                                       ReferenceBinding target, String targetFqn,
                                       List<jo.lspjava.api.NavigationTarget> out, Set<String> seen) {
        try {
            CompilationUnitDeclaration cud = engine.resolveUnit(abs, content, false);
            if (cud == null) return;
            List<TypeDeclaration> types = new ArrayList<>();
            JdtAst.traverseResilient(cud, typeCollector(types));
            for (TypeDeclaration td : types) {
                if (td == null || td.name == null || td.name.length == 0) continue;
                if (td.binding == null) continue;
                if (!extendsOrImplements(td.binding, target, targetFqn)) continue;
                int[] name = nameRange(content, td.sourceStart, td.sourceEnd,
                        String.valueOf(td.name));
                if (name == null) continue;
                String fqn = String.valueOf(td.binding.readableName());
                String key = abs + ":" + name[0];
                if (seen.add(key)) {
                    out.add(new jo.lspjava.api.NavigationTarget(abs, name[0], name[1], navLabel(fqn)));
                }
            }
        } catch (Throwable ignored) {
            // Fichier pathologique — ignoré.
        }
    }

    /** True si {@code sub} déclare DIRECTEMENT {@code target} dans sa clause
     *  extends/implements (comparaison par FQN normalisé — gère les
     *  paramétrés via {@code original()}). */
    private static boolean extendsOrImplements(ReferenceBinding sub,
                                               ReferenceBinding target,
                                               String targetFqn) {
        try {
            ReferenceBinding sup = sub.superclass();
            if (isSameType(sup, target, targetFqn)) return true;
            ReferenceBinding[] ifaces = sub.superInterfaces();
            if (ifaces != null) {
                for (ReferenceBinding i : ifaces) {
                    if (isSameType(i, target, targetFqn)) return true;
                }
            }
        } catch (Throwable ignored) {
            // Hiérarchie non résolue — pas de match.
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════════
    // GO TO — Super
    // ════════════════════════════════════════════════════════════════

    /**
     * Les cibles Super au caret : pour un membre outrepassant (méthode), le
     * même-nommé dans chaque supertype ; sinon les supertypes DIRECTS du
     * type en contexte.
     *
     * @param filePath chemin du fichier courant
     * @param source   texte VIVANT du fichier courant
     * @param offset   offset du caret
     * @param overlay  tampons ouverts des AUTRES fichiers (path → source)
     * @return les cibles (peut être vide)
     */
    public List<jo.lspjava.api.NavigationTarget> findSuper(String filePath, String source,
                                  int offset, Map<String, String> overlay) {
        if (JdtCompilerEngine.DISABLED) return List.of();
        try {
            Context ctx = resolveContext(filePath, source, offset);
            Map<String, String> ov = overlay == null ? Map.of() : overlay;

            // 1) Chemin MEMBRE : le caret est sur une méthode (déclaration,
            //    invocation, référence) qui outrepasse un supertype.
            if (ctx.binding instanceof MethodBinding m && m.selector != null
                    && m.declaringClass != null) {
                List<jo.lspjava.api.NavigationTarget> members = superMethodHits(m, ov);
                if (!members.isEmpty()) return members;
            }

            // 2) Chemin TYPE : les supertypes DIRECTS du type en contexte.
            TypeBinding t = ctx.contextType();
            if (!(t instanceof ReferenceBinding rb)) return List.of();
            List<jo.lspjava.api.NavigationTarget> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (ReferenceBinding sup : directSupertypes(rb)) {
                String fqn = String.valueOf(sup.readableName());
                SourceDecl decl = locateTypeDeclaration(fqn, ov);
                if (decl == null) continue; // binaire (classpath) — aucune source
                int[] name = nameRange(decl.content, decl.type.sourceStart,
                        decl.type.sourceEnd, String.valueOf(decl.type.name));
                if (name == null) continue;
                String key = decl.path + ":" + name[0];
                if (seen.add(key)) {
                    out.add(new jo.lspjava.api.NavigationTarget(decl.path, name[0], name[1], navLabel(fqn)));
                }
            }
            out.sort(Comparator.comparing(h -> h.label));
            return out;
        } catch (Throwable t) {
            return List.of();
        }
    }

    /**
     * Le même-nommé (même sélecteur, même arité) dans chaque supertype
     * transitif de la classe déclarante — les cibles « member super » de
     * projet de référence (label {@code "name  ·  Super"}).
     */
    private List<jo.lspjava.api.NavigationTarget> superMethodHits(MethodBinding m, Map<String, String> overlay) {
        List<jo.lspjava.api.NavigationTarget> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String selector = String.valueOf(m.selector);
        for (ReferenceBinding sup : allSupertypes(m.declaringClass)) {
            try {
                for (MethodBinding sm : sup.getMethods(selector.toCharArray())) {
                    if (sm == null || sm.parameters == null) continue;
                    if (sm.parameters.length != m.parameters.length) continue;
                    // Filtre aussi par compatibilité des types de paramètres
                    // (erasure) et exclut les private.
                    if (sm.isPrivate()) continue;
                    if (!parametersCompatible(sm, m)) continue;
                    String supFqn = String.valueOf(sup.readableName());
                    SourceDecl decl = locateTypeDeclaration(supFqn, overlay);
                    if (decl == null) continue; // supertype binaire — pas de source
                    int[] name = methodNameRange(decl, selector,
                            sm.parameters.length);
                    if (name == null) continue;
                    String key = decl.path + ":" + name[0];
                    if (seen.add(key)) {
                        String label = selector + "  ·  " + simpleName(supFqn);
                        out.add(new jo.lspjava.api.NavigationTarget(decl.path, name[0], name[1], label));
                    }
                }
            } catch (Throwable ignored) {
                // Supertype pathologique — ignoré.
            }
        }
        return out;
    }

    /**
     * Vérifie que deux méthodes ont des paramètres compatibles
     * (même erasure). Utilisé pour filtrer les surcharges dans superMethodHits.
     */
    private static boolean parametersCompatible(MethodBinding a, MethodBinding b) {
        if (a.parameters == null || b.parameters == null) return true; // best-effort
        if (a.parameters.length != b.parameters.length) return false;
        for (int i = 0; i < a.parameters.length; i++) {
            try {
                TypeBinding ta = a.parameters[i].erasure();
                TypeBinding tb = b.parameters[i].erasure();
                if (ta == null || tb == null) continue;
                String na = String.valueOf(ta.readableName());
                String nb = String.valueOf(tb.readableName());
                if (!na.equals(nb)) return false;
            } catch (Throwable ignored) {
                // best-effort — si on ne peut pas comparer, on accepte.
            }
        }
        return true;
    }

    /** La plage du NOM de la déclaration de méthode {@code selector} (même
     *  arité si plusieurs surcharges) dans le fichier de {@code decl}. */
    private static int[] methodNameRange(SourceDecl decl, String selector, int arity) {
        try {
            if (decl.type.methods != null) {
                AbstractMethodDeclaration fallback = null;
                for (AbstractMethodDeclaration md : decl.type.methods) {
                    if (md == null || md.selector == null) continue;
                    if (!selector.equals(String.valueOf(md.selector))) continue;
                    if (md.arguments != null && md.arguments.length != arity) {
                        if (fallback == null) fallback = md;
                        continue;
                    }
                    return nameRange(decl.content, md.sourceStart, md.sourceEnd,
                            selector);
                }
                if (fallback != null) {
                    return nameRange(decl.content, fallback.sourceStart,
                            fallback.sourceEnd, selector);
                }
            }
        } catch (Throwable ignored) {
            // Best-effort.
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    // Contexte au caret
    // ════════════════════════════════════════════════════════════════

    /** Binding au caret + type englobant (source RÉDUITE par SourceFocuser). */
    private static final class Context {
        Binding binding;
        TypeDeclaration enclosingType;

        /**
         * Le TYPE EN CONTEXTE (portage {@code typeRefFqnAt} +
         * {@code contextTypeFqn} du projet de référence) : le type sous le caret
         * (référence ou déclaration) ; sinon la classe ENGLOBANTE — le type
         * d'une variable/méthode ne compte PAS.
         */
        TypeBinding contextType() {
            if (binding instanceof TypeBinding t) {
                return normalize(t);
            }
            return enclosingType != null && enclosingType.binding != null
                    ? enclosingType.binding : null;
        }
    }

    /**
     * Résout l'unité COURANTE (source réduite — les en-têtes de classes
     * survivent, seuls les corps de méthodes hors portée sont effacés) et
     * collecte le binding le plus interne couvrant le caret + le
     * TypeDeclaration englobant le plus interne.
     */
    private Context resolveContext(String filePath, String source, int offset) {
        Context ctx = new Context();
        SourceFocuser.Focused focused = SourceFocuser.focusAndMap(source, offset);
        String workSource = focused.source();
        int workOffset = focused.offset();
        CompilationUnitDeclaration cud = engine.resolveUnit(filePath, workSource, false);
        if (cud == null) return ctx;

        final int[] bestLen = {Integer.MAX_VALUE};
        final int[] bestTypeLen = {Integer.MAX_VALUE};
        ASTVisitor visitor = new ASTVisitor() {
            void consider(ASTNode node, Binding binding, TypeBinding resolvedType) {
                if (node.sourceStart > workOffset || node.sourceEnd < workOffset) return;
                if (binding == null && resolvedType == null) return;
                int len = node.sourceEnd - node.sourceStart;
                if (len >= bestLen[0]) return;
                bestLen[0] = len;
                ctx.binding = binding != null ? binding : resolvedType;
            }

            void considerType(TypeDeclaration td) {
                if (td == null || td.name == null || td.name.length == 0) return;
                // ecj : sourceStart/sourceEnd d'un TypeDeclaration = la
                // plage du NOM SEUL ; la déclaration COMPLÈTE (corps inclus)
                // vit dans declarationSourceStart/declarationSourceEnd —
                // c'est ELLE qui doit couvrir le caret pour que la classe
                // englobante soit détectée.
                int from = td.declarationSourceStart != 0
                        ? td.declarationSourceStart : td.sourceStart;
                int to = td.declarationSourceEnd != 0
                        ? td.declarationSourceEnd : td.sourceEnd;
                if (from > workOffset || to < workOffset) return;
                int len = to - from;
                if (len >= bestTypeLen[0]) return;
                bestTypeLen[0] = len;
                ctx.enclosingType = td;
            }

            @Override
            public boolean visit(TypeDeclaration td, CompilationUnitScope s) {
                considerType(td);
                consider(td, td.binding, null);
                return true;
            }

            @Override
            public boolean visit(TypeDeclaration td, ClassScope s) {
                considerType(td);
                consider(td, td.binding, null);
                return true;
            }

            @Override
            public boolean visit(TypeDeclaration td, BlockScope s) {
                considerType(td);
                consider(td, td.binding, null);
                return true;
            }

            @Override
            public boolean visit(MethodDeclaration md, ClassScope s) {
                consider(md, md.binding, null);
                return true;
            }

            @Override
            public boolean visit(ConstructorDeclaration cd, ClassScope s) {
                consider(cd, cd.binding, null);
                return true;
            }

            @Override
            public boolean visit(FieldDeclaration fd, MethodScope s) {
                consider(fd, fd.binding, null);
                return true;
            }

            @Override
            public boolean visit(LocalDeclaration ld, BlockScope s) {
                consider(ld, ld.binding, null);
                return true;
            }

            @Override
            public boolean visit(Argument a, BlockScope s) {
                consider(a, a.binding, null);
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
        } catch (Throwable ignored) {
            // Traversée partielle — on garde ce qui est collecté.
        }
        return ctx;
    }

    // ════════════════════════════════════════════════════════════════
    // Hiérarchie de bindings
    // ════════════════════════════════════════════════════════════════

    /** Supertypes DIRECTS de {@code t} (superclass + superInterfaces),
     *  normalisés (paramétrés → original), valides uniquement. */
    private static List<ReferenceBinding> directSupertypes(ReferenceBinding t) {
        List<ReferenceBinding> out = new ArrayList<>();
        if (t == null) return out;
        try {
            ReferenceBinding sup = t.superclass();
            addSupertype(out, sup);
            ReferenceBinding[] ifaces = t.superInterfaces();
            if (ifaces != null) {
                for (ReferenceBinding i : ifaces) addSupertype(out, i);
            }
        } catch (Throwable ignored) {
            // Hiérarchie non résolue — liste partielle.
        }
        return out;
    }

    /** Clôture TRANSITIVE des supertypes (superclass + interfaces), BFS avec
     *  garde anti-cycle et cap de profondeur, ordre déterministe. */
    private static List<ReferenceBinding> allSupertypes(ReferenceBinding start) {
        List<ReferenceBinding> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<ReferenceBinding> queue = new ArrayList<>(directSupertypes(start));
        int depth = 0;
        while (!queue.isEmpty() && out.size() < 64 && depth < 32) {
            List<ReferenceBinding> next = new ArrayList<>();
            for (ReferenceBinding sup : queue) {
                if (sup == null) continue;
                String key = String.valueOf(sup.readableName());
                if (!seen.add(key)) continue;
                out.add(sup);
                next.addAll(directSupertypes(sup));
            }
            queue = next;
            depth++;
        }
        return out;
    }

    private static void addSupertype(List<ReferenceBinding> out, ReferenceBinding sup) {
        if (sup == null || !sup.isValidBinding()) return;
        // java.lang.Object implicite : aucune source navigable, écarté d'office
        // par locateTypeDeclaration — pas la peine de le garder.
        if ("java.lang.Object".equals(String.valueOf(sup.readableName()))) return;
        ReferenceBinding norm = asReference(normalize(sup));
        if (norm != null) out.add(norm);
    }

    /** Ramène un TypeBinding paramétré/brut/tableau à son original. */
    private static TypeBinding normalize(TypeBinding t) {
        if (t == null) return null;
        try {
            if (t.isArrayType()) t = t.leafComponentType();
            if ((t.isParameterizedType() || t.isRawType())) {
                TypeBinding orig = t.original();
                if (orig != null) t = orig;
            }
            if (t.isBaseType() || t.isTypeVariable() || !t.isValidBinding()) return null;
        } catch (Throwable ignored) {
            // Best-effort — on garde le binding brut.
        }
        return t;
    }

    private static ReferenceBinding asReference(TypeBinding t) {
        return t instanceof ReferenceBinding rb ? rb : null;
    }

    /** Égalité FQN entre un supertype candidat et la cible. */
    private static boolean isSameType(ReferenceBinding sup,
                                      ReferenceBinding target, String targetFqn) {
        if (sup == null || !sup.isValidBinding()) return false;
        ReferenceBinding norm = asReference(normalize(sup));
        if (norm == null) return false;
        if (norm == target) return true;
        return targetFqn.equals(String.valueOf(norm.readableName()));
    }

    // ════════════════════════════════════════════════════════════════
    // Localisation des déclarations (cross-fichier)
    // ════════════════════════════════════════════════════════════════

    /** Une déclaration de type localisée dans les sources du projet. */
    private static final class SourceDecl {
        final String path;
        final String content;
        final TypeDeclaration type;

        SourceDecl(String path, String content, TypeDeclaration type) {
            this.path = path;
            this.content = content;
            this.type = type;
        }
    }

    /**
     * Portage de {@code declarationLocation} du projet de référence : le fichier
     * déclarant le type {@code fqn} (les types IMBRIQUÉS vivent dans le
     * fichier de leur classe EXTERIEURE — remontée progressive des
     * segments), puis la déclaration correspondante par comparaison du
     * readableName des bindings.
     *
     * @return (chemin, contenu, TypeDeclaration) ou null si {@code fqn}
     *         n'est pas déclaré dans les sources du projet.
     */
    private SourceDecl locateTypeDeclaration(String fqn, Map<String, String> overlay) {
        if (fqn == null || fqn.isEmpty() || fqn.contains("<")) return null;
        String[] segments = fqn.split("\\.");
        for (int n = segments.length; n >= 1; n--) {
            StringBuilder rel = new StringBuilder();
            for (int i = 0; i < n; i++) {
                if (i > 0) rel.append('/');
                rel.append(segments[i]);
            }
            rel.append(".java");
            for (Path root : engine.sourceRoots()) {
                Path candidate = root.resolve(rel.toString());
                String abs = candidate.toAbsolutePath().toString();
                String content = contentOf(abs, null, null, overlay);
                if (content == null) continue;
                try {
                    CompilationUnitDeclaration cud =
                            engine.resolveUnit(abs, content, false);
                    if (cud == null) continue;
                    List<TypeDeclaration> types = new ArrayList<>();
                    JdtAst.traverseResilient(cud, typeCollector(types));
                    for (TypeDeclaration td : types) {
                        if (td == null || td.name == null
                                || td.name.length == 0 || td.binding == null) {
                            continue;
                        }
                        if (fqn.equals(String.valueOf(td.binding.readableName()))) {
                            return new SourceDecl(abs, content, td);
                        }
                    }
                } catch (Throwable ignored) {
                    // Fichier pathologique — suivant.
                }
            }
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    // Balayage des fichiers candidats
    // ════════════════════════════════════════════════════════════════

    /**
     * Les fichiers candidats du balayage : le fichier COURANT d'abord (une
     * sous-classe dans le même fichier compte), puis tous les {@code .java}
     * des racines sources (ordre déterministe, dédupliqués).
     */
    private List<Path> candidateFiles(String filePath) {
        List<Path> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (filePath != null && !filePath.isEmpty()
                && java.nio.file.Files.isRegularFile(Path.of(filePath))) {
            seen.add(Path.of(filePath).toAbsolutePath().toString());
            out.add(Path.of(filePath));
        }
        for (Path root : engine.sourceRoots()) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".java")
                                && Files.isRegularFile(p))
                        .map(p -> p.toAbsolutePath().toString())
                        .sorted()
                        .forEach(abs -> {
                            if (seen.add(abs)) out.add(Path.of(abs));
                        });
            } catch (Throwable ignored) {
                // Racine illisible — ignorée.
            }
        }
        return out;
    }

    /**
     * Le contenu d'un fichier : overlay (tampon vivant) d'abord, disque
     * ensuite — le fichier COURANT utilise toujours sa source passée.
     */
    private static String contentOf(String abs, String currentPath,
                                    String currentSource, Map<String, String> overlay) {
        if (currentPath != null && abs.equals(currentPath)) {
            return currentSource;
        }
        if (overlay.containsKey(abs)) return overlay.get(abs);
        try {
            return new String(Files.readAllBytes(Path.of(abs)),
                    StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Visiteur collectant TOUTES les déclarations de types (imbriquées et
     *  locales incluses) — l'AST est traversé de façon résiliente. */
    private static ASTVisitor typeCollector(List<TypeDeclaration> out) {
        return new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration td, CompilationUnitScope s) {
                out.add(td);
                return true;
            }

            @Override
            public boolean visit(TypeDeclaration td, ClassScope s) {
                out.add(td);
                return true;
            }

            @Override
            public boolean visit(TypeDeclaration td, BlockScope s) {
                out.add(td);
                return true;
            }
        };
    }

    // ════════════════════════════════════════════════════════════════
    // Utilitaires texte
    // ════════════════════════════════════════════════════════════════

    /**
     * La plage du JETON {@code name} dans [{@code from}, {@code to}] avec
     * vérification de frontière de mot (portage {@code nameRangeOf} du
     * moteur — évite de matcher le type d'un champ ou un préfixe).
     */
    static int[] nameRange(String content, int from, int to, String name) {
        if (content == null || name == null || name.isEmpty()) return null;
        from = Math.max(0, from);
        to = Math.min(content.length(), to + 1);
        int idx = from;
        while ((idx = content.indexOf(name, idx)) >= 0 && idx < to) {
            int end = idx + name.length();
            boolean leftOk = idx == 0
                    || !Character.isJavaIdentifierPart(content.charAt(idx - 1));
            boolean rightOk = end >= content.length()
                    || !Character.isJavaIdentifierPart(content.charAt(end));
            if (leftOk && rightOk) return new int[]{idx, end};
            idx = end;
        }
        return null;
    }

    /** « Simple  ·  pkg » (ou juste le nom simple au paquet par défaut) —
     *  le libellé picker du projet de référence ({@code navLabel}). */
    static String navLabel(String fqn) {
        String simple = simpleName(fqn);
        int lastDot = fqn.lastIndexOf('.');
        String pkg = lastDot > 0 ? fqn.substring(0, lastDot) : "";
        return pkg.isEmpty() ? simple : simple + "  ·  " + pkg;
    }

    /** Le dernier segment d'un FQN. */
    static String simpleName(String fqn) {
        if (fqn == null || fqn.isEmpty()) return "";
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }
}
