package jo.lspjava.ecj;

import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.MessageSend;
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aide de signature (popup de paramètres) basée sur les bindings ecj —
 * portage de l'approche {@code EcjSignatureHelpService} du projet tiers de référence,
 * simplifié.
 *
 * <p><strong>Fast-path (v1.6.0) :</strong> l'aide de signature est une
 * requête par-frappe typique (le client la déclenche dans la liste
 * d'arguments en cours de saisie). Quand un diagnose rapide P6 a déjà
 * résolu la source focalisée pour CE contenu, {@link #signatureHelpAt}
 * sert l'appel depuis l'arbre retenu — zéro re-parse, offsets réels
 * directement utilisables (source isomorphe). Repli en chaîne : arbre
 * réel du parseCache (diagnose complet du même contenu), puis réduction
 * {@link SourceFocuser} (chemin v1.5).</p>
 *
 * <p>Améliore l'implémentation JavaParser historique (une seule signature,
 * méthode du fichier courant) :</p>
 * <ul>
 *   <li><b>Toutes les surcharges</b> — pour l'appel sous le curseur, on
 *       énumère les méthodes du type receveur (héritées comprises) portant
 *       le même sélecteur ; les types binaires (android.jar) sont couverts
 *       puisque les signatures vivent dans les bindings.</li>
 *   <li><b>Paramètre actif</b> — l'index de l'argument en cours de saisie
 *       est calculé en comptant les virgules non protégées (hors chaînes,
 *       caractères, parenthèses/générics imbriqués) entre l'ouverture et le
 *       caret.</li>
 *   <li><b>Noms de paramètres</b> — depuis la déclaration source quand elle
 *       est trouvée dans le projet (même résolution que
 *       {@link EcjInlayHints}) ; sinon {@code arg0, arg1…} pour les
 *       binaires.</li>
 *   <li><b>Documentation</b> — la javadoc de la déclaration
 *       source de la signature ACTIVE (commentaire de documentation qui
 *       précède la méthode, extrait via l'AST ecj du fichier déclarant) ;
 *       le popup la rend sous la liste des surcharges (motif des IDE de
 *       projet tiers de référence). Vide pour les méthodes binaires sans source.</li>
 * </ul>
 *
 * @author jo@Dev
 */
public final class EcjSignatureHelp {

    /** Une entrée de signature. */
    public static final class Signature {
        public final String label;
        public final List<String> parameterNames;
        /** Index du paramètre actif (-1 si inconnu). */
        public final int activeParameter;
        /** Javadoc de la déclaration source (signature active seulement). */
        public final String documentation;

        Signature(String label, List<String> parameterNames, int activeParameter) {
            this(label, parameterNames, activeParameter, "");
        }

        Signature(String label, List<String> parameterNames, int activeParameter,
                String documentation) {
            this.label = label;
            this.parameterNames = parameterNames;
            this.activeParameter = activeParameter;
            this.documentation = documentation != null ? documentation : "";
        }
    }

    /** Résultat : signatures (surcharges) + index actif. */
    public static final class Result {
        public final List<Signature> signatures;
        public final int activeSignature;
        public final int activeParameter;

        Result(List<Signature> signatures, int activeSignature, int activeParameter) {
            this.signatures = signatures;
            this.activeSignature = activeSignature;
            this.activeParameter = activeParameter;
        }
    }

    private final EcjCompilerEngine engine;
    private final EcjInlayHints inlayHints;

    public EcjSignatureHelp(EcjCompilerEngine engine) {
        this.engine = engine;
        this.inlayHints = new EcjInlayHints(engine);
        // binaryNames est partagé via engine.binaryNames() — son pool LRU
        // vit pour la durée du moteur (pas du service), évitant les fuites
        // quand un nouveau EcjSignatureHelp est créé à chaque signature-help.
    }

    /**
     * Calcule l'aide de signature au offset.
     *
     * <p><strong>Chaîne de résolution (v1.16.0, du moins cher au plus
     * cher) :</strong></p>
     * <ol>
     *   <li>arbre focalisé du dernier diagnose RAPIDE (P6 étendu) —
     *       servi tel quel si le contenu du tampon est identique et le
     *       caret dans le corps hôte (garde
     *       {@link EcjCompilerEngine#resolveUnitFast}) ;</li>
     *   <li>arbre RÉEL du parseCache (diagnose complet de ce contenu) —
     *       probe pure via {@link EcjCompilerEngine#cachedUnit} ;</li>
     *   <li>réduction {@link SourceFocuser} + resolveUnit (chemin
     *       v1.5) ;</li>
     *   <li><b>variantes réparées</b> (v1.16.0) — la frappe en cours
     *       laisse l'appel non refermé ou l'argument non résoluble : on
     *       referme avec un argument synthétique
     *       ({@link #computeFromSplices}).</li>
     * </ol>
     * <p>Un niveau qui ne trouve pas d'appel (ou échoue) retombe sur le
     * suivant : le pire cas reste le coût v1.5, jamais moins bien.</p>
     *
     * @param filePath chemin du fichier
     * @param source   le texte complet
     * @param offset   position du caret (dans la liste d'arguments)
     * @return le résultat, ou null si le caret n'est pas dans un appel
     *         résolu.
     */
    public Result signatureHelpAt(String filePath, String source, int offset) {
        if (EcjCompilerEngine.DISABLED) return null;

        // ── Niveau 1 : arbre focalisé du dernier diagnose rapide (P6
        // étendu, v1.6.0). Source isomorphe → source et offset RÉELS
        // utilisés tels quels (aucun mapBack).
        try {
            CompilationUnitDeclaration fast =
                    engine.resolveUnitFast(filePath, source, offset);
            if (fast != null) {
                Result r = computeAt(fast, source, offset);
                if (r != null) return r;
            }
        } catch (Throwable t) {
            // Best-effort — repli sur le niveau suivant.
        }

        // ── Niveau 2 : arbre réel du parseCache (diagnose complet de CE
        // contenu — déjà résolu, probe pure sans parse).
        try {
            CompilationUnitDeclaration real =
                    engine.cachedUnit(filePath, source);
            if (real != null) {
                Result r = computeAt(real, source, offset);
                if (r != null) return r;
            }
        } catch (Throwable t) {
            // Best-effort — repli sur le niveau suivant.
        }

        // ── Niveau 3 : chemin v1.5 — SourceFocuser réduit le coût de la
        // résolution (corps hors-portée effacés, offset traduit).
        SourceFocuser.Focused focused = null;
        try {
            focused = SourceFocuser.focusAndMap(source, offset);
            String workSource = focused.source();
            int workOffset = focused.offset();

            CompilationUnitDeclaration cud = engine.resolveUnit(filePath, workSource, false);
            if (cud != null && cud.scope != null) {
                Result r = computeAt(cud, workSource, workOffset);
                if (r != null) return r;
            }
        } catch (Throwable t) {
            // Best-effort — repli sur le niveau 4.
        }

        // ── Niveau 4 : texte vivant cassé (v1.16.0). La frappe EN COURS
        // laisse l'appel non refermé (`add(`, `add(1, `), la chaîne
        // ouverte (`add("abc`) ou l'argument courant non résoluble
        // (`add(1, par`) — ecj jette alors le MessageSend en recovery
        // (binding absent/invalid) et les niveaux 1-3 rendent null. On
        // referme l'appel avec un ARGUMENT SYNTHÉTIQUE et les blocs
        // ouverts, en essayant plusieurs littéraux de secours (null pour
        // les références, 0 pour les numériques, true pour les
        // booléens) : la première variante qui rend un appel résolu
        // gagne. Le splice n'ajoute JAMAIS rien AVANT le caret → le
        // paramètre actif compté sur la source splicée est exactement
        // celui de la frappe réelle.
        try {
            if (focused != null) {
                Result r = computeFromSplices(filePath, focused);
                if (r != null) return r;
            }
        } catch (Throwable t) {
            // Best-effort — null reste la réponse finale.
        }
        return null;
    }

    /**
     * Niveau 4 de {@link #signatureHelpAt} : variantes réparées pour le
     * texte vivant cassé. Transposition du principe des splice-variants
     * de la complétion ({@code EcjCompletionEngine}) au cas signature :
     * le caret est DANS une liste d'arguments, on abandonne la queue du
     * fichier et on synthétise une fermeture équilibrée.
     *
     * <p>Variantes (du mieux au plus sacrificiel) :</p>
     * <ol>
     *   <li><b>A — texte tel quel</b> : on referme juste les littéraux et
     *       blocs ouverts. Couvre {@code add(} (fermeture immédiate) et
     *       les arguments déjà complets ({@code add(1, 2}) ;</li>
     *   <li><b>B/C — littéral apposé</b> : {@code null} puis {@code 0}
     *       ajouté à l'argument courant. Couvre l'argument vide
     *       ({@code add(1, } → {@code add(1, null)}) et la suite
     *       d'opérateur ({@code add(1, 2 + }) ;</li>
     *   <li><b>D/E/F — littéral substitué</b> : l'argument courant (non
     *       résoluble, mot en cours de frappe) est REMPLACÉ par
     *       {@code null}, {@code 0} ou {@code true}. Couvre
     *       {@code add(1, par} → {@code add(1, null)}.</li>
     * </ol>
     *
     * <p>Le compte de virgules (paramètre actif) est préservé par
     * construction : la substitution coupe APRÈS le dernier séparateur
     * de l'appel interne — aucun séparateur n'est ajouté ni retiré avant
     * la position du caret.</p>
     */
    private Result computeFromSplices(String filePath,
            SourceFocuser.Focused focused) {
        String workSource = focused.source();
        int workOffset = focused.offset();
        String head = workSource.substring(0, workOffset);
        HeadScan scan = scanHead(head);
        // Le caret doit vivre dans une liste d'arguments : au moins une
        // parenthèse d'appel encore ouverte dans la tête.
        if (!scan.hasOpenCallParen) return null;

        // Début de l'argument courant : après le DERNIER séparateur
        // (virgule) ou la DERNIÈRE parenthèse ouverte — l'appel interne
        // est celui dont la parenthèse est la plus récente.
        int argStart = Math.max(scan.lastComma, scan.lastParen) + 1;
        String partial = head.substring(argStart);
        boolean blankPartial = partial.isBlank();
        boolean afterComma = scan.lastComma > scan.lastParen;

        // Variante A — le texte tapé reste tel quel (invalid si la
        // liste finit sur une virgule : `add(1, )` ne parse pas).
        if (!(blankPartial && afterComma)) {
            Result r = trySplice(filePath, head + scan.literalClose()
                    + scan.statementTail(), workOffset);
            if (r != null) return r;
        }
        // Variantes B/C — littéral APPPOSÉ à l'argument courant
        // (la chaîne ouverte est refermée d'abord).
        for (String lit : new String[] {"null", "0"}) {
            Result r = trySplice(filePath, head + scan.literalClose()
                    + (scan.inLiteral() ? ", " : "") + lit
                    + scan.statementTail(), workOffset);
            if (r != null) return r;
        }
        // Variantes D/E/F — l'argument courant est REMPLACÉ par un
        // littéral ; la tête est coupée à argStart, les fermetures se
        // calculent sur la tête coupée (les crochets de l'argument
        // abandonné ne doivent pas compter).
        String cutHead = head.substring(0, argStart);
        HeadScan cutScan = scanHead(cutHead);
        int cutOffset = argStart;
        for (String lit : new String[] {"null", "0", "true"}) {
            Result r = trySplice(filePath, cutHead + lit
                    + cutScan.statementTail(), cutOffset + lit.length());
            if (r != null) return r;
        }
        return null;
    }

    /** Résout une variante splicée puis y calcule l'aide de signature. */
    private Result trySplice(String filePath, String spliced, int workOffset) {
        try {
            CompilationUnitDeclaration cud =
                    engine.resolveUnit(filePath, spliced, false);
            if (cud == null || cud.scope == null) return null;
            return computeAt(cud, spliced, workOffset);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Analyse lexicale de la tête (tout ce qui précède le caret) : pile
     * des crochets ouverts, état de littéral (chaîne/caractère), et
     * positions du dernier séparateur d'arguments. Aveugle aux
     * commentaires (une parenthèse dans un commentaire fausse la
     * variante correspondante — elle échoue proprement, les autres
     * restent essayées).
     */
    private static HeadScan scanHead(String head) {
        HeadScan s = new HeadScan();
        boolean escaped = false;
        for (int i = 0; i < head.length(); i++) {
            char c = head.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (s.inString) {
                if (c == '"') s.inString = false;
                continue;
            }
            if (s.inChar) {
                if (c == '\'') s.inChar = false;
                continue;
            }
            switch (c) {
                case '"' -> s.inString = true;
                case '\'' -> s.inChar = true;
                case ',' -> s.lastComma = i;
                case '(' -> {
                    s.lastParen = i;
                    s.open.push(c);
                }
                case '[', '{' -> s.open.push(c);
                case ')', ']', '}' -> {
                    if (!s.open.isEmpty()) s.open.pop();
                }
                default -> { }
            }
        }
        s.hasOpenCallParen = s.open.contains('(');
        return s;
    }

    /**
     * Résultat de {@link #scanHead} — fermetures synthétiques pour les
     * variantes du niveau 4.
     */
    private static final class HeadScan {
        boolean inString;
        boolean inChar;
        int lastComma = -1;
        int lastParen = -1;
        boolean hasOpenCallParen;
        final java.util.ArrayDeque<Character> open = new java.util.ArrayDeque<>();

        boolean inLiteral() { return inString || inChar; }

        /** Fermeture du littéral en cours ("" si aucun). */
        String literalClose() {
            if (inString) return "\"";
            if (inChar) return "'";
            return "";
        }

        /**
         * Queue de fermeture : referme les crochets ouverts (le plus
         * interne d'abord) et termine l'instruction par «;» PLACÉ AVANT
         * LA PREMIÈRE ACCOLADE — {@code add(} devient {@code add();}},
         * jamais {@code add(;}}.
         */
        String statementTail() {
            StringBuilder sb = new StringBuilder();
            for (char c : open) {
                if (c == '{' && sb.indexOf(";") < 0) sb.append(';');
                switch (c) {
                    case '(' -> sb.append(')');
                    case '[' -> sb.append(']');
                    case '{' -> sb.append('}');
                    default -> { }
                }
            }
            if (sb.indexOf(";") < 0) sb.append(';');
            return sb.toString();
        }
    }

    /**
     * Calcule l'aide de signature depuis un arbre DÉJÀ résolu — corps
     * commun aux trois niveaux de résolution de
     * {@link #signatureHelpAt} (l'arbre ne sait pas d'où il vient ;
     * {@code workSource}/{@code workOffset} sont dans le système de
     * coordonnées de CET arbre).
     *
     * @return le résultat, ou null (caret hors appel résolu)
     */
    private Result computeAt(CompilationUnitDeclaration cud,
                             String workSource, int workOffset) {
        try {
            if (cud == null || cud.scope == null) return null;

            CallFinder finder = new CallFinder(workSource, workOffset);
            EcjAst.traverseResilient(cud, finder);

            // Gestion des constructeurs (new Foo() et this()/super()) : on
            // collecte MessageSend, AllocationExpression et
            // ExplicitConstructorCall.
            MessageSend call = finder.call;
            org.eclipse.jdt.internal.compiler.ast.AllocationExpression alloc = finder.allocation;
            org.eclipse.jdt.internal.compiler.ast.ExplicitConstructorCall ctorCall = finder.ctorCall;

            MethodBinding binding = null;
            int callSourceStart = -1;
            ReferenceBinding receiverType = null;

            if (call != null && call.binding != null && call.binding.isValidBinding()) {
                binding = call.binding;
                callSourceStart = call.sourceStart;
                if (call.binding.declaringClass != null) {
                    receiverType = call.binding.declaringClass;
                }
            } else if (alloc != null && alloc.binding != null && alloc.binding.isValidBinding()) {
                // new Foo(...) — binding est le constructeur.
                binding = alloc.binding;
                callSourceStart = alloc.sourceStart;
                if (alloc.binding.declaringClass != null) {
                    receiverType = alloc.binding.declaringClass;
                }
            } else if (ctorCall != null && ctorCall.binding != null && ctorCall.binding.isValidBinding()) {
                // this(...)/super(...) — binding est le constructeur ciblé.
                binding = ctorCall.binding;
                callSourceStart = ctorCall.sourceStart;
                if (ctorCall.binding.declaringClass != null) {
                    receiverType = ctorCall.binding.declaringClass;
                }
            }

            if (binding == null) {
                return null;
            }

            String selector = String.valueOf(binding.selector);
            int activeParam = countActiveParameter(workSource,
                    callSourceStart, workOffset);

            if (receiverType == null) {
                // Pas de graphe — une seule signature (celle du binding).
                return single(binding, activeParam);
            }
            ReferenceBinding refType = receiverType;

            // Énumère les méthodes de même sélecteur sur le graphe — ou
            // les CONSTRUCTEURS du type quand l'appel en est un (v1.16.0 :
            // `new StringBuilder(` énumère (), (int), (String),
            // (CharSequence) au lieu d'une signature isolée).
            boolean wantCtors = binding.isConstructor();
            Map<String, Signature> byKey = new LinkedHashMap<>();
            collectOverloads(refType, selector, byKey, wantCtors);

            if (byKey.isEmpty()) {
                return single(binding, activeParam);
            }

            List<Signature> signatures = new ArrayList<>(byKey.values());
            // Signature active : celle du binding RÉEL de l'appel.
            String activeKey = EcjRenameEngine.methodKey(binding);
            int activeIdx = Math.max(0, indexOfKey(byKey, activeKey));
            // Documentation (javadoc) de la signature ACTIVE.
            if (activeIdx < signatures.size()) {
                Signature plain = signatures.get(activeIdx);
                if (!plain.documentation.isEmpty()) {
                    signatures.set(activeIdx, plain);
                } else {
                    signatures.set(activeIdx, new Signature(plain.label,
                            plain.parameterNames, plain.activeParameter,
                            docOf(binding)));
                }
            }
            return new Result(signatures, activeIdx, activeParam);
        } catch (Throwable t) {
            return null;
        }
    }

    private Result single(MethodBinding binding, int activeParam) {
        List<String> names = parameterNamesOf(binding);
        return new Result(List.of(new Signature(toSignature(binding, names).label,
                names, activeParam, docOf(binding))),
                0, activeParam);
    }

    // ─── Documentation (javadoc source) ────────────────────

    /** Cache javadoc : FQN du type déclarant → (methodKey → javadoc). */
    private final Map<String, Map<String, String>> docCache = new java.util.HashMap<>();

    /**
     * La javadoc de la DÉCLARATION source du binding (commentaire de
     * documentation qui précède la méthode), ou "" pour une méthode
     * binaire sans source. Pattern du cache de noms de paramètres
     * ({@code EcjInlayHints.declCache}).
     */
    private String docOf(MethodBinding binding) {
        try {
            if (binding == null || binding.declaringClass == null) return "";
            String key = EcjRenameEngine.methodKey(binding);
            if (key == null) return "";
            ReferenceBinding declaring = (ReferenceBinding)
                    (binding.declaringClass.erasure() != null
                    ? binding.declaringClass.erasure() : binding.declaringClass);
            String fqn = String.valueOf(declaring.readableName());
            Map<String, String> docs = docCache.get(fqn);
            if (docs == null) {
                docs = readMethodDocs(sourceFileOf(fqn), fqn);
                docCache.putIfAbsent(fqn, docs);
            }
            String doc = docs.get(key);
            return doc != null ? doc : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** Résout le fichier source d'un FQCN sous les racines de l'engine. */
    private String sourceFileOf(String fqn) {
        String rel = fqn.replace('.', '/') + ".java";
        for (java.nio.file.Path root : engine.sourceRoots()) {
            java.nio.file.Path p = root.resolve(rel);
            if (java.nio.file.Files.isRegularFile(p)) {
                return p.toAbsolutePath().toString();
            }
        }
        return null;
    }

    /**
     * Scan AST du fichier déclarant : methodKey → javadoc brute (le
     * commentaire de documentation complet). La javadoc ecj porte
     * sourceStart/sourceEnd du COMMENTAIRE — on extrait la tranche du
     * contenu original.
     */
    private Map<String, String> readMethodDocs(String filePath, String fqn) {
        Map<String, String> docs = new java.util.HashMap<>();
        if (filePath == null) return docs;
        try {
            String content = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(filePath)), java.nio.charset.StandardCharsets.UTF_8);
            CompilationUnitDeclaration cud = engine.resolveUnit(filePath, content, false);
            if (cud == null || cud.types == null) return docs;
            for (org.eclipse.jdt.internal.compiler.ast.TypeDeclaration td : cud.types) {
                collectMethodDocs(td, content, docs);
            }
        } catch (Throwable ignored) {
            // Fichier illisible — map vide.
        }
        return docs;
    }

    private void collectMethodDocs(
            org.eclipse.jdt.internal.compiler.ast.TypeDeclaration td,
            String content, Map<String, String> docs) {
        try {
            if (td.methods != null) {
                for (org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration md : td.methods) {
                    if (md == null || md.binding == null) continue;
                    String key = EcjRenameEngine.methodKey(md.binding);
                    if (key == null || docs.containsKey(key)) continue;
                    if (md.javadoc != null && md.javadoc.sourceStart >= 0
                            && md.javadoc.sourceEnd > md.javadoc.sourceStart
                            && md.javadoc.sourceEnd < content.length()) {
                        docs.put(key, content.substring(
                                md.javadoc.sourceStart, md.javadoc.sourceEnd + 1));
                    }
                }
            }
            if (td.memberTypes != null) {
                for (org.eclipse.jdt.internal.compiler.ast.TypeDeclaration inner : td.memberTypes) {
                    collectMethodDocs(inner, content, docs);
                }
            }
        } catch (Throwable ignored) {
            // Traversée partielle.
        }
    }

    private int indexOfKey(Map<String, Signature> byKey, String activeKey) {
        int i = 0;
        for (String key : byKey.keySet()) {
            if (key.equals(activeKey)) return i;
            i++;
        }
        return 0;
    }

    /**
     * Marche le graphe des super-types en collectant les surcharges —
     * ou les constructeurs du type ({@code wantCtors}).
     */
    private void collectOverloads(ReferenceBinding type, String selector,
                                  Map<String, Signature> byKey,
                                  boolean wantCtors) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        visitOverloads(type, selector, byKey, seen, wantCtors);
    }

    private void visitOverloads(ReferenceBinding type, String selector,
                                Map<String, Signature> byKey,
                                java.util.Set<String> seen,
                                boolean wantCtors) {
        if (type == null || !seen.add(keyOf(type))) return;
        try {
            for (MethodBinding m : type.methods()) {
                if (m == null || m.isSynthetic()) continue;
                if (m.isConstructor() != wantCtors) continue;
                if (!wantCtors
                        && !String.valueOf(m.selector).equals(selector)) {
                    continue;
                }
                String key = EcjRenameEngine.methodKey(m);
                if (key == null || byKey.containsKey(key)) continue;
                byKey.put(key, toSignature(m, parameterNamesOf(m)));
            }
        } catch (Throwable ignored) {
            // Best-effort.
        }
        if (wantCtors) return; // les constructeurs ne s'héritent pas
        try {
            visitOverloads(type.superclass(), selector, byKey, seen, false);
            for (ReferenceBinding itf : type.superInterfaces()) {
                visitOverloads(itf, selector, byKey, seen, false);
            }
        } catch (Throwable ignored) {
            // Hiérarchie incomplète.
        }
    }

    private String keyOf(ReferenceBinding t) {
        try {
            return String.valueOf(t.readableName());
        } catch (Throwable e) {
            return String.valueOf(System.identityHashCode(t));
        }
    }

    // ─── Formatage ─────────────────────────────────────────────────────

    /**
     * Signature affichable : {@code retour nom(T1 p1, T2 p2)} — pour un
     * constructeur, {@code Nom(T1 p1)} (le type de retour est implicite).
     */
    private Signature toSignature(MethodBinding m, List<String> names) {
        StringBuilder sb = new StringBuilder();
        if (!m.isConstructor()) {
            sb.append(EcjCompilerEngine.display(m.returnType)).append(' ');
        }
        sb.append(String.valueOf(m.selector)).append('(');
        for (int i = 0; i < m.parameters.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(EcjCompilerEngine.display(m.parameters[i]));
            if (i < names.size() && !names.get(i).isEmpty()) {
                sb.append(' ').append(names.get(i));
            }
        }
        sb.append(')');
        if (m.declaringClass != null) {
            sb.append(" — ").append(EcjCompilerEngine.readable(m.declaringClass));
        }
        return new Signature(sb.toString(), names, -1);
    }

    /**
     * Noms de paramètres : déclaration source si trouvée (mêmes sources
     * du projet), sinon {@code src.zip}/{@code -sources.jar} pour les
     * méthodes binaires, sinon {@code argN}.
     */
    private List<String> parameterNamesOf(MethodBinding binding) {
        List<String> fromSource = inlayHints.parameterNamesPublic(binding);
        if (fromSource != null) return fromSource;
        // Pour les méthodes BINAIRES (jdk, jars), on essaie le src.zip du
        // JDK home puis les -sources.jar du classpath.
        // Rend les vrais noms (collection, element) au lieu de arg0, arg1…
        // Le résolveur est partagé via engine.binaryNames() — son pool LRU
        // vit pour la durée du moteur, pas du service.
        try {
            List<String> fromBinary = engine.binaryNames().resolveParameterNames(binding);
            if (fromBinary != null) return fromBinary;
        } catch (Throwable ignored) {
            // Best-effort — on retombe sur argN ci-dessous.
        }
        List<String> fallback = new ArrayList<>(binding.parameters.length);
        for (int i = 0; i < binding.parameters.length; i++) {
            fallback.add("arg" + i);
        }
        return fallback;
    }

    /**
     * Index du paramètre actif : compte les virgules de premier niveau
     * entre la parenthèse ouvrante de l'appel et le caret (les chaînes,
     * caractères et niveaux imbriqués sont ignorés).
     */
    static int countActiveParameter(String source, int callStart, int caret) {
        int depth = 0;
        int paramIndex = 0;
        int i = Math.max(0, callStart);
        int n = Math.min(source.length(), caret);
        while (i < n) {
            char c = source.charAt(i);
            switch (c) {
                case '(' -> depth++;
                case ')' -> {
                    if (depth == 1) return paramIndex; // fin de la liste
                    depth = Math.max(0, depth - 1);
                }
                case ',' -> {
                    if (depth == 1) paramIndex++;
                }
                case '"' -> {
                    // Détection des text blocks ("""...""") : une virgule
                    // dans un text block n'est pas un séparateur d'arguments —
                    // on détecte les triple quotes et on saute jusqu'à la
                    // fermeture.
                    if (i + 2 < n && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                        // Text block """..."""  — saute jusqu'au """ fermant.
                        i += 3;
                        while (i < n) {
                            if (source.charAt(i) == '"'
                                    && i + 2 < n
                                    && source.charAt(i + 1) == '"'
                                    && source.charAt(i + 2) == '"') {
                                i += 3;
                                break;
                            }
                            if (source.charAt(i) == '\\') {
                                i += 2;
                                continue;
                            }
                            i++;
                        }
                    } else {
                        // Chaîne — saute jusqu'à la fermeture (échappée).
                        i++;
                        while (i < n) {
                            if (source.charAt(i) == '\\') {
                                i += 2;
                                continue;
                            }
                            if (source.charAt(i) == '"') break;
                            i++;
                        }
                    }
                }
                case '\'' -> {
                    i++;
                    while (i < n) {
                        if (source.charAt(i) == '\\') {
                            i += 2;
                            continue;
                        }
                        if (source.charAt(i) == '\'') break;
                        i++;
                    }
                }
                default -> {
                    // Autres caractères — rien.
                }
            }
            i++;
        }
        return paramIndex;
    }

    // ─── Recherche de l'appel englobant ────────────────────────────────

    /** Trouve le MessageSend / AllocationExpression / ExplicitConstructorCall
     *  le plus interne dont la liste d'arguments contient l'offset (appels de
     *  méthode, constructeurs new Foo() et this()/super()). */
    private static final class CallFinder
            extends org.eclipse.jdt.internal.compiler.ASTVisitor {
        private final String source;
        private final int offset;
        MessageSend call;
        // Champs pour les constructeurs.
        org.eclipse.jdt.internal.compiler.ast.AllocationExpression allocation;
        org.eclipse.jdt.internal.compiler.ast.ExplicitConstructorCall ctorCall;

        CallFinder(String source, int offset) {
            this.source = source;
            this.offset = offset;
        }

        @Override
        public boolean visit(MessageSend ms,
                org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
            if (ms.sourceStart <= offset && offset <= ms.sourceEnd + 1) {
                // Plus interne gagne : on ne remplace pas si déjà trouvé
                // par une visite plus profonde.
                call = ms;
            }
            return true;
        }

        // Visite AllocationExpression (new Foo(...) et new Type[n]).
        @Override
        public boolean visit(org.eclipse.jdt.internal.compiler.ast.AllocationExpression alloc,
                org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
            if (alloc.sourceStart <= offset && offset <= alloc.sourceEnd + 1) {
                allocation = alloc;
            }
            return true;
        }

        // Visite ExplicitConstructorCall (this(...) et super(...)).
        @Override
        public boolean visit(org.eclipse.jdt.internal.compiler.ast.ExplicitConstructorCall ecc,
                org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
            if (ecc.sourceStart <= offset && offset <= ecc.sourceEnd + 1) {
                ctorCall = ecc;
            }
            return true;
        }
    }
}
