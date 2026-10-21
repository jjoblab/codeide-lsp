package jo.lspjava.ecj;

import org.eclipse.jdt.internal.compiler.ast.Argument;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.ast.LambdaExpression;
import org.eclipse.jdt.internal.compiler.ast.LocalDeclaration;
import org.eclipse.jdt.internal.compiler.ast.MessageSend;
import org.eclipse.jdt.internal.compiler.ast.MethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Inlay hints de <strong>noms de paramètres aux call sites</strong> — basés
 * sur les bindings ecj ({@code MessageSend.binding}).
 *
 * <p>Améliore l'implémentation JavaParser historique (index nom+arité du
 * fichier courant uniquement) sur deux axes :</p>
 * <ul>
 *   <li><b>Résolution exacte</b> — le binding du call site donne la
 *       méthode <em>réellement</em> appelée (surcharge correcte), pas une
 *       approximation par nom+arité.</li>
 *   <li><b>Multi-fichiers</b> — les noms de paramètres sont lus dans la
 *       DÉCLARATION de la méthode, où qu'elle vive dans les racines sources
 *       du projet (résolution du fichier déclarant par FQCN + match de la
 *       clé structurelle). Les appels aux types binaires (android.jar) ne
 *       reçoivent pas de hint (pas de noms dans le bytecode) — comportement
 *       explicite, jamais de faux noms.</li>
 * </ul>
 *
 * <p>Un hint est émis pour chaque argument d'appel dont la méthode déclarante
 * est trouvée dans les sources : {@code greet(name)} reçoit
 * {@code name:} avant l'argument. Les appels sans parenthèses d'arguments
 * sont ignorés.</p>
 *
 * @author jo@Dev
 */
public final class EcjInlayHints {

    /** Un hint : position (offset) + libellé. */
    public static final class Hint {
        public final int offset;
        public final String label;
        /** Padding gauche LSP (espace fantôme avant le libellé). */
        public final boolean padLeft;

        public Hint(int offset, String label) {
            this(offset, label, false);
        }

        public Hint(int offset, String label, boolean padLeft) {
            this.offset = offset;
            this.label = label;
            this.padLeft = padLeft;
        }
    }

    private final EcjCompilerEngine engine;

    /**
     * Cache FQCN → noms de paramètres par clé de méthode (par unité lue).
     * LRU borné à 64 entrées : sur un gros fichier avec beaucoup de méthodes
     * différentes, declCache ne doit pas grossir sans limite — on garde les
     * 64 entrées les plus récemment utilisées.
     */
    private static final int DECL_CACHE_LIMIT = 64;
    private final Map<String, Map<String, List<String>>> declCache =
            java.util.Collections.synchronizedMap(
                new java.util.LinkedHashMap<String, Map<String, List<String>>>(DECL_CACHE_LIMIT, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(java.util.Map.Entry<String, Map<String, List<String>>> eldest) {
                        return size() > DECL_CACHE_LIMIT;
                    }
                });

    public EcjInlayHints(EcjCompilerEngine engine) {
        this.engine = engine;
    }

    /**
     * Calcule les hints de noms de paramètres pour toute une unité.
     *
     * @param filePath chemin du fichier (FQCN de l'unité)
     * @param source   le texte complet
     * @return les hints (offset de chaque argument), jamais null
     */
    public List<Hint> hintsFor(String filePath, String source) {
        List<Hint> out = new ArrayList<>();
        if (EcjCompilerEngine.DISABLED) return out;
        try {
            CompilationUnitDeclaration cud = engine.resolveUnit(filePath, source, false);
            if (cud == null || cud.scope == null) return out;

            // Indexe les déclarations de L'UNITÉ COURANTE sous leur FQCN :
            // les appels intra-fichier résolvent vers elles MÊME quand
            // l'engine n'a aucune racine source (le fichier ouvert peut
            // vivre hors des racines déclarées).
            if (cud.types != null) {
                for (TypeDeclaration td : cud.types) {
                    if (td.binding == null) continue;
                    String fqn = String.valueOf(td.binding.readableName());
                    Map<String, List<String>> methods = new HashMap<>();
                    collectMethodParams(td, methods);
                    if (!methods.isEmpty()) {
                        declCache.putIfAbsent(fqn, methods);
                    }
                }
            }

            EcjAst.traverseResilient(cud, new org.eclipse.jdt.internal.compiler.ASTVisitor() {
                @Override
                public boolean visit(MessageSend ms,
                        org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
                    collect(ms.binding, ms.arguments, out);
                    return true;
                }
            });
        } catch (Throwable ignored) {
            // Traversée partielle — on garde ce qui est collecté.
        }
        return out;
    }

    /**
     * Hints de TYPE des déclarations {@code var} par BINDINGS ecj
     * (portage {@code JdtInlayHintService} du projet tiers de référence :
     * {@code node.type.isVar → frag.resolveBinding()?.type}).
     *
     * <p>Remplace l'inférence TEXTUELLE historique (littéraux simples
     * uniquement) : le binding donne le type RÉEL de l'initialiseur —
     * appels de méthode, ternaires, enhanced-for {@code for (var x : …)},
     * appels chaînés, génériques inclus ({@code List<String>}). Un
     * {@code var} non résolu (initialiseur en erreur) ne reçoit PAS de
     * hint — jamais de faux type.</p>
     *
     * @return un hint par {@code var} résolu, à la fin du NOM (offset
     *         exclusif), libellé {@code ": Type"}
     */
    public List<Hint> typeHintsFor(String filePath, String source) {
        List<Hint> out = new ArrayList<>();
        if (EcjCompilerEngine.DISABLED) return out;
        try {
            CompilationUnitDeclaration cud = engine.resolveUnit(filePath, source, false);
            if (cud == null || cud.scope == null) return out;
            EcjAst.traverseResilient(cud, new org.eclipse.jdt.internal.compiler.ASTVisitor() {
                @Override
                public boolean visit(LocalDeclaration ld,
                        org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
                    String label = varTypeHintLabel(ld);
                    if (label != null && ld.sourceEnd >= 0) {
                        // sourceEnd = DERNIER caractère du nom (inclusif,
                        // calibré empiriquement) → le hint s'ancre juste
                        // après le nom, avant le « = ».
                        out.add(new Hint(ld.sourceEnd + 1, label));
                    }
                    return true;
                }
            });
        } catch (Throwable ignored) {
            // Traversée partielle — on garde ce qui est collecté.
        }
        return out;
    }

    /**
     * Portage {@code JdtInlayHintService} du projet tiers de référence (les deux
     * familles qui manquaient) :
     * <ul>
     *   <li><b>Chaining hints</b> — sur un chaînage FLUENT MULTI-LIGNE, le
     *       type du récepteur est affiché à la fin de SA ligne, juste avant
     *       le « . » de l'appel suivant (parité
     *       {@code chainingHint(node)} : récepteur lui-même un
     *       MethodInvocation, ligne(fin du récepteur) != ligne(nom de
     *       l'appel), libellé = nom court du type SANS deux-points,
     *       padding gauche). Même ligne ⇒ pas de hint (juste du bruit).</li>
     *   <li><b>Hints de type des paramètres lambda</b> — chaque paramètre
     *       lambda AU TYPE IMPLICITE reçoit « : Type » à la fin de son nom
     *       (parité {@code visit(LambdaExpression)} : les paramètres typés
     *       explicitement {@code (String s) ->} sont ignorés — ecj les
     *       représente avec un TypeReference non-null).</li>
     * </ul>
     *
     * <p>Types par bindings ecj ({@code Expression.resolvedType} du
     * récepteur, {@code Argument.binding.type} du paramètre lambda) — un
     * binding non résolu ne reçoit JAMAIS de hint (parité projet tiers de référence : pas
     * de faux type).</p>
     *
     * @return les hints des deux familles (ordre de traversal)
     */
    public List<Hint> chainedAndLambdaHintsFor(String filePath, String source) {
        List<Hint> out = new ArrayList<>();
        if (EcjCompilerEngine.DISABLED) return out;
        try {
            CompilationUnitDeclaration cud = engine.resolveUnit(filePath, source, false);
            if (cud == null || cud.scope == null) return out;
            // Index des débuts de ligne (calcul UNE fois par requête — la
            // parité multi-lignes des chaining hints en a besoin).
            int[] lineStarts = lineStartsOf(source);
            EcjAst.traverseResilient(cud, new org.eclipse.jdt.internal.compiler.ASTVisitor() {
                @Override
                public boolean visit(MessageSend ms,
                        org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
                    collectChainingHint(ms, source, lineStarts, out);
                    return true;
                }

                @Override
                public boolean visit(LambdaExpression lambda,
                        org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
                    collectLambdaParamHints(lambda, out);
                    return true;
                }
            });
        } catch (Throwable ignored) {
            // Traversée partielle — on garde ce qui est collecté.
        }
        return out;
    }

    /**
     * Le hint de chaînage d'un appel : émis quand le RÉCEPTEUR est
     * lui-même un appel et que l'appel suivant est sur une AUTRE ligne.
     */
    private static void collectChainingHint(MessageSend ms, String source,
                                            int[] lineStarts, List<Hint> out) {
        if (!(ms.receiver instanceof MessageSend recv)) return;
        if (recv.sourceStart < 0 || recv.sourceEnd < 0) return;
        int recvEnd = recv.sourceEnd + 1; // fin du récepteur (offset exclusif)
        int nameStart = ms.nameSourceStart();
        if (nameStart < 0) return;
        // Même ligne ⇒ pas de hint (projet tiers de référence : « otherwise it's just
        // noise » — seuil binaire ligne/ligne, pas un seuil configurable).
        if (lineOf(lineStarts, recvEnd) == lineOf(lineStarts, nameStart)) return;
        TypeBinding t = recv.resolvedType;
        if (t == null && recv.binding != null && recv.binding.isValidBinding()) {
            t = recv.binding.returnType; // repli : type de retour déclaré
        }
        if (t == null || !t.isValidBinding()) return;
        String label = EcjCompilerEngine.display(t);
        if (label == null || label.isEmpty() || "?".equals(label)) return;
        // Ancre : fin du récepteur (fin de SA ligne) ; padding gauche
        // (le libellé est collé sinon au dernier caractère de l'appel).
        if (recvEnd >= 0 && recvEnd <= source.length()) {
            out.add(new Hint(recvEnd, label, true));
        }
    }

    /**
     * Les hints « : Type » des paramètres lambda au type implicite — les
     * paramètres typés explicitement portent un TypeReference non-null et
     * sont ignorés (parité projet tiers de référence VariableDeclarationFragment).
     */
    private static void collectLambdaParamHints(LambdaExpression lambda,
                                                List<Hint> out) {
        Argument[] args = lambda.arguments;
        if (args == null) return;
        for (Argument p : args) {
            if (p == null || p.name == null) continue;
            // `var` en paramètre de lambda : un simple test `p.type != null`
            // skipperait les paramètres typés, mais `var x` dans une lambda
            // est parsé avec p.type != null (TypeReference représentant le
            // type inféré). On skip donc les paramètres typés explicites
            // (non-var), mais on garde les `var` pour qu'ils reçoivent un hint.
            // Détection de `var` : un Argument avec un type qui résout vers
            // java.lang.Object ou qui est un SingleTypeReference nommé "var"
            // est un `var`. Plus simple : on check le source entre sourceStart
            // et le nom — si ça commence par "var", c'est un `var`.
            boolean isVar = false;
            if (p.type != null && p.sourceStart >= 0 && p.name != null) {
                // Heuristique source : le jeton "var" précède le nom.
                // p.sourceStart est le début de la déclaration (y compris type).
                // On ne peut pas facilement accéder au source ici (méthode static),
                // donc on utilise une heuristique sur le TypeReference : si c'est
                // un SingleTypeReference dont le token est "var", c'est un var.
                try {
                    if (p.type instanceof org.eclipse.jdt.internal.compiler.ast.SingleTypeReference str
                            && str.token != null && str.token.length == 3
                            && str.token[0] == 'v' && str.token[1] == 'a' && str.token[2] == 'r') {
                        isVar = true;
                    }
                } catch (Throwable ignored) {
                    // Best-effort.
                }
            }
            if (p.type != null && !isVar) continue;
            if (p.binding == null || !p.binding.isValidBinding()
                    || p.binding.type == null) {
                continue;
            }
            String label = ": " + EcjCompilerEngine.display(p.binding.type);
            if (label.equals(": ?") || label.equals(": ")) continue;
            // Ancre : fin du nom du paramètre (sourceEnd = dernier
            // caractère inclusif — même calibration que LocalDeclaration).
            if (p.sourceEnd >= 0) {
                out.add(new Hint(p.sourceEnd + 1, label));
            }
        }
    }

    /**
     * Les débuts de ligne du source (offset de chaque '\n' + 1, plus 0) —
     * triés, pour {@link #lineOf} en recherche binaire.
     */
    private static int[] lineStartsOf(String source) {
        int count = 1;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') count++;
        }
        int[] starts = new int[count];
        starts[0] = 0;
        int idx = 1;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') starts[idx++] = i + 1;
        }
        return starts;
    }

    /** La ligne (0-based) contenant {@code offset} — recherche binaire. */
    private static int lineOf(int[] lineStarts, int offset) {
        if (lineStarts.length == 0) return 0;
        if (offset <= lineStarts[0]) return 0;
        int lo = 0, hi = lineStarts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lineStarts[mid] <= offset) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    /**
     * Le libellé « : Type » d'une déclaration {@code var} résolue, ou null
     * (déclaration typée explicitement, binding absent/invalide, ou type
     * littéral non-inféré).
     */
    private static String varTypeHintLabel(LocalDeclaration ld) {
        if (ld.type == null || ld.name == null) return null;
        if (!(ld.type instanceof org.eclipse.jdt.internal.compiler.ast.SingleTypeReference ref)) {
            return null;
        }
        if (!"var".equals(String.valueOf(ref.token))) return null;
        if (ld.binding == null || !ld.binding.isValidBinding()
                || ld.binding.type == null) {
            return null;
        }
        String type = EcjCompilerEngine.display(ld.binding.type);
        if (type == null || type.isEmpty() || "?".equals(type)) return null;
        return ": " + type;
    }

    /** Émet un hint par argument dont les noms de paramètres sont connus. */
    private void collect(MethodBinding binding, Expression[] arguments,
                         List<Hint> out) {
        if (binding == null || !binding.isValidBinding() || arguments == null) {
            return;
        }
        if (binding.isConstructor() || binding.parameters == null) return;
        if (arguments.length == 0) return;

        List<String> names = parameterNames(binding);
        if (names == null) return; // méthode binaire — pas de hint

        for (int i = 0; i < arguments.length && i < names.size(); i++) {
            String name = names.get(i);
            if (name == null || name.isEmpty()) continue;
            Expression arg = arguments[i];
            if (arg == null || arg.sourceStart < 0) continue;
            out.add(new Hint(arg.sourceStart, name + ":"));
        }
    }

    /**
     * Noms des paramètres d'une méthode : cherche la DÉCLARATION dans les
     * sources du projet (FQCN de la classe déclarante → fichier → match de
     * la clé structurelle) et lit ses {@code Argument[]}.
     */
    /** Accès partagé avec {@link EcjSignatureHelp}. */
    List<String> parameterNamesPublic(MethodBinding binding) {
        return parameterNames(binding);
    }

    private List<String> parameterNames(MethodBinding binding) {
        String key = EcjRenameEngine.methodKey(binding);
        if (key == null || binding.declaringClass == null) return null;

        TypeBinding declaring = binding.declaringClass.erasure() != null
                ? binding.declaringClass.erasure() : binding.declaringClass;
        String fqn = String.valueOf(declaring.readableName());

        // Le cache D'ABORD — il contient notamment les déclarations de
        // l'unité COURANTE (indexées par hintsFor) : un appel intra-fichier
        // résout sans qu'aucune racine source soit nécessaire.
        Map<String, List<String>> methods = declCache.get(fqn);
        if (methods == null) {
            String filePath = sourceFileOf(fqn);
            if (filePath == null) return null; // binaire — pas de noms
            methods = declCache.computeIfAbsent(fqn,
                    k -> readMethodParams(filePath, fqn));
        }
        return methods.get(key);
    }

    /**
     * Résout le fichier source d'un FQCN sous les racines de l'engine
     * (cache de l'index partagé : sources relues à chaque requête).
     */
    private String sourceFileOf(String fqn) {
        String rel = fqn.replace('.', '/') + ".java";
        for (Path root : engine.sourceRoots()) {
            Path p = root.resolve(rel);
            if (Files.isRegularFile(p)) {
                return p.toAbsolutePath().toString();
            }
        }
        return null;
    }

    /** Lit les noms de paramètres de chaque méthode déclarée dans un fichier. */
    private Map<String, List<String>> readMethodParams(String filePath, String fqn) {
        Map<String, List<String>> methods = new HashMap<>();
        try {
            String content = new String(Files.readAllBytes(
                    java.nio.file.Paths.get(filePath)), StandardCharsets.UTF_8);
            CompilationUnitDeclaration cud = engine.resolveUnit(filePath, content, false);
            if (cud == null || cud.types == null) return methods;
            for (TypeDeclaration td : cud.types) {
                collectMethodParams(td, methods);
            }
        } catch (Throwable ignored) {
            // Fichier illisible — map vide.
        }
        return methods;
    }

    private static void collectMethodParams(TypeDeclaration td,
                                            Map<String, List<String>> methods) {
        if (td.methods != null) {
            for (var md : td.methods) {
                if (!(md instanceof MethodDeclaration meth)) continue;
                String key = EcjRenameEngine.methodKey(meth.binding);
                if (key == null || meth.arguments == null) continue;
                List<String> names = new ArrayList<>(meth.arguments.length);
                for (Argument a : meth.arguments) {
                    names.add(String.valueOf(a.name));
                }
                methods.putIfAbsent(key, names);
            }
        }
        if (td.memberTypes != null) {
            for (TypeDeclaration nested : td.memberTypes) {
                collectMethodParams(nested, methods);
            }
        }
    }
}
