package jo.lspjava.ecj;

import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.Argument;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.ConstructorDeclaration;
import org.eclipse.jdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.jdt.internal.compiler.ast.FieldReference;
import org.eclipse.jdt.internal.compiler.ast.ImportReference;
import org.eclipse.jdt.internal.compiler.ast.LocalDeclaration;
import org.eclipse.jdt.internal.compiler.ast.MessageSend;
import org.eclipse.jdt.internal.compiler.ast.MethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.QualifiedNameReference;
import org.eclipse.jdt.internal.compiler.ast.QualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.SingleTypeReference;
import org.eclipse.jdt.internal.compiler.ast.SingleNameReference;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.lookup.Binding;
import org.eclipse.jdt.internal.compiler.lookup.FieldBinding;
import org.eclipse.jdt.internal.compiler.lookup.LocalVariableBinding;
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.VariableBinding;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Moteur de renommage sûr basé sur les <strong>bindings ecj</strong> — le
 * portage Java du {@code JdtRename} du projet tiers de référence, adapté au compilateur
 * interne (l'API DOM n'est pas utilisée ici).
 *
 * <p>Le renommage est <em>structurellement correct</em> : les références
 * sont identifiées par <strong>clé de binding</strong> (une identité
 * stable à travers les unités compilées séparément — classe déclarante +
 * nom + signature), pas par texte. Les homonymes (deux méthodes
 * {@code get} sur des classes différentes, une locale {@code x} dans deux
 * méthodes) ne sont PAS retouchés ; les usages qualifiés
 * ({@code list.add}), les imports et la déclaration sont attrapés.</p>
 *
 * <h2>Cibles</h2>
 * <ul>
 *   <li><b>Type</b> (classe/interface/enum/record/annotation) — le renommage
 *       d'une classe retouche AUSSI les noms des déclarations de
 *       constructeurs (leur binding est le constructeur, dont la classe
 *       déclarante est le type renommé) et les imports.</li>
 *   <li><b>Méthode</b> — les redéfinissions ne sont PAS poursuivies
 *       (comportement projet tiers de référence) : renommer une méthode ne renomme pas
 *       ses overrides.</li>
 *   <li><b>Champ</b>, <b>locale</b>, <b>paramètre</b> — les locales et
 *       paramètres sont <em>file-local</em> : le balayage projet est
 *       esquivé, seule la déclaration + le fichier courant sont retouchés.</li>
 * </ul>
 *
 * <h2>Orchestration multi-fichiers</h2>
 * <ol>
 *   <li>Le tampon courant (contenu ouvert, non sauvegardé) est résolu
 *       en premier — c'est lui qui définit la cible.</li>
 *   <li>Pour une cible non file-local, chaque {@code .java} des racines
 *       sources contenant le nom (pré-filtre textuel rapide) est résolu et
 *       scanné ; les occurrences sont collectées par fichier.</li>
 * </ol>
 *
 * @author jo@Dev
 */
public final class EcjRenameEngine {

    /** Symbole renommable. */
    public static final class RenameTarget {
        public final String oldName;
        public final String kind;
        /** Clé structurelle stable entre unités (méthode/champ/type). */
        public final String bindingKey;
        /** Locale/paramètre — références confinées au fichier déclarant. */
        public final boolean fileLocal;
        /** Type — les noms de constructeurs comptent aussi. */
        public final boolean isType;
        /** Clé de la méthode déclarante (locales/paramètres seulement). */
        public final String enclosingMethodKey;
        /** Plage [start, end) du jeton nom visé (la déclaration quand le
         *  caret y est) — pour exclure la déclaration des références. */
        public final int declStart;
        public final int declEnd;

        RenameTarget(String oldName, String kind, String bindingKey,
                     boolean fileLocal, boolean isType, String enclosingMethodKey) {
            this(oldName, kind, bindingKey, fileLocal, isType, enclosingMethodKey, -1, -1);
        }

        RenameTarget(String oldName, String kind, String bindingKey,
                     boolean fileLocal, boolean isType, String enclosingMethodKey,
                     int declStart, int declEnd) {
            this.oldName = oldName;
            this.kind = kind;
            this.bindingKey = bindingKey;
            this.fileLocal = fileLocal;
            this.isType = isType;
            this.enclosingMethodKey = enclosingMethodKey;
            this.declStart = declStart;
            this.declEnd = declEnd;
        }
    }

    /** Une occurrence : plage [start, end) dans un fichier. */
    public static final class Occurrence {
        public final int start;
        public final int end;

        public Occurrence(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    private final EcjCompilerEngine engine;

    public EcjRenameEngine(EcjCompilerEngine engine) {
        this.engine = engine;
    }

    /**
     * @return le symbole renommable dont le jeton nom contient l'offset,
     *         ou null (pas sur un nom / binding non résolu / non renommable).
     */
    public RenameTarget targetAt(String filePath, String source, int offset) {
        if (EcjCompilerEngine.DISABLED) return null;
        try {
            CompilationUnitDeclaration cud = engine.resolveUnit(filePath, source, false);
            if (cud == null || cud.scope == null) return null;
            TargetScan scan = new TargetScan(source, offset);
            EcjAst.traverseResilient(cud, scan);
            return scan.result;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Toutes les occurrences de {@code target} dans UNE source.
     *
     * @param source le texte de l'unité
     * @param filePath son chemin (pour les locales : comparer la méthode
     *                 déclarante en plus du nom)
     */
    public List<Occurrence> referencesIn(String filePath, String source, RenameTarget target) {
        List<Occurrence> out = new ArrayList<>();
        try {
            CompilationUnitDeclaration cud = engine.resolveUnit(filePath, source, false);
            if (cud == null || cud.scope == null) return out;
            ReferenceScan scan = new ReferenceScan(source, target, out);
            EcjAst.traverseResilient(cud, scan);
            // Imports du type renommé (FQN exact).
            if (target.isType) {
                collectImportReferences(cud, source, target, out);
            }
        } catch (Throwable ignored) {
            // Partial — on garde ce qui est collecté.
        }
        out.sort(Comparator.comparingInt(o -> o.start));
        // Déduplication (un même jeton peut être visité par deux formes).
        List<Occurrence> dedup = new ArrayList<>();
        int lastStart = -1;
        for (Occurrence o : out) {
            if (o.start != lastStart) {
                dedup.add(o);
                lastStart = o.start;
            }
        }
        return dedup;
    }

    /**
     * Renommage multi-fichiers : occurrences de {@code target} dans le
     * tampon courant + toutes les sources des racines du projet.
     *
     * @return la carte chemin absolu → occurrences (le texte reste à
     *         remplacer par l'appelant).
     */
    public Map<String, List<Occurrence>> renameProject(String filePath, String source,
                                                       RenameTarget target) {
        return renameProject(filePath, source, target, java.util.Map.of());
    }

    /**
     * Renommage multi-fichiers avec OVERLAY des tampons ouverts.
     *
     * <p>Le serveur LSP voit le tampon ouvert (via {@code didOpen} /
     * {@code didChange}) mais le renommage multi-fichiers lit les AUTRES
     * fichiers sur disque — sans overlay, un fichier frère ouvert non
     * sauvegardé serait renommé sur une version PÉRIMÉE. On accepte donc un
     * overlay {@code path → source} qui est consulté AVANT le disque : si un
     * frère est ouvert, on prend sa version vivante (modifications non
     * sauvegardées incluses).</p>
     *
     * @param overlay chemin absolu → source en mémoire (tampons vivants).
     *                Peut être vide ; ne doit PAS contenir {@code filePath}
     *                (passé séparément via {@code source}).
     */
    public Map<String, List<Occurrence>> renameProject(String filePath, String source,
                                                       RenameTarget target,
                                                       java.util.Map<String, String> overlay) {
        return renameProject(filePath, source, target, overlay,
                jo.lspjava.engine.CancelToken.NEVER);
    }

    /**
     * Renommage multi-fichiers avec overlay + annulation coopérative.
     *
     * <p>Le balayage projet peut
     * être long (des secondes sur un gros projet Android). Le token permet
     * à l'appelant interactif (completion, hover) d'interrompre le balayage
     * projet en arrière-plan — le moteur rend une PARTIE des occurrences
     * plutôt que de bloquer la frappe. Pattern {@code CancelToken} de
     * projet tiers de référence.</p>
     */
    public Map<String, List<Occurrence>> renameProject(String filePath, String source,
                                                       RenameTarget target,
                                                       java.util.Map<String, String> overlay,
                                                       jo.lspjava.engine.CancelToken cancel) {
        Map<String, List<Occurrence>> out = new LinkedHashMap<>();
        out.put(filePath, referencesIn(filePath, source, target));

        if (!target.fileLocal && engineHasSourceRoots()) {
            for (Path root : sourceRootsOfEngine()) {
                // Check d'annulation entre les racines sources
                // — si l'utilisateur a tapé une nouvelle touche pendant le
                // balayage, on rend ce qu'on a déjà collecté (partial result).
                if (cancel != null && cancel.isCancelled()) {
                    return out;
                }
                try (Stream<Path> walk = Files.walk(root)) {
                    walk.filter(p -> p.toString().endsWith(".java")
                                    && Files.isRegularFile(p))
                        .forEach(p -> {
                            // Check d'annulation dans la boucle interne —
                            // ne bloque pas l'annulation entre fichiers.
                            if (cancel != null && cancel.isCancelled()) return;
                            String abs = p.toAbsolutePath().toString();
                            if (abs.equals(filePath)) return;
                            // Overlay d'abord — si le frère est
                            // ouvert dans l'éditeur (tampon vivant), on prend
                            // sa version en mémoire ; sinon on lit le disque.
                            String content;
                            if (overlay != null && overlay.containsKey(abs)) {
                                content = overlay.get(abs);
                            } else {
                                try {
                                    content = new String(
                                            Files.readAllBytes(p), StandardCharsets.UTF_8);
                                } catch (IOException | OutOfMemoryError ignored) {
                                    return; // Fichier illisible — ignoré.
                                }
                            }
                            // Pré-filtre textuel — ne résout pas un
                            // fichier qui ne contient même pas le nom.
                            if (content == null || !content.contains(target.oldName)) return;
                            List<Occurrence> occurrences =
                                    referencesIn(abs, content, target);
                            if (!occurrences.isEmpty()) {
                                out.put(abs, occurrences);
                            }
                        });
                } catch (IOException | OutOfMemoryError ignored) {
                    // Racine illisible — ignorée.
                }
            }
        }
        return out;
    }

    private boolean engineHasSourceRoots() {
        return !sourceRootsOfEngine().isEmpty();
    }

    private List<Path> sourceRootsOfEngine() {
        return engine.sourceRoots();
    }

    /**
     * Binding du DERNIER segment d'un {@link QualifiedNameReference} —
     * {@code ref.binding} porte le PREMIER segment (variable/type du
     * qualifier) ; les segments suivants vivent dans
     * {@code otherBindings} (vérifié empiriquement :
     * {@code config.timeout} → binding=LocalVariableBinding(config),
     * otherBindings[0]=FieldBinding(timeout)).
     */
    static Binding lastSegmentBinding(QualifiedNameReference ref) {
        if (ref.otherBindings != null && ref.otherBindings.length > 0) {
            return ref.otherBindings[ref.otherBindings.length - 1];
        }
        return ref.binding;
    }

    // ─── Clés structurelles (identité entre unités) ───────────────────

    /** Clé d'une méthode : classe déclarante + sélecteur + signature. */
    static String methodKey(MethodBinding m) {
        if (m == null) return null;
        try {
            MethodBinding original = m.original() != null ? m.original() : m;
            StringBuilder sb = new StringBuilder("method:");
            sb.append(typeKey(original.declaringClass)).append('.')
                    .append(String.valueOf(original.selector)).append('(');
            for (int i = 0; i < original.parameters.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(erasedName(original.parameters[i]));
            }
            sb.append(')');
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Clé d'un champ : classe déclarante + nom. */
    static String fieldKey(FieldBinding f) {
        if (f == null) return null;
        try {
            return "field:" + typeKey(f.declaringClass) + '.'
                    + String.valueOf(f.name);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Clé d'un type : nom qualifié effacé. */
    static String typeKey(TypeBinding t) {
        if (t == null) return "?";
        try {
            TypeBinding erasure = t.erasure() != null ? t.erasure() : t;
            return String.valueOf(erasure.readableName());
        } catch (Throwable e) {
            return String.valueOf(t.readableName());
        }
    }

    /** Nom effacé d'un type de paramètre (sans arguments génériques). */
    private static String erasedName(TypeBinding t) {
        if (t == null) return "?";
        try {
            TypeBinding erasure = t.erasure() != null ? t.erasure() : t;
            return String.valueOf(erasure.readableName());
        } catch (Throwable e) {
            return "?";
        }
    }

    /** Clé du binding selon sa nature — l'identité du renommage. */
    static String bindingKey(Binding b) {
        if (b instanceof MethodBinding m) return methodKey(m);
        if (b instanceof FieldBinding f) return fieldKey(f);
        if (b instanceof ReferenceBinding t) return "type:" + typeKey(t);
        return null;
    }

    // ─── Scan de cible (nœud nom à l'offset) ──────────────────────────

    /** Trouve le plus petit jeton nom couvrant l'offset et le décrit. */
    private final class TargetScan extends org.eclipse.jdt.internal.compiler.ASTVisitor {
        private final String source;
        private final int offset;
        private final int[] best = {Integer.MAX_VALUE};
        RenameTarget result;
        /** Méthode déclarante courante (pour les locales). */
        private String currentMethodKey;

        TargetScan(String source, int offset) {
            this.source = source;
            this.offset = offset;
        }

        void consider(ASTNode node, char[] token, Binding binding) {
            if (result != null || token == null || token.length == 0) return;
            int start = nameStart(node, token, source);
            int end = start + token.length;
            if (start < 0 || offset < start || offset > end) return;
            int len = node.sourceEnd - node.sourceStart;
            if (len >= best[0]) return;
            RenameTarget t = describe(token, binding, currentMethodKey);
            if (t != null) {
                best[0] = len;
                result = new RenameTarget(t.oldName, t.kind, t.bindingKey,
                        t.fileLocal, t.isType, t.enclosingMethodKey, start, end);
            }
        }

        @Override
        public boolean visit(MethodDeclaration md,
                org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
            currentMethodKey = methodKey(md.binding);
            consider(md, md.selector, md.binding);
            return true;
        }

        @Override
        public boolean visit(ConstructorDeclaration cd,
                org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
            currentMethodKey = methodKey(cd.binding);
            // Le constructeur renomme la CLASSE (cas spécial type).
            considerConstructor(cd);
            return true;
        }

        private void considerConstructor(ConstructorDeclaration cd) {
            if (result != null) return;
            int start = nameStart(cd, cd.selector, source);
            int end = start + cd.selector.length;
            if (start < 0 || offset < start || offset > end) return;
            // Type = classe déclarante du constructeur.
            if (cd.binding != null && cd.binding.declaringClass != null) {
                String key = "type:" + typeKey(cd.binding.declaringClass);
                result = new RenameTarget(String.valueOf(cd.selector), "class",
                        key, false, true, null, start, end);
            }
        }

        @Override
        public void endVisit(MethodDeclaration md,
                org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
            currentMethodKey = null;
        }

        @Override
        public void endVisit(ConstructorDeclaration cd,
                org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
            currentMethodKey = null;
        }

        @Override
        public boolean visit(TypeDeclaration td,
                org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
            consider(td, td.name, td.binding);
            return true;
        }

        @Override
        public boolean visit(TypeDeclaration td,
                org.eclipse.jdt.internal.compiler.lookup.CompilationUnitScope s) {
            consider(td, td.name, td.binding);
            return true;
        }

        @Override
        public boolean visit(FieldDeclaration fd,
                org.eclipse.jdt.internal.compiler.lookup.MethodScope s) {
            consider(fd, fd.name, fd.binding);
            return true;
        }

        @Override
        public boolean visit(LocalDeclaration ld,
                org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
            consider(ld, ld.name, ld.binding);
            return true;
        }

        @Override
        public boolean visit(Argument a,
                org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
            consider(a, a.name, a.binding);
            return true;
        }

        @Override
        public boolean visit(SingleNameReference ref,
                org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
            consider(ref, ref.token, ref.binding);
            return true;
        }

        @Override
        public boolean visit(SingleNameReference ref,
                org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
            consider(ref, ref.token, ref.binding);
            return true;
        }

        @Override
        public boolean visit(FieldReference ref,
                org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
            consider(ref, ref.token, ref.binding);
            return true;
        }

        @Override
        public boolean visit(MessageSend ms,
                org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
            consider(ms, ms.selector, ms.binding);
            return true;
        }

        @Override
        public boolean visit(QualifiedNameReference ref,
                org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
            considerQualified(ref);
            return true;
        }

        @Override
        public boolean visit(QualifiedNameReference ref,
                org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
            considerQualified(ref);
            return true;
        }

        @Override
        public boolean visit(SingleTypeReference ref,
                org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
            considerTypeRef(ref, ref.token, ref.resolvedType);
            return true;
        }

        @Override
        public boolean visit(SingleTypeReference ref,
                org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
            considerTypeRef(ref, ref.token, ref.resolvedType);
            return true;
        }

        @Override
        public boolean visit(QualifiedTypeReference ref,
                org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
            considerQualifiedType(ref);
            return true;
        }

        @Override
        public boolean visit(QualifiedTypeReference ref,
                org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
            considerQualifiedType(ref);
            return true;
        }

        private void considerTypeRef(ASTNode node, char[] token, TypeBinding resolved) {
            if (result != null || token == null) return;
            int start = nameStart(node, token, source);
            int end = start + token.length;
            if (start < 0 || offset < start || offset > end) return;
            if (resolved instanceof ReferenceBinding rb && rb.isValidBinding()) {
                result = describe(token, rb, currentMethodKey);
            }
        }

        private void considerQualifiedType(QualifiedTypeReference ref) {
            if (result != null || ref.tokens == null || ref.tokens.length == 0) return;
            char[] last = ref.tokens[ref.tokens.length - 1];
            long pos = ref.sourcePositions[ref.sourcePositions.length - 1];
            int start = (int) (pos >>> 32);
            int end = (int) pos;
            if (offset < start || offset > end + 1) return;
            if (ref.resolvedType instanceof ReferenceBinding rb && rb.isValidBinding()) {
                result = describe(last, rb, currentMethodKey);
            }
        }

        private void considerQualified(QualifiedNameReference ref) {
            if (result != null || ref.tokens.length == 0) return;
            char[] last = ref.tokens[ref.tokens.length - 1];
            long pos = ref.sourcePositions[ref.sourcePositions.length - 1];
            int start = (int) (pos >>> 32);
            int end = (int) pos;
            if (offset < start || offset > end + 1) return;
            RenameTarget t = describe(last, lastSegmentBinding(ref), currentMethodKey);
            if (t != null) {
                result = t;
            }
        }

        @Override
        public boolean visit(ImportReference imp,
                org.eclipse.jdt.internal.compiler.lookup.CompilationUnitScope s) {
            // Import simple (import demo.Helper;) — le dernier segment est
            // une cible TYPE. Résout via le scope pour vérifier que c'est
            // bien un type renommable.
            if (result != null || imp.tokens == null || imp.tokens.length == 0) {
                return true;
            }
            if (((imp.bits & org.eclipse.jdt.internal.compiler.ast.ASTNode.OnDemand) != 0)) {
                return true; // import ...*; — pas de segment type ciblable
            }
            long pos = imp.sourcePositions[imp.sourcePositions.length - 1];
            int start = (int) (pos >>> 32);
            int end = (int) pos;
            if (offset < start || offset > end + 1) return true;
            try {
                TypeBinding type = s.getType(imp.tokens, imp.tokens.length);
                if (type != null && type.isValidBinding()
                        && type instanceof ReferenceBinding rb) {
                    result = describe(imp.tokens[imp.tokens.length - 1], rb, null);
                }
            } catch (Throwable ignored) {
                // Import non résolvable — pas une cible.
            }
            return true;
        }
    }

    /** Décrit un binding en cible de renommage (null = non renommable). */
    private static RenameTarget describe(char[] token, Binding binding,
                                         String enclosingMethodKey) {
        if (binding == null || !binding.isValidBinding()) return null;
        String name = String.valueOf(token);
        if (binding instanceof MethodBinding m) {
            if (m.isConstructor()) {
                if (m.declaringClass == null) return null;
                return new RenameTarget(name, "class",
                        "type:" + typeKey(m.declaringClass), false, true, null);
            }
            String key = methodKey(m);
            return key == null ? null
                    : new RenameTarget(name, "method", key, false, false, null);
        }
        if (binding instanceof FieldBinding f) {
            String key = fieldKey(f);
            return key == null ? null
                    : new RenameTarget(name, "field", key, false, false, null);
        }
        if (binding instanceof LocalVariableBinding l) {
            // Locale/paramètre — file-local, match nom + méthode déclarante.
            boolean param = l.isParameter();
            // Inclure l'offset de déclaration dans la clé pour distinguer
            // deux lambdas de la même méthode qui utilisent le même nom de
            // paramètre : deux lambdas `(x -> x+1, x -> x*2)` dans la même
            // méthode partagent enclosingMethodKey — sans declStart, le
            // rename de l'une renommerait l'autre.
            int declStart = l.declaration != null ? l.declaration.sourceStart : -1;
            String enhancedKey = enclosingMethodKey + "@decl" + declStart;
            return new RenameTarget(name, param ? "parameter" : "local variable",
                    "local:" + name, true, false, enhancedKey);
        }
        if (binding instanceof ReferenceBinding t) {
            if (t.isAnnotationType()) {
                return new RenameTarget(name, "annotation",
                        "type:" + typeKey(t), false, true, null);
            }
            if (t.isEnum()) {
                return new RenameTarget(name, "enum",
                        "type:" + typeKey(t), false, true, null);
            }
            if (t.isInterface()) {
                return new RenameTarget(name, "interface",
                        "type:" + typeKey(t), false, true, null);
            }
            return new RenameTarget(name, "class",
                    "type:" + typeKey(t), false, true, null);
        }
        return null;
    }

    // ─── Scan de références (occurrences d'une cible) ─────────────────

    /** Collecte les jetons nom qui référencent la cible. */
    private final class ReferenceScan extends org.eclipse.jdt.internal.compiler.ASTVisitor {
        private final String source;
        private final RenameTarget target;
        private final List<Occurrence> out;
        private String currentMethodKey;

        ReferenceScan(String source, RenameTarget target, List<Occurrence> out) {
            this.source = source;
            this.target = target;
            this.out = out;
        }

        void add(int start, int end) {
            if (start >= 0 && end > start) out.add(new Occurrence(start, end));
        }

        void onName(ASTNode node, char[] token, Binding binding) {
            if (token == null || !String.valueOf(token).equals(target.oldName)) return;
            if (matchesTarget(binding)) {
                add(nameStart(node, token, source), nameStart(node, token, source)
                        + token.length);
            }
        }

        boolean matchesTarget(Binding binding) {
            if (binding == null || !binding.isValidBinding()) return false;
            if (target.fileLocal) {
                // Locale/paramètre : nom + méthode déclarante.
                if (!(binding instanceof LocalVariableBinding lvb)) return false;
                // Comparaison avec la clé enrichie (incluant declStart).
                int refDeclStart = lvb.declaration != null
                        ? lvb.declaration.sourceStart : -1;
                String refKey = currentMethodKey + "@decl" + refDeclStart;
                return currentMethodKey != null
                        && refKey.equals(target.enclosingMethodKey);
            }
            if (binding instanceof MethodBinding m) {
                if (m.isConstructor()) {
                    // Constructeur → compte pour le renommage du type.
                    return target.isType && m.declaringClass != null
                            && ("type:" + typeKey(m.declaringClass))
                                    .equals(target.bindingKey);
                }
                return methodKey(m) != null
                        && methodKey(m).equals(target.bindingKey);
            }
            if (binding instanceof FieldBinding f) {
                String key = fieldKey(f);
                return key != null && key.equals(target.bindingKey);
            }
            if (binding instanceof ReferenceBinding t) {
                return ("type:" + typeKey(t)).equals(target.bindingKey);
            }
            return false;
        }

        @Override
        public boolean visit(MethodDeclaration md,
                org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
            currentMethodKey = methodKey(md.binding);
            onName(md, md.selector, md.binding);
            return true;
        }

        @Override
        public boolean visit(ConstructorDeclaration cd,
                org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
            currentMethodKey = methodKey(cd.binding);
            onName(cd, cd.selector, cd.binding);
            return true;
        }

        @Override
        public void endVisit(MethodDeclaration md,
                org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
            currentMethodKey = null;
        }

        @Override
        public void endVisit(ConstructorDeclaration cd,
                org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
            currentMethodKey = null;
        }

        @Override
        public boolean visit(TypeDeclaration td,
                org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
            onName(td, td.name, td.binding);
            return true;
        }

        @Override
        public boolean visit(TypeDeclaration td,
                org.eclipse.jdt.internal.compiler.lookup.CompilationUnitScope s) {
            onName(td, td.name, td.binding);
            return true;
        }

        @Override
        public boolean visit(FieldDeclaration fd,
                org.eclipse.jdt.internal.compiler.lookup.MethodScope s) {
            onName(fd, fd.name, fd.binding);
            return true;
        }

        @Override
        public boolean visit(LocalDeclaration ld,
                org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
            onName(ld, ld.name, ld.binding);
            return true;
        }

        @Override
        public boolean visit(Argument a,
                org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
            onName(a, a.name, a.binding);
            return true;
        }

        @Override
        public boolean visit(SingleNameReference ref,
                org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
            onName(ref, ref.token, ref.binding);
            return true;
        }

        @Override
        public boolean visit(SingleNameReference ref,
                org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
            onName(ref, ref.token, ref.binding);
            return true;
        }

        @Override
        public boolean visit(FieldReference ref,
                org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
            onName(ref, ref.token, ref.binding);
            return true;
        }

        @Override
        public boolean visit(MessageSend ms,
                org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
            if (String.valueOf(ms.selector).equals(target.oldName)
                    && matchesTarget(ms.binding)) {
                int start = (int) (ms.nameSourcePosition >>> 32);
                int end = (int) ms.nameSourcePosition;
                if (start >= 0 && end > start) add(start, end + 1);
            }
            return true;
        }

        @Override
        public boolean visit(QualifiedNameReference ref,
                org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
            onQualified(ref);
            return true;
        }

        @Override
        public boolean visit(QualifiedNameReference ref,
                org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
            onQualified(ref);
            return true;
        }

        @Override
        public boolean visit(SingleTypeReference ref,
                org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
            onTypeRef(ref, ref.token, ref.resolvedType);
            return true;
        }

        @Override
        public boolean visit(SingleTypeReference ref,
                org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
            onTypeRef(ref, ref.token, ref.resolvedType);
            return true;
        }

        @Override
        public boolean visit(QualifiedTypeReference ref,
                org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
            onQualifiedType(ref);
            return true;
        }

        @Override
        public boolean visit(QualifiedTypeReference ref,
                org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
            onQualifiedType(ref);
            return true;
        }

        private void onTypeRef(ASTNode node, char[] token, TypeBinding resolved) {
            if (token == null || !String.valueOf(token).equals(target.oldName)) return;
            if (resolved instanceof ReferenceBinding rb
                    && ("type:" + typeKey(rb)).equals(target.bindingKey)) {
                add(nameStart(node, token, source),
                        nameStart(node, token, source) + token.length);
            }
        }

        private void onQualifiedType(QualifiedTypeReference ref) {
            if (ref.tokens == null || ref.tokens.length == 0) return;
            char[] last = ref.tokens[ref.tokens.length - 1];
            if (!String.valueOf(last).equals(target.oldName)) return;
            if (ref.resolvedType instanceof ReferenceBinding rb
                    && ("type:" + typeKey(rb)).equals(target.bindingKey)) {
                long pos = ref.sourcePositions[ref.sourcePositions.length - 1];
                int start = (int) (pos >>> 32);
                int end = (int) pos;
                if (start >= 0 && end >= start) add(start, end + 1);
            }
        }

        private void onQualified(QualifiedNameReference ref) {
            if (ref.tokens.length == 0) return;
            char[] last = ref.tokens[ref.tokens.length - 1];
            if (!String.valueOf(last).equals(target.oldName)) return;
            if (matchesTarget(lastSegmentBinding(ref))) {
                long pos = ref.sourcePositions[ref.sourcePositions.length - 1];
                int start = (int) (pos >>> 32);
                int end = (int) pos;
                if (start >= 0 && end >= start) add(start, end + 1);
            }
        }
    }

    /**
     * Imports du type renommé : {@code import demo.Main;} /
     * {@code import demo.Main.*;} — le DERNIER segment est retouché quand
     * le FQN importé est exactement celui du type.
     */
    private static void collectImportReferences(CompilationUnitDeclaration cud,
                                                String source, RenameTarget target,
                                                List<Occurrence> out) {
        if (cud.imports == null) return;
        String typeFqn = target.bindingKey.substring("type:".length());
        for (ImportReference imp : cud.imports) {
            if (imp.tokens == null || imp.tokens.length == 0) continue;
            StringBuilder fqn = new StringBuilder();
            for (char[] t : imp.tokens) {
                if (fqn.length() > 0) fqn.append('.');
                fqn.append(t);
            }
            boolean exact = fqn.toString().equals(typeFqn);
            boolean onDemand = false;
            if (!exact && imp.tokens.length >= 2) {
                // import demo.Main.*; — le FQN sans le dernier segment *.
                onDemand = fqn.toString().equals(typeFqn + ".*");
            }
            if (!exact && !onDemand) continue;
            // Trouve le dernier segment (le nom du type) dans la déclaration.
            char[] last = imp.tokens[imp.tokens.length - (onDemand ? 2 : 1)];
            if (!String.valueOf(last).equals(target.oldName)) continue;
            int idx = findWord(source, String.valueOf(target.oldName),
                    imp.declarationSourceStart, imp.declarationSourceEnd + 1,
                    /* fromEnd */ true);
            if (idx >= 0) {
                out.add(new Occurrence(idx, idx + target.oldName.length()));
            }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    /**
     * Début du jeton nom d'un nœud : cherche le nom dans la plage du nœud
     * avec vérification de frontière de mot (évite de matcher le type d'un
     * champ).
     */
    static int nameStart(ASTNode node, char[] token, String source) {
        String name = String.valueOf(token);
        return findWord(source, name, node.sourceStart, node.sourceEnd + 1, false);
    }

    /**
     * Cherche {@code word} dans [from, to) avec frontières de mot.
     *
     * @param fromEnd chercher depuis la fin (imports — dernier segment).
     */
    public static int findWord(String source, String word, int from, int to,
                        boolean fromEnd) {
        from = Math.max(0, from);
        to = Math.min(source.length(), to);
        if (from >= to) return -1;
        if (fromEnd) {
            int idx = to - word.length();
            while (idx >= from) {
                if (wordAt(source, idx, word)) return idx;
                idx--;
            }
            return -1;
        }
        int idx = source.indexOf(word, from);
        while (idx >= 0 && idx < to) {
            if (wordAt(source, idx, word)) return idx;
            idx = source.indexOf(word, idx + word.length());
        }
        return -1;
    }

    private static boolean wordAt(String source, int idx, String word) {
        if (idx < 0 || idx + word.length() > source.length()) return false;
        if (!source.startsWith(word, idx)) return false;
        boolean leftOk = idx == 0
                || !Character.isJavaIdentifierPart(source.charAt(idx - 1));
        int end = idx + word.length();
        boolean rightOk = end >= source.length()
                || !Character.isJavaIdentifierPart(source.charAt(end));
        return leftOk && rightOk;
    }
}
