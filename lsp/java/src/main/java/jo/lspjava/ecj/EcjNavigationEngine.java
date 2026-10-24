package jo.lspjava.ecj;

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
import org.eclipse.jdt.internal.compiler.lookup.FieldBinding;
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.VariableBinding;
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
 * KotlinSourceAnalyzer du projet tiers de référence, adapté au moteur ecj de CodeIDE).
 *
 * <h2>Sémantique (parité projet tiers de référence)</h2>
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
 * Là où projet tiers de référence interroge son {@code SubtypeIndex} (producteurs binaire
 * + Java + Kotlin), le moteur ecj ne connaît que l'unité courante : on
 * BALAIE donc les racines sources du projet (pattern
 * {@link EcjRenameEngine#renameProject} — pré-filtre textuel sur le nom
 * simple, puis résolution ecj par fichier candidat, overlay des tampons
 * ouverts consulté AVANT le disque). Bornes : {@link #MAX_WALK_FILES}
 * fichiers et {@link #WALK_BUDGET_MS} ms (résultat partiel rendu plutôt
 * que bloquer).
 *
 * @author jo@Dev
 */
public final class EcjNavigationEngine {

    /** Cap du balayage projet (fichiers candidats résolus). */
    static final int MAX_WALK_FILES = 400;

    /** Budget wall-clock du balayage projet (résultat partiel au-delà). */
    static final long WALK_BUDGET_MS = 8_000L;

    private final EcjCompilerEngine engine;

    public EcjNavigationEngine(EcjCompilerEngine engine) {
        this.engine = engine;
    }

    // ════════════════════════════════════════════════════════════════
    // GO TO — Implementations
    // ════════════════════════════════════════════════════════════════

    /**
     * Les héritiers DIRECTS du type en contexte au caret — une référence de
     * type sous le caret, sinon la classe englobante (sémantique
     * {@code contextTypeFqn} du projet tiers de référence : le type d'une VARIABLE ne
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
        if (EcjCompilerEngine.DISABLED) return List.of();
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
            EcjAst.traverseResilient(cud, typeCollector(types));
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
        if (EcjCompilerEngine.DISABLED) return List.of();
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
     * projet tiers de référence (label {@code "name  ·  Super"}).
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
         * {@code contextTypeFqn} du projet tiers de référence) : le type sous le caret
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
                consider(ref, EcjAst.bindingAuCaret(ref, workOffset), null);
                return true;
            }

            @Override
            public boolean visit(QualifiedNameReference ref, ClassScope s) {
                consider(ref, EcjAst.bindingAuCaret(ref, workOffset), null);
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
            EcjAst.traverseResilient(cud, visitor);
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
     * Portage de {@code declarationLocation} du projet tiers de référence : le fichier
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
        // BINAIRE (index classpath OU source attachée) : aucune source
        // projet à localiser — le GUARD évite un balayage complet
        // inutile (ex. findSuper sur java.lang.Thread).
        try {
            if (engine.isBinaryType(fqn)) return null;
        } catch (Throwable ignored) {
            // Index indisponible — best-effort, on continue.
        }
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
                    EcjAst.traverseResilient(cud, typeCollector(types));
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
        // Repli BALAYAGE BORNÉ (ADR 0209 — audit navigation) : la
        // convention de chemin échoue quand le fichier porte PLUSIEURS
        // types top-level ou un type NON PUBLIC sans son nom — la
        // PREUVE par readableName résout (pré-filtre textuel sur le nom
        // simple, budget MAX_WALK_FILES/WALK_BUDGET_MS comme le
        // balayage d'héritiers).
        return locateTypeDeclarationParBalayage(fqn, overlay);
    }

    /**
     * Le repli balayage de {@link #locateTypeDeclaration} : chaque
     * fichier candidat (pré-filtré sur le nom simple) est résolu et ses
     * types (imbriqués compris) appariés par FQN exact.
     */
    private SourceDecl locateTypeDeclarationParBalayage(String fqn,
                                                        Map<String, String> overlay) {
        String simple = simpleName(fqn);
        if (simple.isEmpty()) return null;
        Map<String, String> ov = overlay == null ? Map.of() : overlay;
        long deadline = System.currentTimeMillis() + WALK_BUDGET_MS;
        int[] resolved = {0};
        for (Path file : candidateFiles(null)) {
            if (resolved[0] >= MAX_WALK_FILES) break;
            if (System.currentTimeMillis() > deadline) break;
            String abs = file.toAbsolutePath().toString();
            String content = contentOf(abs, null, null, ov);
            if (content == null || !content.contains(simple)) continue;
            resolved[0]++;
            try {
                CompilationUnitDeclaration cud = engine.resolveUnit(abs, content, false);
                if (cud == null) continue;
                List<TypeDeclaration> types = new ArrayList<>();
                EcjAst.traverseResilient(cud, typeCollector(types));
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
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    // GO TO — Définition PROJET (cross-fichiers, ADR 0209)
    // ════════════════════════════════════════════════════════════════

    /**
     * Une cible de DÉFINITION dans les sources du projet (ADR 0209) : le
     * chemin du fichier déclarant + la plage du JETON nom — le pendant
     * multi-fichiers du {@code TextRange} intra-fichier de
     * {@code EcjCompilerEngine.findDefinition}.
     */
    public record CibleDefinition(String chemin, int debut, int fin) {}

    /**
     * La DÉFINITION du symbole au caret dans un AUTRE fichier des
     * sources du PROJET (ADR 0209 — comble le vide du chemin
     * JDT-first, restreint au fichier courant) : le binding ecj prouve
     * la CIBLE (méthode, champ, type — jamais un nom deviné), la classe
     * DÉCLARANTE est localisée par {@link #locateTypeDeclaration}
     * (convention de chemin puis parse, overlay des tampons ouverts
     * consulté AVANT le disque), et le NOM visé y est borné par
     * {@link #methodNameRange}/{@link #fieldNameRange}/nameRange.
     *
     * <p>Ordre de la façade : intra-fichier d'abord (bindings vifs), ce
     * chemin ensuite, fallback BINAIRE ({@code jdt://decompiled/…}) en
     * dernier — une cible PROJET ne doit JAMAIS atterrir dans le stub
     * décompilé.</p>
     *
     * <p>Bornes honnêtes (D3) : locale/paramètre → {@code null} (ils
     * vivent dans le fichier courant, déjà servi) ; classe déclarante
     * BINAIRE → {@code null} (le fallback décompilé de la façade prend
     * le relais) ; type anonyme interne → {@code null}.</p>
     *
     * @param filePath chemin du fichier courant
     * @param source   texte VIVANT du fichier courant
     * @param offset   offset du caret dans {@code source}
     * @param overlay  tampons ouverts des AUTRES fichiers (path → source)
     * @return la cible (chemin + plage du nom), ou null
     */
    public CibleDefinition findDefinitionProject(String filePath, String source,
                                                 int offset, Map<String, String> overlay) {
        if (EcjCompilerEngine.DISABLED) return null;
        try {
            Context ctx = resolveContext(filePath, source, offset);
            Map<String, String> ov = overlay == null ? Map.of() : overlay;
            Binding b = ctx.binding;
            if (b == null) return null;
            // 1) MÉTHODE (invocation, déclaration, référence) : la
            //    classe DÉCLARANTE porte la source ; un constructeur
            //    vise le NOM DE LA CLASSE elle-même.
            if (b instanceof MethodBinding m && m.declaringClass != null) {
                if (m.isConstructor()) {
                    return cibleTypeProjet(m.declaringClass, ov);
                }
                SourceDecl sd = classeDeclaranteLocalisee(m.declaringClass, ov);
                if (sd == null) return null; // binaire — fallback décompilé
                int arity = m.parameters == null ? 0 : m.parameters.length;
                int[] name = methodNameRange(sd, String.valueOf(m.selector), arity);
                return name == null ? null : new CibleDefinition(sd.path, name[0], name[1]);
            }
            // 2) CHAMP (référence, déclaration) : la classe déclarante.
            if (b instanceof FieldBinding f && f.declaringClass != null) {
                SourceDecl sd = classeDeclaranteLocalisee(f.declaringClass, ov);
                if (sd == null) return null;
                int[] name = fieldNameRange(sd, String.valueOf(f.name));
                return name == null ? null : new CibleDefinition(sd.path, name[0], name[1]);
            }
            // 3) TYPE (référence de type au caret) : sa déclaration.
            if (b instanceof TypeBinding) {
                return cibleTypeProjet(normalize((TypeBinding) b), ov);
            }
            // 4) Locale/paramètre : le fichier COURANT les sert déjà
            //    (chemin intra-fichier) — null honnête, jamais de saut
            //    hasardeux.
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * La déclaration du TYPE du symbole au caret dans un AUTRE fichier
     * des sources du projet (ADR 0209 — pendant multi-fichiers du
     * {@code findTypeDefinition} intra-fichier) : le type du symbole —
     * une référence de type se suffit, une variable/paramètre/champ
     * donne son type, un appel donne son type de retour (miroir
     * {@code typeBindingOf} du moteur).
     *
     * @return la cible (chemin + plage du nom), ou null (type binaire —
     *         le fallback décompilé de la façade prend le relais)
     */
    public CibleDefinition findTypeDefinitionProject(String filePath, String source,
                                                     int offset, Map<String, String> overlay) {
        if (EcjCompilerEngine.DISABLED) return null;
        try {
            Context ctx = resolveContext(filePath, source, offset);
            TypeBinding t = typeDuSymbole(ctx.binding);
            if (t == null) return null;
            return cibleTypeProjet(t, overlay == null ? Map.of() : overlay);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Le TYPE du binding au caret (miroir moteur {@code typeBindingOf})
     * : une référence de type se suffit ; une variable/paramètre/champ
     * donne son type ; un appel donne son type de retour. Tableau →
     * élément feuille ; paramétré → original ; base/variable de type →
     * null (aucune déclaration navigable).
     */
    private static TypeBinding typeDuSymbole(Binding b) {
        if (b == null) return null;
        try {
            TypeBinding t = null;
            if (b instanceof TypeBinding tb) {
                t = tb;
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
            if (t.isBaseType() || t.isTypeVariable() || !t.isValidBinding()) return null;
            return t;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** La classe déclarante LOCALISÉE dans les sources du projet (ou
     *  null — binaire ou type interne $ : le fallback décompilé de la
     *  façade prend le relais, jamais une localisation inventée). */
    private SourceDecl classeDeclaranteLocalisee(ReferenceBinding classe,
                                                  Map<String, String> overlay) {
        String fqn = String.valueOf(classe.readableName());
        if (fqn.isEmpty() || fqn.contains("<") || fqn.contains("$")) return null;
        return locateTypeDeclaration(fqn, overlay);
    }

    /** La DÉCLARATION d'un TYPE projet, bornée au nom (ou null). */
    private CibleDefinition cibleTypeProjet(TypeBinding t, Map<String, String> overlay) {
        if (!(t instanceof ReferenceBinding rb)) return null;
        SourceDecl sd = classeDeclaranteLocalisee(rb, overlay);
        if (sd == null) return null;
        try {
            int[] name = nameRange(sd.content, sd.type.sourceStart, sd.type.sourceEnd,
                    String.valueOf(sd.type.name));
            return name == null ? null : new CibleDefinition(sd.path, name[0], name[1]);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** La plage du JETON du champ {@code nom} dans la déclaration de
     *  type de {@code decl} (premier homonyme — les champs ne se
     *  surchargent pas). Les CONSTANTES d'enum vivent dans fields. */
    private static int[] fieldNameRange(SourceDecl decl, String nom) {
        try {
            if (decl.type.fields == null) return null;
            for (FieldDeclaration fd : decl.type.fields) {
                if (fd == null || fd.name == null) continue;
                if (!nom.equals(String.valueOf(fd.name))) continue;
                return nameRange(decl.content, fd.sourceStart, fd.sourceEnd, nom);
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
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
     *  le libellé picker du projet tiers de référence ({@code navLabel}). */
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

    // ════════════════════════════════════════════════════════════════
    // Hiérarchies type / appel (lot S2-b)
    // ════════════════════════════════════════════════════════════════

    /** Une déclaration localisée dans les sources — l'élément de
     *  hiérarchie (type ou méthode) : fichier, FQN, nom simple, plage du
     *  jeton NOM et plage de la DÉCLARATION COMPLÈTE. */
    public record CibleHierarchie(String chemin, String fqn, String nom,
                                  int nomDebut, int nomFin,
                                  int declDebut, int declFin) {}

    /** Une méthode appelante ou appelée : sa déclaration localisée + les
     *  plages des sites d'appel dans son corps (vides pour une préparation
     *  d'élément, [start, end) par site pour un appel entrant/sortant). */
    public record CibleAppel(String chemin, String nomMethode,
                             String classeProprietaire,
                             int nomDebut, int nomFin,
                             int declDebut, int declFin,
                             List<int[]> sites) {}

    /** Cap des appels entrants collectés (éviter un résultat géant). */
    static final int MAX_APPELS = 256;

    /**
     * L'ÉLÉMENT de hiérarchie de TYPES au caret : le type référencé ou
     * déclaré sous l'offset, localisé dans les sources du projet.
     *
     * @return l'élément, ou null (pas de type au caret, type binaire du
     *         classpath — D3 : jamais d'URI factice, l'appelant rend null)
     */
    public CibleHierarchie typeHierarchieAu(String filePath, String source,
                                            int offset, Map<String, String> overlay) {
        if (EcjCompilerEngine.DISABLED) return null;
        try {
            Context ctx = resolveContext(filePath, source, offset);
            TypeBinding t = ctx.contextType();
            if (!(t instanceof ReferenceBinding rb)) return null;
            String fqn = String.valueOf(rb.readableName());
            SourceDecl decl = locateTypeDeclaration(fqn,
                    overlay == null ? Map.of() : overlay);
            if (decl == null) return null; // binaire — jamais d'URI factice
            return cibleType(decl, fqn);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Les supertypes DIRECTS du type au caret (superclasse + interfaces),
     * localisés dans les sources — les supertypes binaires sont écartés
     * (même discipline D3 que {@code findSuper}).
     */
    public List<CibleHierarchie> supertypesHierarchie(String filePath, String source,
                                                      int offset, Map<String, String> overlay) {
        List<CibleHierarchie> out = new ArrayList<>();
        if (EcjCompilerEngine.DISABLED) return out;
        try {
            Context ctx = resolveContext(filePath, source, offset);
            TypeBinding t = ctx.contextType();
            if (!(t instanceof ReferenceBinding rb)) return out;
            Map<String, String> ov = overlay == null ? Map.of() : overlay;
            for (ReferenceBinding sup : directSupertypes(rb)) {
                String fqn = String.valueOf(sup.readableName());
                SourceDecl decl = locateTypeDeclaration(fqn, ov);
                if (decl == null) continue;
                CibleHierarchie c = cibleType(decl, fqn);
                if (c != null) out.add(c);
            }
        } catch (Throwable ignored) {
            // Hiérarchie non résolue — liste partielle.
        }
        return out;
    }

    /**
     * Les héritiers DIRECTS du type au caret (même sémantique que
     * {@code findImplementations} : superclass ou interface DIRECTE
     * résolvant vers la cible), localisés dans les sources.
     */
    public List<CibleHierarchie> sousTypesHierarchie(String filePath, String source,
                                                     int offset, Map<String, String> overlay) {
        List<CibleHierarchie> out = new ArrayList<>();
        if (EcjCompilerEngine.DISABLED) return out;
        try {
            Context ctx = resolveContext(filePath, source, offset);
            TypeBinding target = ctx.contextType();
            if (!(target instanceof ReferenceBinding rb)) return out;
            String targetFqn = String.valueOf(rb.readableName());
            if (targetFqn.isEmpty() || targetFqn.contains("<")) return out;

            Map<String, String> ov = overlay == null ? Map.of() : overlay;
            Set<String> seen = new HashSet<>();
            long deadline = System.currentTimeMillis() + WALK_BUDGET_MS;
            int[] resolved = {0};
            for (Path file : candidateFiles(filePath)) {
                if (resolved[0] >= MAX_WALK_FILES) break;
                if (System.currentTimeMillis() > deadline) break;
                String abs = file.toAbsolutePath().toString();
                String content = contentOf(abs, filePath, source, ov);
                if (content == null) continue;
                resolved[0]++;
                collecteSousTypesCible(abs, content, rb, targetFqn, out, seen);
            }
        } catch (Throwable ignored) {
            // Balayage interrompu — liste partielle.
        }
        return out;
    }

    /** Scan d'UNE unité pour {@code sousTypesHierarchie} (miroir
     *  {@code collectDirectSubtypes} — la version API CibleHierarchie). */
    private void collecteSousTypesCible(String abs, String content,
                                        ReferenceBinding target, String targetFqn,
                                        List<CibleHierarchie> out, Set<String> seen) {
        try {
            CompilationUnitDeclaration cud = engine.resolveUnit(abs, content, false);
            if (cud == null) return;
            List<TypeDeclaration> types = new ArrayList<>();
            EcjAst.traverseResilient(cud, typeCollector(types));
            for (TypeDeclaration td : types) {
                if (td == null || td.name == null || td.name.length == 0) continue;
                if (td.binding == null) continue;
                if (!extendsOrImplements(td.binding, target, targetFqn)) continue;
                CibleHierarchie c = cibleType(
                        new SourceDecl(abs, content, td),
                        String.valueOf(td.binding.readableName()));
                if (c == null) continue;
                if (seen.add(abs + ":" + c.nomDebut())) out.add(c);
            }
        } catch (Throwable ignored) {
            // Fichier pathologique — ignoré.
        }
    }

    /** La CibleHierarchie d'un TypeDeclaration déjà résolu (nom exact). */
    private static CibleHierarchie cibleType(SourceDecl decl, String fqn) {
        try {
            TypeDeclaration td = decl.type;
            if (td.name == null || td.name.length == 0) return null;
            int[] name = nameRange(decl.content, td.sourceStart, td.sourceEnd,
                    String.valueOf(td.name));
            if (name == null) return null;
            int declDebut = td.declarationSourceStart != 0
                    ? td.declarationSourceStart : td.sourceStart;
            int declFin = td.declarationSourceEnd != 0
                    ? td.declarationSourceEnd + 1 : td.sourceEnd + 1;
            return new CibleHierarchie(decl.path, fqn, String.valueOf(td.name),
                    name[0], name[1], declDebut, declFin);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * L'ÉLÉMENT de hiérarchie d'APPELS au caret : la méthode référencée ou
     * déclarée sous l'offset, localisée dans les sources du projet.
     *
     * @return l'élément, ou null (pas de méthode au caret, déclaration
     *         binaire — l'appelant rend null, jamais d'URI factice)
     */
    public CibleHierarchie methodeHierarchieAu(String filePath, String source,
                                               int offset, Map<String, String> overlay) {
        if (EcjCompilerEngine.DISABLED) return null;
        try {
            Context ctx = resolveContext(filePath, source, offset);
            if (!(ctx.binding instanceof MethodBinding m)) return null;
            return localiseMethode(m, overlay);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** La localisation SOURCE d'un MethodBinding (fichier déclarant, plage
     *  du nom, plage de la déclaration) — null si binaire ou introuvable. */
    private CibleHierarchie localiseMethode(MethodBinding m, Map<String, String> overlay) {
        try {
            if (m == null || m.selector == null || m.declaringClass == null) return null;
            String classeFqn = String.valueOf(m.declaringClass.readableName());
            SourceDecl decl = locateTypeDeclaration(classeFqn,
                    overlay == null ? Map.of() : overlay);
            if (decl == null) return null;
            String selector = String.valueOf(m.selector);
            int[] name = methodNameRange(decl, selector,
                    m.parameters == null ? 0 : m.parameters.length);
            if (name == null) return null;
            // La déclaration complète : retrouver le AbstractMethodDeclaration
            // correspondant dans le type déclarant (même arité d'abord).
            AbstractMethodDeclaration elue = null;
            if (decl.type.methods != null) {
                for (AbstractMethodDeclaration md : decl.type.methods) {
                    if (md == null || md.selector == null) continue;
                    if (!selector.equals(String.valueOf(md.selector))) continue;
                    int arite = md.arguments == null ? 0 : md.arguments.length;
                    if (arite == (m.parameters == null ? 0 : m.parameters.length)) {
                        elue = md;
                        break;
                    }
                    if (elue == null) elue = md;
                }
            }
            if (elue == null) return null;
            int declDebut = elue.declarationSourceStart != 0
                    ? elue.declarationSourceStart : elue.sourceStart;
            int declFin = elue.declarationSourceEnd != 0
                    ? elue.declarationSourceEnd + 1 : elue.sourceEnd + 1;
            return new CibleHierarchie(decl.path, classeFqn + "." + selector,
                    selector, name[0], name[1], declDebut, declFin);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Les appels ENTRANTS de la méthode au caret : chaque site MessageSend
     * des sources du projet dont le binding résout EXACTEMENT vers la même
     * déclaration (même classe déclarante, même sélecteur, mêmes
     * paramètres — les appels transitant par un supertype ne sont PAS
     * attribués : conservateur, jamais de faux positif). Groupés par
     * méthode appelante (la plus intérieure contenant le site).
     */
    public List<CibleAppel> appelsEntrants(String filePath, String source,
                                           int offset, Map<String, String> overlay) {
        List<CibleAppel> out = new ArrayList<>();
        if (EcjCompilerEngine.DISABLED) return out;
        try {
            Context ctx = resolveContext(filePath, source, offset);
            if (!(ctx.binding instanceof MethodBinding m)) return out;
            String cle = cleMethode(m);
            if (cle == null) return out;

            Map<String, String> ov = overlay == null ? Map.of() : overlay;
            // Dédup des appelants : chemin + nomDebut → sites accumulés.
            java.util.Map<String, CibleAppel> parAppelant = new java.util.LinkedHashMap<>();
            long deadline = System.currentTimeMillis() + WALK_BUDGET_MS;
            int[] resolved = {0};
            for (Path file : candidateFiles(filePath)) {
                if (resolved[0] >= MAX_WALK_FILES
                        || parAppelant.size() >= MAX_APPELS) break;
                if (System.currentTimeMillis() > deadline) break;
                String abs = file.toAbsolutePath().toString();
                String content = contentOf(abs, filePath, source, ov);
                if (content == null) continue;
                resolved[0]++;
                collecteAppels(abs, content, cle, parAppelant);
            }
            out.addAll(parAppelant.values());
        } catch (Throwable ignored) {
            // Balayage interrompu — liste partielle.
        }
        return out;
    }

    /**
     * Les appels SORTANTS de la méthode au caret : chaque MessageSend de
     * son corps résolu vers une déclaration SOURCE du projet, groupé par
     * méthode cible avec les plages des sites d'appel.
     */
    public List<CibleAppel> appelsSortants(String filePath, String source,
                                           int offset, Map<String, String> overlay) {
        List<CibleAppel> out = new ArrayList<>();
        if (EcjCompilerEngine.DISABLED) return out;
        try {
            CompilationUnitDeclaration cud = engine.resolveUnit(filePath, source, false);
            if (cud == null) return out;
            Map<String, String> ov = overlay == null ? Map.of() : overlay;

            // La méthode englobante au caret (la plus intérieure).
            List<AbstractMethodDeclaration> methodes = new ArrayList<>();
            EcjAst.traverseResilient(cud, methodCollector(methodes));
            AbstractMethodDeclaration englobante = plusInterieure(methodes, offset);
            if (englobante == null) return out;
            int de = englobante.declarationSourceStart != 0
                    ? englobante.declarationSourceStart : englobante.sourceStart;
            int a = englobante.declarationSourceEnd != 0
                    ? englobante.declarationSourceEnd : englobante.sourceEnd;

            // Les sites d'appel DANS son corps, dédupliqués par cible.
            List<MessageSend> envois = new ArrayList<>();
            EcjAst.traverseResilient(cud, messageSendCollector(envois, de, a));
            java.util.Map<String, List<int[]>> sitesParCle = new java.util.LinkedHashMap<>();
            java.util.Map<String, MethodBinding> cibleParCle = new java.util.LinkedHashMap<>();
            for (MessageSend envoi : envois) {
                // MessageSend.binding est déjà typé MethodBinding (null si
                // non résolu) — un pattern instanceof serait « inconditionnel »
                // (Java 21, interdit en -source 17).
                MethodBinding mb = envoi.binding;
                if (mb == null || mb.declaringClass == null) continue;
                String cle = cleMethode(mb);
                if (cle == null) continue;
                sitesParCle.computeIfAbsent(cle, k -> new ArrayList<>())
                        .add(new int[]{envoi.sourceStart, envoi.sourceEnd + 1});
                cibleParCle.putIfAbsent(cle, mb);
            }
            for (var entry : sitesParCle.entrySet()) {
                CibleHierarchie cible = localiseMethode(cibleParCle.get(entry.getKey()), ov);
                if (cible == null) continue; // binaire — jamais d'URI factice
                // Le fqn d'une cible MÉTHODE est « classe.sélecteur » — le
                // propriétaire = tout sauf le dernier segment (les classes
                // imbriquées restent complètes : demo.Outer.Inner.m →
                // demo.Outer.Inner).
                String fqnComplet = cible.fqn();
                String proprietaire = fqnComplet.contains(".")
                        ? fqnComplet.substring(0, fqnComplet.lastIndexOf('.'))
                        : fqnComplet;
                out.add(new CibleAppel(cible.chemin(), cible.nom(), proprietaire,
                        cible.nomDebut(), cible.nomFin(),
                        cible.declDebut(), cible.declFin(),
                        entry.getValue()));
            }
        } catch (Throwable ignored) {
            // Balayage interrompu — liste partielle.
        }
        return out;
    }

    /** Scan d'UNE unité pour {@code appelsEntrants} : les sites MessageSend
     *  résolvant vers {@code cle}, groupés par méthode appelante. */
    private void collecteAppels(String abs, String content, String cle,
                                java.util.Map<String, CibleAppel> parAppelant) {
        try {
            CompilationUnitDeclaration cud = engine.resolveUnit(abs, content, false);
            if (cud == null) return;
            List<AbstractMethodDeclaration> methodes = new ArrayList<>();
            List<MessageSend> envois = new ArrayList<>();
            EcjAst.traverseResilient(cud, methodCollector(methodes));
            EcjAst.traverseResilient(cud, messageSendCollector(envois, -1, -1));
            if (envois.isEmpty() || methodes.isEmpty()) return;
            for (MessageSend envoi : envois) {
                // MessageSend.binding est déjà typé MethodBinding (null si
                // non résolu) — un pattern instanceof serait « inconditionnel »
                // (Java 21, interdit en -source 17).
                MethodBinding mb = envoi.binding;
                if (mb == null) continue;
                if (!cle.equals(cleMethode(mb))) continue;
                AbstractMethodDeclaration appelante =
                        plusInterieure(methodes, envoi.sourceStart);
                if (appelante == null || appelante.selector == null) continue;
                String selector = String.valueOf(appelante.selector);
                int[] name = nameRange(content, appelante.sourceStart,
                        appelante.bodyStart > appelante.sourceStart
                                ? appelante.bodyStart : appelante.sourceEnd,
                        selector);
                if (name == null) continue;
                String classeFqn = appelante instanceof MethodDeclaration md
                        && md.binding != null && md.binding.declaringClass != null
                        ? String.valueOf(md.binding.declaringClass.readableName())
                        : "";
                int declDebut = appelante.declarationSourceStart != 0
                        ? appelante.declarationSourceStart : appelante.sourceStart;
                int declFin = appelante.declarationSourceEnd != 0
                        ? appelante.declarationSourceEnd + 1 : appelante.sourceEnd + 1;
                String k = abs + ":" + name[0];
                CibleAppel existante = parAppelant.get(k);
                int[] site = {envoi.sourceStart, envoi.sourceEnd + 1};
                if (existante == null) {
                    parAppelant.put(k, new CibleAppel(abs, selector, classeFqn,
                            name[0], name[1], declDebut, declFin,
                            new ArrayList<>(List.of(site))));
                } else if (existante.sites().size() < MAX_APPELS) {
                    existante.sites().add(site);
                }
            }
        } catch (Throwable ignored) {
            // Fichier pathologique — ignoré.
        }
    }

    /** La clé structurelle d'une méthode (classe déclarante, sélecteur,
     *  paramètres érasés) — stable entre unités, null si indéterminable. */
    private static String cleMethode(MethodBinding m) {
        try {
            if (m == null || m.selector == null || m.declaringClass == null) return null;
            MethodBinding orig = m.original() != null ? m.original() : m;
            StringBuilder sb = new StringBuilder(
                    String.valueOf(orig.declaringClass.readableName()))
                    .append('.').append(String.valueOf(orig.selector)).append('(');
            if (orig.parameters != null) {
                for (TypeBinding p : orig.parameters) {
                    if (p == null) continue;
                    sb.append(String.valueOf(p.erasure().readableName())).append(',');
                }
            }
            return sb.append(')').toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** La méthode la plus intérieure CONTENANT l'offset (plage de
     *  déclaration complète), ou null. */
    private static AbstractMethodDeclaration plusInterieure(
            List<AbstractMethodDeclaration> methodes, int offset) {
        AbstractMethodDeclaration elue = null;
        int longueur = Integer.MAX_VALUE;
        for (AbstractMethodDeclaration md : methodes) {
            if (md == null) continue;
            int de = md.declarationSourceStart != 0
                    ? md.declarationSourceStart : md.sourceStart;
            int a = md.declarationSourceEnd != 0
                    ? md.declarationSourceEnd : md.sourceEnd;
            if (de > offset || a < offset) continue;
            if (a - de < longueur) {
                longueur = a - de;
                elue = md;
            }
        }
        return elue;
    }

    /** Visiteur collectant les déclarations de méthodes/constructeurs. */
    private static ASTVisitor methodCollector(List<AbstractMethodDeclaration> out) {
        return new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration md, ClassScope s) {
                out.add(md);
                return true;
            }

            @Override
            public boolean visit(ConstructorDeclaration cd, ClassScope s) {
                out.add(cd);
                return true;
            }
        };
    }

    /** Visiteur collectant les MessageSend DANS [de, a] (bornes -1/-1 =
     *  tout le fichier), bindings résolus uniquement. */
    private static ASTVisitor messageSendCollector(List<MessageSend> out,
                                                   int de, int a) {
        return new ASTVisitor() {
            @Override
            public boolean visit(MessageSend ms, BlockScope s) {
                if (ms.binding == null) return true;
                if (de >= 0 && (ms.sourceStart < de || ms.sourceStart > a)) {
                    return true;
                }
                out.add(ms);
                return true;
            }
        };
    }

    // ════════════════════════════════════════════════════════════════
    // CodeLens (lot S2-b)
    // ════════════════════════════════════════════════════════════════

    /** Une déclaration porteuse de lens : type, méthode, constructeur ou
     *  champ — la plage du jeton nom sert d'ancre à la résolution. */
    public record LenseCode(String nom, String genre,
                            int nomDebut, int nomFin) {}

    /** Genres de lens. */
    public static final String LENSE_TYPE = "type";
    public static final String LENSE_METHODE = "methode";
    public static final String LENSE_CONSTRUCTEUR = "constructeur";
    public static final String LENSE_CHAMP = "champ";

    /**
     * Les déclarations du fichier porteuses d'une lens « références » :
     * les types (tous niveaux d'imbrication), leurs méthodes et
     * constructeurs, leurs champs. Les locales/paramètres sont écartés
     * (pas de lens sur une variable locale).
     */
    public List<LenseCode> declarationsPourLenses(String filePath, String source) {
        List<LenseCode> out = new ArrayList<>();
        if (EcjCompilerEngine.DISABLED || source == null || source.isEmpty()) {
            return out;
        }
        try {
            CompilationUnitDeclaration cud = engine.resolveUnit(filePath, source, false);
            if (cud == null) return out;
            EcjAst.traverseResilient(cud, new ASTVisitor() {
                private void nom(int from, int to, char[] name, String genre) {
                    if (name == null || name.length == 0) return;
                    int[] plage = nameRange(source, from, to, String.valueOf(name));
                    if (plage != null) {
                        out.add(new LenseCode(String.valueOf(name), genre,
                                plage[0], plage[1]));
                    }
                }

                @Override
                public boolean visit(TypeDeclaration td, CompilationUnitScope s) {
                    type(td);
                    return true;
                }

                @Override
                public boolean visit(TypeDeclaration td, ClassScope s) {
                    type(td);
                    return true;
                }

                @Override
                public boolean visit(TypeDeclaration td, BlockScope s) {
                    type(td);
                    return true;
                }

                private void type(TypeDeclaration td) {
                    nom(td.sourceStart, td.sourceEnd, td.name, LENSE_TYPE);
                }

                @Override
                public boolean visit(MethodDeclaration md, ClassScope s) {
                    if (md.bodyStart > md.sourceStart) {
                        nom(md.sourceStart, md.bodyStart, md.selector, LENSE_METHODE);
                    }
                    return true;
                }

                @Override
                public boolean visit(ConstructorDeclaration cd, ClassScope s) {
                    if (cd.bodyStart > cd.sourceStart) {
                        nom(cd.sourceStart, cd.bodyStart, cd.selector,
                                LENSE_CONSTRUCTEUR);
                    }
                    return true;
                }

                @Override
                public boolean visit(FieldDeclaration fd, MethodScope s) {
                    nom(fd.sourceStart, fd.sourceEnd, fd.name, LENSE_CHAMP);
                    return true;
                }
            });
        } catch (Throwable ignored) {
            // Unité pathologique — liste partielle (peut être vide).
        }
        return out;
    }
}
