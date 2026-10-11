package jo.lspjava.services;

import jo.lspjava.api.Diagnostic;
import jo.lspjava.api.TextRange;
import jo.lspjava.engine.CompilationEnvironment;

import java.util.*;

/**
 * Service d'actions de code (correctifs rapides) pour les diagnostics Java.
 *
 * <p><strong>Édits RÉELS.</strong> Chaque action retourne une liste
 * d'{@link Edit} CONCRÈTES calculées sur le texte courant — suppression
 * de ligne d'import, remplacement d'instruction par son enveloppe
 * try-catch, insertion de {@code throws} dans l'en-tête de méthode, cast
 * du type attendu, insertion d'import candidat depuis l'index de noms de
 * classes… Une action sans édition n'est tout simplement pas proposée
 * (une action muette est pire que pas d'action).</p>
 *
 * <p>Portage des quick-fixes du projet de référence ({@code JavaActions.kt} +
 * {@code JavaCompilerFixes.kt}).</p>
 *
 * <p>Le dispatch reste ID-FIRST ({@code "ecj:<ID>"} →
 * {@link JavaProblemFamily}) avec lecture des arguments STRUCTURÉS
 * ({@code IProblem.getArguments()}) — jamais du message localisé.</p>
 *
 * @author jo@Dev
 */
public final class CodeActionService {

    /**
     * Une édition de texte concrète : remplacer la plage [{@link #start},
     * {@link #end}) par {@link #text} (insertion quand start == end).
     */
    public record Edit(int start, int end, String text) {}

    /**
     * Action de code avec ses éditions RÉELLES — le contrat est
     * qu'une action sans édits n'est tout simplement pas proposée
     * (une action muette est pire que pas d'action).
     */
    public record CodeAction(String title, String kind, List<Edit> edits) {}

    /**
     * Environnement de compilation — accès à l'index de noms de classes
     * pour les candidats d'import (référence non résolue). Peut être null
     * (tests purement textuels) : les fixes index en dépendent ne sont
     * simplement pas proposés.
     */
    private final CompilationEnvironment environment;

    public CodeActionService(CompilationEnvironment environment) {
        this.environment = environment;
    }

    /**
     * Retourne les actions de code disponibles pour un diagnostic.
     *
     * @param diagnostic le diagnostic (code {@code "ecj:<ID>"})
     * @param source     le code source courant du document
     * @return la liste des actions, chacune avec ses édits réelles
     */
    public List<CodeAction> actionsFor(Diagnostic diagnostic, String source) {
        return actionsFor(diagnostic, source, null);
    }

    /**
     * Variante avec le CHEMIN du fichier : le quick-fix
     * CreateMethodFromUsage résout l'AST ecj (cache de parse partagé avec
     * les diagnostics — requête quasi gratuite juste après une passe de
     * diagnose) pour trouver le type/méthode englobants aux positions
     * EXACTES.
     *
     * @param filePath le chemin du fichier (clé du cache de parse), ou null
     */
    public List<CodeAction> actionsFor(Diagnostic diagnostic, String source,
                                        String filePath) {
        List<CodeAction> actions = new ArrayList<>();
        if (diagnostic == null || diagnostic.code() == null || source == null) {
            return actions;
        }

        String code = diagnostic.code();
        List<String> args = diagnostic.arguments() != null
                ? diagnostic.arguments() : List.of();

        if (code.startsWith("ecj:")) {
            JavaProblemFamily family = JavaProblemFamily.fromCode(code);
            actions.addAll(actionsForFamily(family, diagnostic, args, source,
                    filePath));
        }

        // Toute action retenue doit porter au moins une édition non vide.
        actions.removeIf(a -> a.edits() == null || a.edits().isEmpty());
        return actions;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Dispatch par famille sémantique
    // ─────────────────────────────────────────────────────────────────────

    private List<CodeAction> actionsForFamily(JavaProblemFamily family,
                                               Diagnostic diagnostic,
                                               List<String> args,
                                               String source,
                                               String filePath) {
        List<CodeAction> out = new ArrayList<>();
        if (family == JavaProblemFamily.UNKNOWN) return out;
        TextRange range = diagnostic.range();
        String filePackage = filePackage(source);

        switch (family) {
            case UNRESOLVED_REFERENCE -> out.addAll(importFixes(args, source, filePackage));
            case UNDEFINED_METHOD -> out.addAll(createMethodFixes(args, diagnostic,
                    source, filePath));
            case UNUSED_IMPORT -> {
                // Portage RemoveUnusedImportQuickFixProvider (projet de référence) :
                // supprimer la LIGNE d'import entière.
                int[] line = fullLineRange(source, range.startOffset(), range.endOffset());
                String lineText = source.substring(line[0], Math.min(line[1], source.length()));
                if (lineText.trim().startsWith("import ")) {
                    out.add(new CodeAction(
                            "Supprimer l'import inutilisé",
                            "quickfix.remove.import",
                            List.of(new Edit(line[0], line[1], ""))));
                }
            }
            case UNUSED_DECLARATION -> out.addAll(removeDeclarationFixes(source, range));
            case UNCAUGHT_EXCEPTION -> out.addAll(exceptionFixes(args, source, range, filePackage));
            case TYPE_MISMATCH -> out.addAll(castFix(args, range));
            case MISSING_SEMI -> {
                // Insérer « ; » en FIN de ligne du token signalé (avant, le
                // point-virgule atterrissait au DÉBUT de la plage — « ;foo() »).
                int insertAt = lineEnd(source, range.endOffset());
                out.add(new CodeAction(
                        "Ajouter un point-virgule",
                        "quickfix.semicolon",
                        List.of(new Edit(insertAt, insertAt, ";"))));
            }
            default -> { /* famille non couverte */ }
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────
    // UNRESOLVED_REFERENCE → « Importer X (fqn) »
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Portage du {@code AddImportQuickFixProvider} du projet de référence : sur un
     * symbole non résolu dont le texte ressemble à un NOM DE TYPE
     * (initiale majuscule — projet de référence écarte explicitement les variables
     * et méthodes non résolues), proposer « Importer X » pour chaque FQN
     * de l'index de noms de classes dont le nom simple correspond
     * exactement — même édition d'auto-import que la complétion, mais
     * atteignable depuis l'ampoule sur un nom déjà tapé.
     */
    private List<CodeAction> importFixes(List<String> args, String source, String filePackage) {
        List<CodeAction> out = new ArrayList<>();
        if (args.isEmpty() || environment == null) return out;
        String name = args.get(0);
        if (name == null || name.isEmpty()) return out;
        // Un plausible nom de type uniquement (règle projet de référence).
        char c0 = name.charAt(0);
        if (!Character.isJavaIdentifierStart(c0) || !Character.isUpperCase(c0)) return out;
        for (char c : name.toCharArray()) {
            if (!Character.isJavaIdentifierPart(c)) return out;
        }

        List<String> candidates;
        try {
            candidates = environment.jdt().fqnsForSimpleTypeName(name);
        } catch (Throwable t) {
            return out;
        }
        int offered = 0;
        for (String fqn : candidates) {
            if (offered >= 8) break; // projet de référence : take(8)
            int dot = fqn.lastIndexOf('.');
            if (dot <= 0) continue;                       // pas de paquet
            String pkg = fqn.substring(0, dot);
            if ("java.lang".equals(pkg) || pkg.equals(filePackage)) continue;
            if (!needsImport(source, fqn, filePackage)) continue; // déjà importé / on-demand
            String importText = "import " + fqn + ";\n";
            int at = importInsertOffset(source);
            out.add(new CodeAction(
                    "Importer " + name + " (" + fqn + ")",
                    "quickfix.import.fqn",
                    List.of(new Edit(at, at, importText))));
            offered++;
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────
    // UNUSED_DECLARATION → suppression de la déclaration
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Portage {@code RemoveUnusedLocalQuickFixProvider} /
     * {@code RemoveUnusedMemberQuickFixProvider} du projet de référence : supprimer
     * l'instruction de déclaration ENTIÈRE. Comme projet de référence écarte les
     * groupes multi-fragments ({@code int a, b;} — risque de variables
     * sœurs) et les paramètres de méthode, on fait de même par heuristique
     * textuelle : déclaration multi-fragments (virgule de premier niveau
     * avant le {@code =}) ou plage à l'intérieur d'une liste de paramètres
     * (précédée de {@code (} ou {@code ,}) → PAS de fix.
     */
    private List<CodeAction> removeDeclarationFixes(String source, TextRange range) {
        List<CodeAction> out = new ArrayList<>();
        int[] stmt = statementRange(source, range.startOffset(), range.endOffset());
        if (stmt[1] <= stmt[0]) return out;
        String stmtText = source.substring(stmt[0], stmt[1]).trim();
        if (stmtText.isEmpty()) return out;

        // Paramètre de méthode / argument d'appel : la plage est à
        // l'intérieur d'une liste de parenthèses → abandon (projet de référence
        // laisse les SingleVariableDeclaration tranquilles).
        if (insideParenList(source, range.startOffset())) return out;

        // Déclaration multi-fragments ({@code int a, b;} ou
        // {@code int a = 1, b = 2;}) : virgule de premier niveau → abandon
        // (projet de référence : « sibling-var risk »).
        if (indexOfTopLevel(stmtText, ',') >= 0) return out;

        // Étendre à la ligne complète (-indentation incluse) : de stmt[0]
        // au retour à la ligne après stmt[1].
        int[] line = fullLineRange(source, stmt[0], stmt[1]);
        String label = stmtText.length() > 40
                ? stmtText.substring(0, 37) + "…" : stmtText;
        out.add(new CodeAction(
                "Supprimer la déclaration inutilisée « " + label + " »",
                "quickfix.remove.unused",
                List.of(new Edit(line[0], line[1], ""))));
        return out;
    }

    /**
     * La position est-elle à l'intérieur d'une liste de parenthèses ?
     * Remonte en comptant les parenthèses : une {@code (} ouvrante
     * rencontrée à profondeur 0 AVANT tout délimiteur d'instruction
     * ({@code ; { }}) → oui (liste de paramètres, argument d'appel…).
     */
    static boolean insideParenList(String text, int from) {
        int depth = 0;
        int i = clamp(from, 0, text.length()) - 1;
        while (i >= 0) {
            char c = text.charAt(i);
            if (c == ')') depth++;
            else if (c == '(') {
                if (depth == 0) return true;
                depth--;
            } else if ((c == ';' || c == '{' || c == '}') && depth == 0) {
                return false;
            }
            i--;
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────
    // UNDEFINED_METHOD → « Créer la méthode » (portage CreateMethodFromUsage)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Portage du {@code CreateMethodFromUsageQuickFixProvider} du projet de référence :
     * sur un appel non résolu qui cible CE type ({@code foo(args)} /
     * {@code this.foo(args)}), créer le stub de méthode dans le type
     * englobant.
     *
     * <ul>
     *   <li><b>Paramètres</b> — types des arguments depuis les arguments
     *       STRUCTURÉS ecj ({@code [ownerFqn, name, typeP0, typeP1, …]}),
     *       raccourcis en noms simples avec imports collectés (ou qualifiés
     *       en cas de collision) ;</li>
     *   <li><b>Retour</b> — {@code void} quand l'appel EST l'instruction
     *       (ExpressionStatement chez le projet de référence), sinon {@code Object} ;</li>
     *   <li><b>Visibilité</b> — {@code private}, plus {@code static} quand
     *       le site d'appel est statique ;</li>
     *   <li><b>Corps</b> — {@code throw new UnsupportedOperationException(
     *       "TODO: …")} : compile quel que soit le type de retour ;</li>
     *   <li><b>Position</b> — APRÈS la méthode englobante (positions AST
     *       exactes : {@code declarationSourceEnd + 1}).</li>
     * </ul>
     */
    private List<CodeAction> createMethodFixes(List<String> args,
                                                Diagnostic diagnostic,
                                                String source, String filePath) {
        List<CodeAction> out = new ArrayList<>();
        if (args.size() < 2) return out;
        String ownerFqn = args.get(0);
        String name = args.get(1);
        if (ownerFqn == null || name == null || name.isEmpty()) return out;
        String ownerSimple = simpleName(ownerFqn.trim());

        // Types des paramètres : ecj 3.40 les sépare en args[2..] ; certaines
        // versions les regroupent en UNE chaîne « int, java.lang.String »
        // (projet de référence splitTopLevel) — les deux formes sont acceptées.
        List<String> paramTypes = new ArrayList<>();
        if (args.size() == 3) {
            paramTypes.addAll(splitTopLevel(args.get(2)));
        } else {
            for (int i = 2; i < args.size(); i++) {
                paramTypes.addAll(splitTopLevel(args.get(i)));
            }
        }
        paramTypes.removeIf(String::isBlank);

        // Type/méthode englobants — positions AST exactes.
        EnclosingDecl encl = enclosingDecl(source, filePath,
                diagnostic.range().startOffset());
        if (encl == null || encl.typeName == null) return out;
        // Créer uniquement dans le type COURANT (projet de référence : « creating in
        // another file is a separate, larger fix »).
        if (!ownerSimple.equals(encl.typeName)) return out;
        // Le site d'appel doit vivre dans une MÉTHODE (un initialiseur de
        // champ n'a pas de méthode englobante où prendre static/indent).
        if (encl.methodEnd < 0) return out;

        // void si l'appel EST l'instruction entière (portage
        // callInvocation().parent is ExpressionStatement), sinon Object.
        int[] stmt = statementRange(source, diagnostic.range().startOffset(),
                diagnostic.range().endOffset());
        String stmtText = stmt[1] > stmt[0]
                ? source.substring(stmt[0], stmt[1]).trim() : "";
        boolean returnsVoid = stmtText.startsWith(name + "(");
        String returnType = returnsVoid ? "void" : "Object";

        String indent = lineIndent(source, Math.max(0, encl.methodStart));
        String modifiers = encl.methodStatic ? "private static" : "private";

        // Paramètres : types raccourcis + imports nécessaires (projet de référence
        // renderTypeName : simple + import, FQN en cas de collision).
        String filePackage = filePackage(source);
        java.util.LinkedHashSet<String> imports = new java.util.LinkedHashSet<>();
        StringBuilder params = new StringBuilder();
        for (int i = 0; i < paramTypes.size(); i++) {
            if (i > 0) params.append(", ");
            params.append(renderTypeName(paramTypes.get(i), source,
                    filePackage, imports)).append(" arg").append(i);
        }

        int insertAt = clamp(encl.methodEnd + 1, 0, source.length());
        String method = "\n\n" + indent + modifiers + " " + returnType + " "
                + name + "(" + params + ") {\n"
                + indent + "    throw new UnsupportedOperationException("
                + "\"TODO: " + name + "\");\n"
                + indent + "}";
        List<Edit> edits = new ArrayList<>();
        edits.add(new Edit(insertAt, insertAt, method));
        if (!imports.isEmpty()) {
            int at = importInsertOffset(source);
            StringBuilder sb = new StringBuilder();
            for (String fqn : imports) sb.append("import ").append(fqn).append(";\n");
            edits.add(new Edit(at, at, sb.toString()));
        }

        StringBuilder sig = new StringBuilder(name).append("(");
        for (int i = 0; i < paramTypes.size(); i++) {
            if (i > 0) sig.append(", ");
            sig.append(simpleName(paramTypes.get(i).trim()));
        }
        sig.append(")");
        out.add(new CodeAction(
                "Créer la méthode « " + sig + " »",
                "quickfix.create.method", edits));
        return out;
    }

    /** Type + méthode englobant un offset (les plus INTERNES). */
    private static final class EnclosingDecl {
        final String typeName;
        final int methodStart;   // declarationSourceStart de la méthode
        final int methodEnd;     // declarationSourceEnd (offset du '}') ; -1 si aucun
        final boolean methodStatic;

        EnclosingDecl(String typeName, int methodStart, int methodEnd,
                boolean methodStatic) {
            this.typeName = typeName;
            this.methodStart = methodStart;
            this.methodEnd = methodEnd;
            this.methodStatic = methodStatic;
        }
    }

    /**
     * Résout le type et la méthode englobant {@code offset} via l'AST ecj
     * (visiteur : le plus INTERNE gagne). Le type/méthode couvre
     * [declarationSourceStart, declarationSourceEnd] — bornes calibrées
     * empiriquement (declarationSourceEnd = offset du dernier caractère,
     * la {@code }} pour un corps concret).
     */
    private EnclosingDecl enclosingDecl(String source, String filePath, int offset) {
        if (environment == null || filePath == null) return null;
        try {
            var cud = environment.jdt().resolveUnit(filePath, source, false);
            if (cud == null || cud.scope == null) return null;
            final int at = clamp(offset, 0, Math.max(0, source.length() - 1));
            final org.eclipse.jdt.internal.compiler.ast.TypeDeclaration[] bestType =
                    new org.eclipse.jdt.internal.compiler.ast.TypeDeclaration[1];
            final org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration[] bestMethod =
                    new org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration[1];
            jo.lspjava.jdt.JdtAst.traverseResilient(cud,
                    new org.eclipse.jdt.internal.compiler.ASTVisitor() {
                        private boolean covers(int start, int end) {
                            return start >= 0 && end >= start
                                    && at >= start && at <= end;
                        }

                        @Override
                        public boolean visit(
                                org.eclipse.jdt.internal.compiler.ast.TypeDeclaration td,
                                org.eclipse.jdt.internal.compiler.lookup.CompilationUnitScope s) {
                            visitType(td);
                            return true;
                        }

                        @Override
                        public boolean visit(
                                org.eclipse.jdt.internal.compiler.ast.TypeDeclaration td,
                                org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
                            visitType(td);
                            return true;
                        }

                        @Override
                        public boolean visit(
                                org.eclipse.jdt.internal.compiler.ast.TypeDeclaration td,
                                org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
                            visitType(td);
                            return true;
                        }

                        private void visitType(
                                org.eclipse.jdt.internal.compiler.ast.TypeDeclaration td) {
                            if (covers(td.declarationSourceStart,
                                    td.declarationSourceEnd)) {
                                bestType[0] = td;
                            }
                        }

                        @Override
                        public boolean visit(
                                org.eclipse.jdt.internal.compiler.ast.MethodDeclaration md,
                                org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
                            if (covers(md.declarationSourceStart,
                                    md.declarationSourceEnd)) {
                                bestMethod[0] = md;
                            }
                            return true;
                        }
                        @Override
                        public boolean visit(
                                org.eclipse.jdt.internal.compiler.ast.ConstructorDeclaration cd,
                                org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
                            if (covers(cd.declarationSourceStart,
                                    cd.declarationSourceEnd)) {
                                bestMethod[0] = cd;
                            }
                            return true;
                        }
                    });
            if (bestType[0] == null) return null;
            org.eclipse.jdt.internal.compiler.ast.TypeDeclaration td = bestType[0];
            boolean isStatic = bestMethod[0] != null
                    && (bestMethod[0].modifiers
                            & org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants.AccStatic) != 0;
            return new EnclosingDecl(
                    String.valueOf(td.name),
                    bestMethod[0] != null ? bestMethod[0].declarationSourceStart : -1,
                    bestMethod[0] != null ? bestMethod[0].declarationSourceEnd : -1,
                    isStatic);
        } catch (Throwable t) {
            return null; // moteur indisponible / source imparsable
        }
    }

    /**
     * Raccourcit les FQN d'une signature de type : nom simple + import
     * collecté (ou FQN conservé en cas de collision d'import — portage
     * {@code shortenType} du projet de référence).
     */
    private static String renderTypeName(String type, String source,
                                          String filePackage,
                                          java.util.Set<String> imports) {
        java.util.regex.Matcher m = FQN_PATTERN.matcher(type);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String fqn = m.group();
            String simple = m.group(2);
            String pkg = fqn.substring(0, fqn.length() - simple.length() - 1);
            String replacement;
            if ("java.lang".equals(pkg) || pkg.equals(filePackage)) {
                replacement = simple;
            } else if (collidesWithImport(source, simple, fqn)) {
                replacement = fqn; // collision → usage qualifié
            } else {
                if (needsImport(source, fqn, filePackage)) {
                    imports.add(fqn);
                }
                replacement = simple;
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Un import EXISTANT porte déjà ce nom simple pour un AUTRE FQN ? */
    private static boolean collidesWithImport(String source, String simple,
                                               String fqn) {
        for (String line : source.split("\n", -1)) {
            String t = line.trim();
            if (!t.startsWith("import ")) continue;
            if (t.endsWith(";")) t = t.substring(0, t.length() - 1);
            String target = t.substring(7).trim();
            if (target.endsWith(".*")) continue; // on-demand : pas une collision
            if (target.equals(fqn)) continue;    // déjà le bon import
            if (simpleName(target).equals(simple)) return true;
        }
        return false;
    }

    /** Découpe une liste de types au niveau 0 (virgules de « <>() » ignorées). */
    static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isBlank()) return out;
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<', '(', '[' -> { depth++; sb.append(c); }
                case '>', ')', ']' -> { depth--; sb.append(c); }
                case ',' -> {
                    if (depth == 0) { out.add(sb.toString().trim()); sb.setLength(0); }
                    else sb.append(c);
                }
                default -> sb.append(c);
            }
        }
        if (!sb.toString().isBlank()) out.add(sb.toString().trim());
        return out;
    }

    private static final java.util.regex.Pattern FQN_PATTERN =
            java.util.regex.Pattern.compile(
                    "([A-Za-z_$][A-Za-z0-9_$]*\\.)+([A-Za-z_$][A-Za-z0-9_$]*)");

    // ─────────────────────────────────────────────────────────────────────
    // UNCAUGHT_EXCEPTION → try-catch / throws
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Portage {@code SurroundWithTryCatchForExceptionQuickFixProvider} et
     * {@code AddExceptionToThrowsQuickFixProvider} du projet de référence :
     * l'argument STRUCTURÉ du problème ({@code getArguments()[0]} = FQN de
     * l'exception vérifiée) fournit le type exact du catch/throws — jamais
     * le message localisé.
     */
    private List<CodeAction> exceptionFixes(List<String> args, String source,
                                             TextRange range, String filePackage) {
        List<CodeAction> out = new ArrayList<>();
        String excFqn = !args.isEmpty() ? args.get(0) : null;
        if (excFqn == null || excFqn.isBlank()) excFqn = "java.lang.Exception";
        String simple = simpleName(excFqn);

        // ── 1. Entourer l'instruction avec try-catch (type EXACT) ──
        int[] stmt = statementRange(source, range.startOffset(), range.endOffset());
        if (stmt[1] > stmt[0]) {
            String stmtText = source.substring(stmt[0], stmt[1]);
            String indent = lineIndent(source, stmt[0]);
            String inner = indent + "    ";
            String wrapped = "try {\n"
                    + inner + stmtText.replace("\n", "\n    ") + "\n"
                    + indent + "} catch (" + simple + " e) {\n"
                    + inner + "e.printStackTrace();\n"
                    + indent + "}";
            List<Edit> edits = new ArrayList<>();
            edits.add(new Edit(stmt[0], stmt[1], wrapped));
            addImportEditIfNeeded(edits, source, excFqn, filePackage);
            out.add(new CodeAction(
                    "Entourer avec try-catch (" + simple + ")",
                    "quickfix.surround.trycatch",
                    edits));
        }

        // ── 2. Déclarer throws dans la méthode englobante ──
        int[] throwsIns = throwsInsertPosition(source, range.startOffset());
        if (throwsIns != null) {
            List<Edit> edits = new ArrayList<>();
            String insert = throwsIns[1] == 1
                    ? ", " + simple              // throws existant → ajouter
                    : " throws " + simple;       // créer la clause
            edits.add(new Edit(throwsIns[0], throwsIns[0], insert));
            addImportEditIfNeeded(edits, source, excFqn, filePackage);
            out.add(new CodeAction(
                    "Déclarer throws " + simple,
                    "quickfix.declare.throws",
                    edits));
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────
    // TYPE_MISMATCH → cast
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Cast vers le type ATTENDU : le message ecj est
     * « cannot convert from {0} to {1} » → {@code args[0]} = type réel,
     * {@code args[1]} = type attendu → on caste vers args[1]. L'édition
     * insère « (Attendu) » DEVANT l'expression signalée (la plage du
     * problème couvre l'expression à caster).
     */
    private List<CodeAction> castFix(List<String> args, TextRange range) {
        List<CodeAction> out = new ArrayList<>();
        if (args.isEmpty()) return out;
        String target = args.size() >= 2 ? args.get(1) : args.get(0);
        if (target == null || target.isBlank()) return out;
        String simple = simpleName(target);
        int at = Math.max(0, range.startOffset());
        out.add(new CodeAction(
                "Insérer un cast vers " + simple,
                "quickfix.cast",
                List.of(new Edit(at, at, "(" + simple + ") "))));
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Aides import
    // ─────────────────────────────────────────────────────────────────────

    /** Ajoute l'édit d'insertion d'import si le FQN en a vraiment besoin. */
    private static void addImportEditIfNeeded(List<Edit> edits, String source,
                                               String fqn, String filePackage) {
        if (!needsImport(source, fqn, filePackage)) return;
        int at = importInsertOffset(source);
        edits.add(new Edit(at, at, "import " + fqn + ";\n"));
    }

    /**
     * Le FQN requiert-t-il un import ? Non si : pas de paquet, paquet
     * {@code java.lang}, paquet du fichier courant, déjà importé
     * explicitement, ou couvert par un import on-demand de son paquet.
     */
    static boolean needsImport(String source, String fqn, String filePackage) {
        int dot = fqn.lastIndexOf('.');
        if (dot <= 0) return false;
        String pkg = fqn.substring(0, dot);
        if ("java.lang".equals(pkg) || pkg.equals(filePackage)) return false;
        for (String line : source.split("\n", -1)) {
            String t = line.trim();
            if (!t.startsWith("import ")) continue;
            if (t.endsWith(";")) t = t.substring(0, t.length() - 1);
            String target = t.substring(7).trim();
            if (target.equals(fqn)) return false;          // déjà importé
            if (target.equals(pkg + ".*")) return false;   // on-demand
        }
        return true;
    }

    /**
     * Offset d'insertion d'un nouvel import — après le DERNIER import
     * existant, sinon après la déclaration {@code package}, sinon début
     * de fichier. (Sémantique identique à
     * {@code JavaTextDocumentService.computeImportInsertionOffset} —
     * celle-ci y délègue désormais.)
     */
    public static int importInsertOffset(String source) {
        int lastImportEnd = -1;
        int packageEnd = -1;
        int lineStart = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\n' || i == source.length() - 1) {
                int lineEnd = c == '\n' ? i : i + 1;
                String line = source.substring(lineStart, lineEnd).trim();
                if (line.startsWith("package ") && line.endsWith(";")) {
                    packageEnd = lineEnd;
                } else if (line.startsWith("import ") && line.endsWith(";")) {
                    lastImportEnd = lineEnd;
                } else if (!line.isEmpty() && !line.startsWith("//") && !line.startsWith("/*")
                        && !line.startsWith("*") && packageEnd >= 0) {
                    break;
                }
                lineStart = i + 1;
            }
        }
        return lastImportEnd >= 0 ? lastImportEnd
                : (packageEnd >= 0 ? packageEnd : 0);
    }

    /** Le paquet déclaré du fichier (« package a.b; »), ou "". */
    static String filePackage(String source) {
        for (String line : source.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("package ") && t.endsWith(";")) {
                return t.substring(8, t.length() - 1).trim();
            }
            if (!t.isEmpty() && !t.startsWith("//") && !t.startsWith("/*")) break;
        }
        return "";
    }

    // ─────────────────────────────────────────────────────────────────────
    // Aides plages textuelles (portages projet de référence fullLineRange /
    // lineIndent / enclosingStatement)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Étend [{@code start}, {@code end}) aux lignes COMPLÈTES : recule au
     * début de ligne, avance jusqu'au retour à la ligne INCLUS (portage
     * {@code fullLineRange} du projet de référence).
     */
    static int[] fullLineRange(String text, int start, int end) {
        int s = clamp(start, 0, text.length());
        int e = clamp(end, s, text.length());
        while (s > 0 && text.charAt(s - 1) != '\n') s--;
        while (e < text.length() && text.charAt(e) != '\n') e++;
        if (e < text.length()) e++; // avale le retour à la ligne
        return new int[]{s, e};
    }

    /** L'indentation (espaces/tabulations) de la ligne contenant offset. */
    static String lineIndent(String text, int offset) {
        int start = clamp(offset, 0, text.length());
        while (start > 0 && text.charAt(start - 1) != '\n') start--;
        StringBuilder sb = new StringBuilder();
        int i = start;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            sb.append(text.charAt(i));
            i++;
        }
        return sb.toString();
    }

    /** Fin de la ligne contenant offset (l'index du '\n', ou la fin). */
    static int lineEnd(String text, int offset) {
        int e = clamp(offset, 0, text.length());
        while (e < text.length() && text.charAt(e) != '\n') e++;
        return e;
    }

    /**
     * L'instruction englobante de la plage : recule au début de ligne ou
     * après le délimiteur précédent ({@code ; { }}), avance jusqu'au
     * {@code ;} de premier niveau (ou la fermeture de bloc) — version
     * textuelle du {@code enclosingStatement} DOM du projet de référence.
     */
    static int[] statementRange(String text, int rangeStart, int rangeEnd) {
        int n = text.length();
        int s = clamp(rangeStart, 0, n);
        while (s > 0) {
            char c = text.charAt(s - 1);
            if (c == '\n' || c == ';' || c == '{' || c == '}') break;
            s--;
        }
        while (s < n && (text.charAt(s) == ' ' || text.charAt(s) == '\t')) s++;

        int e = clamp(Math.max(rangeEnd, s), 0, n);
        int depth = 0;
        int guard = e + 5000; // garde contre les fichiers pathologiques
        while (e < n && e < guard) {
            char c = text.charAt(e);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) break; // l'instruction se ferme sans ';'
                depth--;
            } else if (c == ';' && depth == 0) {
                e++; // le ';' appartient à l'instruction
                break;
            }
            e++;
        }
        return new int[]{s, e};
    }

    /**
     * Position d'insertion d'un {@code throws} dans l'en-tête de la
     * MÉTHODE englobant {@code fromOffset} : remonte les accolades en
     * ignorant les blocs de contrôle (if/for/while/catch…), lit l'en-tête
     * de méthode, et retourne soit juste après la {@code )} de la liste de
     * paramètres (mode 0 : insérer « throws X »), soit à la fin de la
     * clause throws existante (mode 1 : insérer « , X »).
     *
     * @return {position, mode} ou null si aucune méthode englobante trouvée
     */
    static int[] throwsInsertPosition(String text, int fromOffset) {
        int i = clamp(fromOffset, 0, text.length()) - 1;
        int depth = 0;
        while (i >= 0) {
            char c = text.charAt(i);
            if (c == '}') {
                depth++;
                i--;
                continue;
            }
            if (c == '{') {
                if (depth > 0) {
                    depth--;
                    i--;
                    continue;
                }
                // '{' ouvrant le bloc IMMÉDIATEMENT englobant : lire l'en-tête.
                int headerEnd = i;
                int j = i - 1;
                while (j >= 0) {
                    char h = text.charAt(j);
                    if (h == ';' || h == '}' || h == '{') break;
                    j--;
                }
                // NON-TRIMMÉ : les positions se calculent sur le texte
                // brut de l'en-tête — le trim décalerait absClose d'autant
                // (édit corrompu du type « voi throws Xd m() »).
                String rawHeader = text.substring(j + 1, headerEnd);
                if (isMethodHeader(rawHeader.trim())) {
                    return throwsPositionInHeader(rawHeader, j + 1);
                }
                // Bloc de contrôle (if/for/try/lambda…) : CONTINUER au
                // délimiteur précédent — il sera retraité par la boucle
                // (si c'est le '{' de la méthode, il deviendra ouvrant).
                i = j;
                continue;
            }
            i--;
        }
        return null;
    }

    /**
     * Un en-tête ressemble-t-il à une déclaration de MÉTHODE/CONSTRUCTEUR ?
     * Écarte les blocs de contrôle (if/for/while/switch/catch/else/try…),
     * les lambdas ({@code () ->}) et les expressions {@code new}.
     */
    private static boolean isMethodHeader(String header) {
        int open = header.indexOf('(');
        int close = header.lastIndexOf(')');
        if (open <= 0 || close < open) return false;
        String before = header.substring(0, open).trim();
        if (before.endsWith("->")) return false; // lambda
        String[] words = before.split("\\s+");
        String last = words[words.length - 1];
        if (last.isEmpty()) return false;
        switch (words[0]) {
            case "if": case "for": case "while": case "switch": case "catch":
            case "synchronized": case "do": case "else": case "try":
            case "return": case "throw": case "new": case "assert":
                return false;
        }
        String nm = last;
        int lt = nm.indexOf('<');
        if (lt >= 0) nm = nm.substring(0, lt);
        if (nm.isEmpty()) return false;
        for (char c : nm.toCharArray()) {
            if (!Character.isJavaIdentifierPart(c)) return false;
        }
        return true;
    }

    /**
     * Position d'insertion du throws dans l'en-tête NON-TRIMMÉ
     * [headerStart, headerStart + rawHeader.length()) du texte : après la
     * {@code )} des paramètres (mode 0) ou à la fin de la clause throws
     * existante (mode 1).
     */
    private static int[] throwsPositionInHeader(String rawHeader, int headerStart) {
        int close = rawHeader.lastIndexOf(')');
        int absClose = headerStart + close + 1; // juste après la ')'
        String after = rawHeader.substring(close + 1);
        int t = after.indexOf("throws");
        boolean hasThrows = t >= 0 && isWordAt(after, t, "throws");
        if (hasThrows) {
            // Fin de la clause throws = dernier caractère non-blanc de
            // l'en-tête (juste avant le '{' ouvrant le corps).
            int end = rawHeader.length() - 1;
            while (end >= 0 && Character.isWhitespace(rawHeader.charAt(end))) {
                end--;
            }
            return new int[]{headerStart + end + 1, 1};
        }
        return new int[]{absClose, 0};
    }

    /** « throws » à l'offset t est-il un MOT (frontières non-identifiants) ? */
    private static boolean isWordAt(String s, int t, String word) {
        if (t > 0 && Character.isJavaIdentifierPart(s.charAt(t - 1))) return false;
        int after = t + word.length();
        if (after < s.length() && Character.isJavaIdentifierPart(s.charAt(after))) return false;
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Petites aides
    // ─────────────────────────────────────────────────────────────────────

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    private static int indexOfTopLevel(String s, char target) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == target && depth == 0) return i;
        }
        return -1;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
