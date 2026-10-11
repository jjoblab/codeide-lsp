package jo.lspjava.ecj;

import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.Assignment;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.FieldReference;
import org.eclipse.jdt.internal.compiler.ast.MessageSend;
import org.eclipse.jdt.internal.compiler.ast.QualifiedNameReference;
import org.eclipse.jdt.internal.compiler.ast.QualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.SingleNameReference;
import org.eclipse.jdt.internal.compiler.ast.SingleTypeReference;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.lookup.Binding;
import org.eclipse.jdt.internal.compiler.lookup.FieldBinding;
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding;
import org.eclipse.jdt.internal.compiler.lookup.Scope;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jo.lspjava.api.CompletionItem;

/**
 * Moteur de complétion basé sur le compilateur interne ecj — le portage Java
 * du pipeline {@code JdtCompletion} du projet de référence (ContextAnalyzer +
 * CandidateCollector), sans l'index de workspace.
 *
 * <p>Pipeline : un <strong>marqueur</strong> (identifiant factice) est
 * inséré au caret dans le texte — plusieurs formes sont essayées (verbatim,
 * terminée par {@code ;}, forme d'appel {@code marker()} après un point)
 * car la récupération d'instructions d'ecj se comporte différemment selon la
 * position. L'unité splicée est résolue avec les bindings, puis une
 * traversée unique localise le marqueur et dérive le contexte :</p>
 * <ul>
 *   <li><b>MEMBER_ACCESS</b> — le marqueur est le dernier segment d'un nom
 *       qualifié / d'un accès de champ / le sélecteur d'un appel : membres
 *       accessibles du type du receveur (hérités compris, filtrés par
 *       visibilité et préfixe).</li>
 *   <li><b>NAME_REFERENCE</b> — le marqueur est un nom seul : variables
 *       locales/paramètres en portée + membres du type englobant.</li>
 * </ul>
 *
 * <p>Les candidats sont déduits par préfixe et triés par pertinence. Sans
 * index, les types non importés ne sont PAS proposés (l'appelant retombe
 * sur le moteur JavaParser pour ces cas).</p>
 *
 * @author jo@Dev
 */
public final class EcjCompletionEngine {

    /** Marqueur inséré au caret — identifiant valide, collision improbable. */
    private static final String MARKER = "__codeide_completion__";

    /** Mots-clés Java proposés en contexte de nom. */
    private static final List<String> KEYWORDS = List.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "const", "continue", "default", "do", "double",
            "else", "enum", "extends", "final", "finally", "float", "for",
            "if", "implements", "import", "instanceof", "int", "interface",
            "long", "native", "new", "package", "private", "protected",
            "public", "return", "short", "static", "strictfp", "super",
            "switch", "synchronized", "this", "throw", "throws", "transient",
            "try", "void", "volatile", "while", "true", "false", "null",
            "var", "record", "sealed", "permits", "yield");

    /** Types usuels du JDK proposés sans préfixe (le jrt couvre le reste). */
    private static final List<String> COMMON_TYPES = List.of(
            "String", "Object", "Integer", "Long", "Double", "Float", "Boolean",
            "Character", "Byte", "Short", "List", "ArrayList", "Map", "HashMap",
            "Set", "HashSet", "Optional", "Arrays", "Collections", "Exception",
            "RuntimeException", "StringBuilder", "Thread", "Runnable");

    private final EcjCompilerEngine engine;

    public EcjCompletionEngine(EcjCompilerEngine engine) {
        this.engine = engine;
    }

    /**
     * Signal d'arrêt anticipé de la traversée AST — déroulé immédiat dès
     * que le marqueur est capturé (portage du pattern {@code MarkerFound}
     * de {@code ContextAnalyzer.kt}, projet de référence v3.20). La traversée ecj
     * n'a pas de mécanisme d'arrêt ({@code visit} retourne false = ne
     * descend pas dans les ENFANTS, mais continue les frères) : tout ce
     * qui suit le marqueur dans l'ordre de traversée est POSTÉRIEUR au
     * caret — le contexte (piles de types/attentes, locales) est déjà
     * intégralement collecté pendant la descente, la suite est du calcul
     * gaspillé (sur un fichier de 3 000 lignes avec le caret à la ligne 50,
     * ~98 % de la traversée est évitée). Zéro coût de stack trace :
     * {@code writableStackTrace=false} + {@link #fillInStackTrace()} no-op.
     */
    private static final class MarkerFound extends RuntimeException {
        MarkerFound() {
            super(null, null, false, false);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    /**
     * Complétion au offset. Retourne null si le contexte n'a pas pu être
     * résolu (l'appelant retombe sur le moteur JavaParser) ; une liste
     * (possiblement vide) sinon.
     */
    public List<CompletionItem> complete(String filePath, String source, int offset) {
        return complete(filePath, source, offset, null);
    }

    /**
     * Surcharge instrumentée — {@code out[0]} reçoit une trace
     * détaillée par splice (ctx, nb items, exceptions absorbées). Utilisée
     * par les tests de repro et activable côté app pour les logs device.
     */
    public List<CompletionItem> complete(String filePath, String source,
                                         int offset, String[] out) {
        StringBuilder trace = out != null ? new StringBuilder() : null;
        List<CompletionItem> result = completeImpl(filePath, source, offset, trace);
        if (out != null && trace != null) out[0] = trace.toString();
        return result;
    }

    private List<CompletionItem> completeImpl(String filePath, String source,
                                              int offset, StringBuilder trace) {
        // CONTRAT (lot F, étape 3) : complete() ne rend JAMAIS null —
        // un contexte non capturé (nom de déclaration, annotation en
        // recovery…) rend une liste VIDE honnête. L'ancien null était
        // avalé en NPE par les appelants et se manifestait comme le
        // « 0 item » du banc (étude §4.3 — symptôme « got 0 items »).
        if (EcjCompilerEngine.DISABLED) return new ArrayList<>();

        int caret = Math.max(0, Math.min(offset, source.length()));

        // Réduction du texte AVANT la construction des variantes de splice
        // (technique de java-language-server). SourceFocuser efface le
        // corps de toute méthode qui ne contient pas l'offset du curseur,
        // en gardant les signatures intactes (résolution de surcharges /
        // héritage correcte). Les 2 à 4 tentatives de resolveUnit portent
        // alors sur un texte réduit, donc bien moins coûteuses chacune.
        //
        // SourceFocuser traduit l'offset curseur dans la source réduite
        // (les corps précédant la méthode focalisée sont raccourcis, ce qui
        // décale les positions).
        SourceFocuser.Focused focused = SourceFocuser.focusAndMap(source, caret);
        String workSource = focused.source();
        int workCaret = focused.offset();

        String prefix = identifierPrefix(workSource, workCaret);
        int replaceStart = workCaret - prefix.length();
        // On consomme AUSSI la fin de l'identifiant APRÈS le curseur :
        // compléter au milieu d'un mot (« activity_m|ain ») collerait le
        // suffixe résiduel (« ain ») au marqueur → l'entrée ecj deviendrait
        // un UNIQUE identifiant fusionné dont aucun segment n'égalerait
        // MARKER → analyzeContext ne trouverait jamais le contexte → 0
        // candidat systématique (le cas le plus courant en frappe live).
        int replaceEnd = workCaret;
        while (replaceEnd < workSource.length()
                && Character.isJavaIdentifierPart(workSource.charAt(replaceEnd))) {
            replaceEnd++;
        }
        boolean afterDot = precededByDot(workSource, replaceStart);

        // Portage des spliceVariants du projet de référence : la 5e
        // forme « balancedTail » ABANDONNE le texte après le caret et
        // synthétise une queue équilibrée en parenthèses terminée par «;».
        // Elle sauve les instructions NON TERMINÉES autour d'une lambda
        // (…map(x -> 1).collect(Colle|) sans «;» fermant) : ecj jette la
        // déclaration entière en recovery, marqueur compris — le marqueur
        // doit atterrir dans une syntaxe DÉJÀ valide.
        List<String> splices = new ArrayList<>();
        splices.add(workSource.substring(0, replaceStart) + MARKER + workSource.substring(replaceEnd));
        splices.add(workSource.substring(0, replaceStart) + MARKER + ";" + workSource.substring(replaceEnd));
        if (afterDot) {
            String token = MARKER + "()";
            splices.add(workSource.substring(0, replaceStart) + token + workSource.substring(replaceEnd));
            splices.add(workSource.substring(0, replaceStart) + token + ";" + workSource.substring(replaceEnd));
        }
        splices.add(workSource.substring(0, replaceStart)
                + (afterDot ? MARKER + "()" : MARKER)
                + balancedTail(workSource.substring(0, replaceStart)));

        boolean appendParens = !followedByArgList(workSource, replaceEnd);

        // Classement des kinds (pattern kindRank de
        // projet de référence) : un contexte résolu (MEMBER/PACKAGE/IMPORT/TYPE)
        // est DÉFINITIF ; NAME est faible. Un résultat NON-VIDE de rank
        // supérieur gagne toujours ; un résultat VIDE de rank supérieur ne
        // masque JAMAIS un résultat non-vide inférieur (un mis-parse
        // PACKAGE vide ne doit pas éclipser le vrai MEMBER_ACCESS de la
        // forme en appel).
        List<CompletionItem> best = null;
        Context bestCtx = null;
        int bestRank = -1;
        int[] spliceCounter = {0};
        for (String spliced : splices) {
            final int idx = spliceCounter[0]++;
            CompilationUnitDeclaration cud;
            try {
                cud = engine.resolveUnit(filePath, spliced, false);
            } catch (Throwable tResolve) {
                if (trace != null) trace.append("splice#").append(idx)
                        .append(" resolve EXCEPTION ")
                        .append(tResolve.getClass().getSimpleName())
                        .append(": ").append(tResolve.getMessage()).append('\n');
                continue;
            }
            if (cud == null || cud.scope == null) {
                if (trace != null) trace.append("splice#").append(idx)
                        .append(" cud=").append(cud != null)
                        .append(" scope=").append(cud != null && cud.scope != null)
                        .append('\n');
                continue;
            }

            Context ctx;
            {
                String[] at = trace != null ? new String[1] : null;
                ctx = analyzeContext(cud, replaceStart, at, workSource);
                if (trace != null && at != null && at[0] != null
                        && !at[0].isEmpty()) {
                    trace.append(at[0]).append('\n');
                }
            }
            if (ctx == null) continue;

            List<CompletionItem> items;
            try {
                items = collectCandidates(ctx, prefix, appendParens,
                        workSource);
                // Templates DU contexte de CE splice (portage
                // Live/PostfixTemplates du projet de référence v3.20), AVANT le
                // test de vacuité : `sout` (aucun mot-clé/membre ne
                // matche) et `expr.var` (aucun membre « var ») sont des
                // complétions 100% template — les ajouter après la boucle
                // les perdrait quand best == null.
                if (ctx.kind == Kind.MEMBER_ACCESS) {
                    addPostfixTemplates(items, workSource, replaceStart,
                            workCaret, prefix, ctx.rawType, ctx.staticQualifier,
                            focused);
                } else if (ctx.kind == Kind.NAME_REFERENCE
                        && isStatementPosition(workSource, replaceStart)) {
                    addLiveTemplates(items, workSource, replaceStart, prefix);
                }
            } catch (Throwable tItems) {
                // Une branche de candidats a jeté — TRACÉ au lieu d'être
                // avalé (un silence serait indistinguable d'une liste
                // vide).
                if (trace != null) trace.append(" → EXCEPTION ")
                        .append(tItems.getClass().getSimpleName())
                        .append(": ").append(tItems.getMessage()).append('\n');
                continue;
            }
            int rank = kindRank(ctx.kind);
            if (trace != null) trace.append(" [").append(ctx.kind)
                    .append(" r").append(rank).append("] → ")
                    .append(items.size()).append(" items\n");
            if (items.isEmpty()) continue;
            // Un résultat non-vide de rank supérieur gagne ; à rank égal,
            // le plus riche gagne (les splices sont ordonnés par coût
            // croissant de secours).
            if (best == null || rank > bestRank
                    || (rank == bestRank && items.size() > best.size())) {
                best = items;
                bestCtx = ctx;
                bestRank = rank;
            }
            // Un contexte résolu NON VIDE est définitif — inutile d'essayer
            // les formes de secours (pattern « break au premier définitif »
            // du projet de référence) : c'est aussi ce qui rend la complétion
            // INCREMENTALE rapide à la frappe (1 seul splice le plus souvent).
            if (rank == 2) {
                break;
            }
        }
        if (best != null) {
            // Tri final UNIFIÉ : pertinence, puis nom le plus
            // court, puis ordre alphabétique (portage compareBy(score,
            // name.length, name) de CompletionRanker).
            best.sort(RELEVANCE_ORDER);
            return best;
        }

        // Variante « déclaration synthétique » (DERNIER recours, seulement
        // si RIEN n'a été trouvé) : pour `private Str|` SANS nom de champ
        // après le caret, le recovery ecj produit un CONSTRUCTEUR FANTÔME
        // qui JETTE le marqueur → 0 item systématique. On insère « x; »
        // après le marqueur : celui-ci devient le TYPE d'une déclaration
        // valide (`private __m__ x;` → FieldDeclaration(type=__m__) ; dans
        // un corps de méthode → LocalDeclaration) → TYPE_REFERENCE →
        // types + mots-clés. projet de référence teste ce contexte sous la forme
        // `Array| f;` (nom DÉJÀ présent) ; notre variante couvre la frappe
        // progressive SANS nom.
        List<CompletionItem> synth = synthDeclarationCompletions(
                filePath, workSource, replaceStart, replaceEnd, prefix, trace);
        if (synth != null && !synth.isEmpty()) {
            return synth;
        }

        // Secours SANS bindings : hiérarchie inconsistante ou
        // zones de recovery de ecj ne laissent AUCUN binding exploitable,
        // alors que l'index classpath (RawZipReader) contient le type.
        // Le repli lexical « tokens → ClassFileReader » rétablit la
        // complétion d'accès membre sur ces fichiers, au lieu du zéro total.
        if (afterDot) {
            List<CompletionItem> textual =
                    textualMemberCompletions(workSource, replaceStart, prefix);
            // Templates postfixes best-effort SANS bindings (récepteur non
            // résolu — hiérarchie cassée) : seules les templates sans
            // prédicat de type (sout, var, cast…) s'appliquent.
            addPostfixTemplates(textual, workSource, replaceStart,
                    workCaret, prefix, null, false, focused);
            if (!textual.isEmpty()) {
                if (trace != null) trace.append("TEXTUAL → ")
                        .append(textual.size()).append(" items\n");
                textual.sort(RELEVANCE_ORDER);
                return textual;
            }
        }
        // CONTRAT : jamais null (cf. en tête de completeImpl). Les appels
        // suivants peuvent trier — liste VIDE mutable.
        return best != null ? best : new ArrayList<>();
    }

    /**
     * Ajoute les templates postfixes (portage {@code PostfixTemplates.kt},
     * projet de référence v3.20) au résultat d'un contexte MEMBER_ACCESS :
     * {@code expr.var} réécrit toute l'expression en
     * {@code Type name = expr;} avec tab stops LSP ; l'édition additionnelle
     * supprime le récepteur — offsets traduits de la source réduite vers
     * l'ORIGINALE via {@link SourceFocuser.Focused#mapBack}. Filtrées par le
     * type résolu du récepteur ({@code qualifierType}) : une template ne
     * sort jamais là où elle ne compilerait pas. Best-effort : toute
     * défaillance laisse les candidats ordinaires intacts.
     */
    private static void addPostfixTemplates(List<CompletionItem> out,
                                            String workSource, int replaceStart,
                                            int workCaret, String prefix,
                                            TypeBinding qualifierType,
                                            boolean staticQualifier,
                                            SourceFocuser.Focused focused) {
        try {
            int importOffset = importAnchorOffset(workSource);
            for (var t : EcjPostfixTemplates.itemsFor(workSource,
                    replaceStart, workCaret, prefix, qualifierType,
                    staticQualifier, importOffset)) {
                List<CompletionItem.TextEdit> edits = new ArrayList<>();
                // Suppression du récepteur « expr. » : la plage
                // [deleteStart, deleteEnd) PRÉCÈDE immédiatement la clé
                // [replaceStart, caret) — jamais de chevauchement avec
                // la plage de remplacement principale (contrainte LSP).
                // mapBack : les offsets viennent de la source RÉDUITE.
                edits.add(new CompletionItem.TextEdit(
                        focused.mapBack(t.deleteStart()),
                        focused.mapBack(t.deleteEnd()), ""));
                if (t.importFqn() != null) {
                    // L'ancre d'import est en tête de fichier, AVANT
                    // toute réduction (SourceFocuser n'efface que des
                    // CORPS de méthodes) — mapBack y est l'identité.
                    int at = focused.mapBack(t.importOffset());
                    edits.add(new CompletionItem.TextEdit(at, at,
                            "import " + t.importFqn() + ";\n"));
                }
                out.add(new CompletionItem(t.key(),
                        CompletionItem.Kind.SNIPPET, t.example(),
                        t.description(), t.snippet(), t.relevance(),
                        null, true, edits));
            }
        } catch (Throwable t) {
            // Templates best-effort — la complétion ordinaire reste servie.
        }
    }

    /**
     * Ajoute les live templates (portage {@code LiveTemplates.kt},
     * projet de référence v3.20) au résultat d'un contexte NAME_REFERENCE :
     * {@code sout}, {@code psvm}, {@code fori}… en syntaxe snippet LSP
     * native ({@code $0}, {@code ${1:défaut}}, index liés). La garde
     * {@link #isStatementPosition} (à vérifier par l'appelant) empêche
     * toute template d'instruction en position d'expression. Best-effort.
     */
    private static void addLiveTemplates(List<CompletionItem> out,
                                         String workSource, int replaceStart,
                                         String prefix) {
        try {
            for (var t : EcjLiveTemplates.itemsFor(prefix)) {
                out.add(new CompletionItem(t.key(),
                        CompletionItem.Kind.SNIPPET, t.preview(),
                        t.description(), t.snippet(), t.relevance(),
                        null, true, List.of()));
            }
        } catch (Throwable t) {
            // Templates best-effort — la complétion ordinaire reste servie.
        }
    }

    /**
     * Position d'instruction (garde textuelle des live templates) : la clé
     * est en tête de sa ligne ou suit immédiatement {@code ;}, {@code {}
     * ou {@code}} (espaces/tabulations ignorés). Une template d'instruction
     * ({@code sout}, {@code fori}…) n'est JAMAIS proposée en position
     * d'expression ({@code x = sout|}, {@code foo(sout|}…) où son expansion
     * serait syntaxiquement invalide — garde conservatrice : à faux
     * négatif elle ne fait que TAIRE une template, jamais proposer à tort.
     */
    static boolean isStatementPosition(String text, int keyStart) {
        int i = keyStart - 1;
        while (i >= 0) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t') { i--; continue; }
            if (c == '\n') return true;                        // tête de ligne
            if (c == ';' || c == '{' || c == '}') return true;  // fin d'instruction
            return false;                                       // expression/qualifier
        }
        return true; // début de fichier
    }

    /**
     * Offset d'insertion d'un nouvel import (ancre de l'auto-import des
     * templates postfixes) : après le DERNIER import existant, sinon après
     * la déclaration {@code package}, sinon début de fichier — scan
     * textuel de l'en-tête (portage de {@code importOffset()} de
     * {@code JdtCompletionService.kt}, projet de référence v3.20 ; sémantique
     * identique à CodeActionService.importInsertOffset, sans dépendance
     * croisée couches). L'ancre précède tout corps de méthode : offset
     * IDENTIQUE dans la source réduite et l'originale.
     */
    static int importAnchorOffset(String source) {
        int lastImportEnd = -1;
        int packageEnd = -1;
        int i = 0;
        while (i < source.length()) {
            int lineEnd = source.indexOf('\n', i);
            if (lineEnd < 0) lineEnd = source.length();
            String line = source.substring(i, lineEnd).trim();
            boolean blankish = line.isEmpty() || line.startsWith("//")
                    || line.startsWith("/*") || line.startsWith("*");
            if (line.startsWith("package ") && line.endsWith(";")) {
                packageEnd = Math.min(lineEnd + 1, source.length());
            } else if (line.startsWith("import ") && line.endsWith(";")) {
                lastImportEnd = Math.min(lineEnd + 1, source.length());
            } else if (!blankish) {
                break; // première ligne de CODE — fin de l'en-tête
            }
            i = lineEnd + 1;
        }
        if (lastImportEnd >= 0) return lastImportEnd;
        if (packageEnd >= 0) return packageEnd;
        return 0;
    }

    /**
     * Résout la variante {@code marqueur + " x;"} (déclaration
     * synthétique) et en déduit les candidats TYPE_REFERENCE + mots-clés.
     *
     * @return les items (possiblement vide), ou null si la résolution a
     *         échoué.
     */
    private List<CompletionItem> synthDeclarationCompletions(
            String filePath, String workSource, int replaceStart,
            int replaceEnd, String prefix, StringBuilder trace) {
        // La queue part de replaceEnd (fin de l'IDENTIFIANT complet
        // remplacé — frappe au milieu d'un mot comprise).
        String tail = workSource.substring(replaceEnd);
        // La queue d'origine est conservée ; « x; » ferme la déclaration
        // synthétique. Une seconde forme referme aussi les blocs ouverts
        // (balancedTail) pour les fichiers non terminés.
        String head = workSource.substring(0, replaceStart);
        String[] variants = {
                head + MARKER + " x;" + tail,
                head + MARKER + " x;" + balancedTail(head),
        };
        for (String spliced : variants) {
            CompilationUnitDeclaration cud;
            try {
                cud = engine.resolveUnit(filePath, spliced, false);
            } catch (Throwable t) {
                continue;
            }
            if (cud == null || cud.scope == null) continue;
            String[] at = trace != null ? new String[1] : null;
            Context ctx = analyzeContext(cud, replaceStart, at, spliced);
            if (trace != null && at != null && at[0] != null
                    && !at[0].isEmpty()) {
                trace.append("[SYNTH] ").append(at[0]).append('\n');
            }
            if (ctx == null || ctx.kind == Kind.NONE) continue;
            // La variante ne sert QUE la position de TYPE — tout autre kind
            // (le marqueur en position de NOM, p.ex. `private String |`)
            // reste sans candidats.
            if (ctx.kind != Kind.TYPE_REFERENCE) continue;
            try {
                List<CompletionItem> items = collectCandidates(
                        ctx, prefix, false, workSource);
                if (trace != null) trace.append("[SYNTH] → ")
                        .append(items.size()).append(" items\n");
                if (!items.isEmpty()) {
                    items.sort(RELEVANCE_ORDER);
                    return items;
                }
            } catch (Throwable ignored) {
                // Best-effort.
            }
        }
        return null;
    }


    /** Description compacte du contexte (traces de complétion). */
    private static String describe(Context ctx) {
        StringBuilder sb = new StringBuilder("{");
        sb.append(ctx.kind);
        if (ctx.kind == Kind.IMPORT_REFERENCE || ctx.kind == Kind.PACKAGE_REFERENCE) {
            sb.append(" path='").append(ctx.importPath).append('\'');
        } else if (ctx.kind == Kind.MEMBER_ACCESS) {
            sb.append(" on=")
                    .append(ctx.type != null ? ctx.type.readableName() : "?");
        }
        return sb.append('}').toString();
    }

    // ─── Dispatch par kind (portage CandidateCollector) ───────────────

    /**
     * Rank d'un kind (pattern kindRank du projet de référence) : les contextes
     * RÉSOLUS (member/package/import/type) valent 2 — définitifs ; NAME
     * vaut 1 ; NONE 0.
     */
    private static int kindRank(Kind kind) {
        return switch (kind) {
            case MEMBER_ACCESS, PACKAGE_REFERENCE, IMPORT_REFERENCE,
                 TYPE_REFERENCE -> 2;
            case NAME_REFERENCE -> 1;
            case NONE -> 0;
        };
    }

    /**
     * LA source unique de candidats, choisie par le kind du
     * contexte (portage du {@code CandidateCollector.collect} de
     * projet de référence) :
     * <ul>
     *   <li>MEMBER_ACCESS → membres accessibles du type receveur
     *       (bindings résolus) ;</li>
     *   <li>NAME_REFERENCE → noms en portée (locales + membres englobants)
     *       + types non importés de l'index (auto-import) + mots-clés si
     *       préfixe vide ;</li>
     *   <li>TYPE_REFERENCE → types de l'index matchant le préfixe
     *       (auto-import), mots-clés si préfixe vide —
     *       {@code private Str|}, {@code new Foo|} ;</li>
     *   <li>PACKAGE/IMPORT → sous-paquets + types DIRECTS du chemin pointé
     *       (insertion du segment, pas d'import).</li>
     * </ul>
     */
    private List<CompletionItem> collectCandidates(Context ctx, String prefix,
                                                   boolean appendParens,
                                                   String workSource) {
        EcjPrefixMatcher matcher = new EcjPrefixMatcher(prefix);
        return switch (ctx.kind) {
            case MEMBER_ACCESS -> members(ctx, matcher, appendParens);
            case NAME_REFERENCE -> {
                List<CompletionItem> inScope =
                        names(ctx, matcher, appendParens);
                List<CompletionItem> out = new ArrayList<>(inScope);
                // Portage « taken » de CandidateCollector : un
                // type de l'index dont le simple name est DÉJÀ pris par un
                // candidat in-scope (locale/membre) n'est PAS proposé en
                // plus.
                Set<String> taken = new HashSet<>();
                for (CompletionItem it : inScope) taken.add(it.label());
                out.addAll(typeCompletions(ctx, matcher, workSource, taken));
                // Mots-clés FILTRÉS PAR PRÉFIXE — ils doivent apparaître
                // pour tout préfixe, pas seulement pour un préfixe VIDE
                // (« imp » → « import »).
                out.addAll(keywordCompletions(matcher));
                yield out;
            }
            case TYPE_REFERENCE -> {
                List<CompletionItem> out =
                        new ArrayList<>(typeCompletions(ctx, matcher,
                                workSource, null));
                // Idem : mots-clés filtrés par préfixe.
                out.addAll(keywordCompletions(matcher));
                yield out;
            }
            case PACKAGE_REFERENCE, IMPORT_REFERENCE ->
                    packageChildren(ctx.importPath, matcher);
            case NONE -> new ArrayList<>();
        };
    }

    /**
     * Sous-paquets + types DIRECTS sous {@code path} — portage
     * EXACT de {@code packageChildren} du projet de référence
     * (CandidateCollector.kt), les SIX RÈGLES D'OR du filtre subpackage :
     * <ol>
     *   <li><b>Enfants directs uniquement</b> — le PREMIER segment après
     *       {@code q.} : {@code android.} propose {@code content}, {&#64;code os},
     *       {@code view}, {@code widget}… mais JAMAIS {@code content.pm} ;</li>
     *   <li><b>Dédoublonnage par segment</b> — dix classes dans
     *       {@code android.content.*} ne produisent qu'UNE entrée
     *       {@code content} ; les types des trois sources du classpath
     *       (jars, jrt, sources) sont dédoublonnés par FQN ;</li>
     *   <li><b>Préfixe de requête avec le point final</b> — un préfixe VIDE
     *       ({@code import android.|}) liste QUAND MÊME les sous-paquets ;
     *       le filtrage par préfixe utilise le matcher gradué
     *       (prefix/prefix-ci/camel-hump/substring) ;</li>
     *   <li><b>Types directement dans q</b> — requête EXACTE
     *       ({@code directTypesInPackage}, portage exactAll(PACKAGE_TYPES,
     *       q)), DÉTERMINISTE et sans cap arbitraire : une requête
     *       startsWith(q.)+cap matcherait 2452 classes sous android.** et
     *       éjecterait Manifest/R selon l'ordre HashMap (différent entre
     *       JVM desktop et ART). Insertion du nom SIMPLE (le chemin q. est
     *       déjà tapé), JAMAIS d'auto-import ;</li>
     *   <li><b>Insertion des packages = le segment simple</b> — le texte
     *       avant le caret contient déjà {@code q.} ;</li>
     *   <li><b>Exclusions module JVM</b> — N/A : CodeIDE est exclusivement
     *       Android (le classpath compilable contient toujours android.jar).</li>
     * </ol>
     */
    private List<CompletionItem> packageChildren(String path,
                                                 EcjPrefixMatcher matcher) {
        List<CompletionItem> out = new ArrayList<>();
        String q = path == null ? "" : path;
        try {
            var index = engine.typeIndex();
            // Règles 1+2+3+5 : sous-paquets DIRECTS, dédoublonnés par
            // segment, filtrés par le matcher gradué, insertion du SEGMENT.
            Set<String> seen = new HashSet<>();
            for (String seg : index.subPackages(q)) {
                if (seg.isEmpty() || !matcher.matches(seg) || !seen.add(seg)) {
                    continue;
                }
                String fq = q.isEmpty() ? seg : q + "." + seg;
                out.add(new CompletionItem(seg,
                        CompletionItem.Kind.PACKAGE, fq, null, seg,
                        relevance(rankScore(matcher, seg, PROX_PACKAGE,
                                null, null, false, false))));
            }
            // Règle 4 : types DIRECTEMENT dans le paquet q — requête
            // EXACTE, insertion du nom simple, jamais d'auto-import.
            if (!q.isEmpty()) {
                for (String fqn : index.directTypesInPackage(q)) {
                    String tail = fqn.substring(q.length() + 1);
                    if (!matcher.matches(tail)) continue;
                    out.add(new CompletionItem(tail,
                            CompletionItem.Kind.CLASS, fqn, null, tail,
                            relevance(rankScore(matcher, tail, PROX_NESTED_TYPE,
                                    null, null, false, false))));
                }
            }
        } catch (Throwable t) {
            // Index indisponible — best-effort (les sous-paquets déjà
            // collectés restent rendus).
        }
        return out;
    }

    /**
     * Queue équilibrée (portage balancedTail du projet de référence) :
     * referme les parenthèses/accolades/crochets OUVERTS dans {@code head}
     * et termine par «;». Sauve les instructions non terminées qu'ecj
     * jette autrement en recovery (marqueur compris).
     */
    private static String balancedTail(String head) {
        java.util.Deque<Character> stack = new java.util.ArrayDeque<>();
        boolean inString = false, inChar = false, escaped = false;
        for (int i = 0; i < head.length(); i++) {
            char c = head.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (inString) {
                if (c == '"') inString = false;
                continue;
            }
            if (inChar) {
                if (c == '\'') inChar = false;
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '\'' -> inChar = true;
                case '(', '[', '{' -> stack.push(c);
                case ')', ']', '}' -> {
                    if (!stack.isEmpty()) stack.pop();
                }
                default -> { }
            }
        }
        StringBuilder sb = new StringBuilder(";");
        for (char c : stack) {
            switch (c) {
                case '(' -> sb.append(')');
                case '[' -> sb.append(']');
                case '{' -> sb.append('}');
                default -> { }
            }
        }
        return sb.toString();
    }

    // ─── Complétion de secours SANS bindings ──────────────────────────

    /**
     * Fallback « texte → index » pour les accès membres quand la résolution
     * ecj n'a produit AUCUN binding exploitable (hiérarchie inconsistante,
     * R absent avant premier build, zone de recovery à portée null).
     *
     * <p>Chaîne d'analyse purement lexicale sur {@code workSource} :</p>
     * <ol>
     *   <li>le qualifier avant le point est segmenté en tokens ;</li>
     *   <li>si le dernier token ressemble à une variable (minuscule), on
     *       cherche sa déclaration textuelle ({@code Type var}) dans le
     *       texte précédent et on la substitue ;</li>
     *   <li>chaque préfixe de tokens (du plus long au plus court) est testé
     *       comme FQN du type récepteur dans l'index classpath ;</li>
     *   <li>les méthodes/champs publics du binaire trouvé deviennent les
     *       candidats (héritage non exploré — volontairement borné).</li>
     * </ol>
     *
     * @return les candidats (possiblement vides), jamais null.
     */
    private List<CompletionItem> textualMemberCompletions(String workSource,
                                                          int replaceStart,
                                                          String prefix) {
        List<CompletionItem> out = new ArrayList<>();
        try {
            int p = replaceStart - 1;
            if (p < 0 || workSource.charAt(p) != '.') return out;
            p--; // saute le point
            List<String> toks = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            while (p >= 0) {
                char c = workSource.charAt(p);
                if (Character.isJavaIdentifierPart(c)) {
                    cur.insert(0, c);
                    p--;
                } else if (c == '.' && cur.length() > 0) {
                    toks.add(0, cur.toString());
                    cur.setLength(0);
                    p--;
                } else break;
            }
            if (cur.length() > 0) toks.add(0, cur.toString());
            if (toks.isEmpty()) return out;

            // Substitution « variable → type déclaré » si dernier token
            // d'apparence variable ET qu'un type précédent est trouvable.
            List<String> eff = new ArrayList<>(toks);
            String lastTok = toks.get(toks.size() - 1);
            boolean looksVar = !lastTok.isEmpty()
                    && Character.isLowerCase(lastTok.charAt(0));
            if (looksVar && toks.size() >= 1) {
                String declType = findDeclaredTypeName(workSource, lastTok);
                if (declType != null) {
                    eff = new ArrayList<>(
                            toks.subList(0, toks.size() - 1));
                    for (String seg : declType.split("\\.")) {
                        if (!seg.isEmpty()) eff.add(seg);
                    }
                }
            }

            org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader binary =
                    resolveBinaryByTokens(eff);
            if (binary == null) return out;

            // Énumération bornée des membres publics du binaire —
            // matcher gradué + scoring unifié (proximité héritée).
            EcjPrefixMatcher matcher = new EcjPrefixMatcher(prefix);
            Set<String> seen = new HashSet<>();
            for (org.eclipse.jdt.internal.compiler.env.IBinaryMethod m
                    : binary.getMethods()) {
                if (m == null || m.isConstructor()) continue;
                String name = String.valueOf(m.getSelector());
                if (name.startsWith("<") || !seen.add("m:" + name)) continue;
                if (!matcher.matches(name)) continue;
                int score = rankScore(matcher, name, PROX_INHERITED,
                        null, null, false, false);
                out.add(new CompletionItem(name,
                        CompletionItem.Kind.METHOD, null, null, name,
                        relevance(score)));
                if (out.size() >= 60) break;
            }
            for (org.eclipse.jdt.internal.compiler.env.IBinaryField f
                    : binary.getFields()) {
                if (f == null) continue;
                String name = String.valueOf(f.getName());
                if (!seen.add("f:" + name)) continue;
                if (!matcher.matches(name)) continue;
                int score = rankScore(matcher, name, PROX_INHERITED,
                        null, null, false, false);
                out.add(new CompletionItem(name,
                        CompletionItem.Kind.FIELD, null, null, name,
                        relevance(score)));
                if (out.size() >= 80) break;
            }
        } catch (Throwable ignored) {
            // Fallback best-effort — jamais fatal.
        }
        return out;
    }

    /**
     * Cherche dans le texte AVANT le caret une déclaration textuelle
     * {@code TypeNom var} (paramètre ou locale) — matching heuristique.
     */
    private static String findDeclaredTypeName(String source, String var) {
        if (var.isEmpty()) return null;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(?:^|[\\s(,<])([A-Za-z_$][\\w.$]*)\\s+"
                        + java.util.regex.Pattern.quote(var)
                        + "\\s*(?=[=;,)]|\\s*$)",
                java.util.regex.Pattern.MULTILINE);
        Matcher m = p.matcher(source);
        String found = null;
        while (m.find()) {
            found = m.group(1); // la DERNIÈRE déclaration gagne
        }
        return found;
    }

    /**
     * Résout un tableau de segments comme type du classpath — essai du
     * chemin le plus long vers le plus court (« R.layout » puis « R »),
     * avec correspondance exacte de nom simple en dernier recours.
     */
    private org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader
            resolveBinaryByTokens(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) return null;
        for (int k = tokens.size(); k >= 1; k--) {
            StringBuilder fqn = new StringBuilder();
            for (int i = 0; i < k; i++) {
                if (i > 0) fqn.append('.');
                fqn.append(tokens.get(i));
            }
            var reader = engine.binaryType(fqn.toString());
            if (reader != null) return reader;
        }
        // Correspondance simple unique : dernier segment.
        String simple = tokens.get(tokens.size() - 1);
        try {
            for (String fqn : engine.typeIndex()
                    .findTypesByPrefix(simple, 25)) {
                int dot = fqn.lastIndexOf('.');
                String tailSimple = dot < 0 ? fqn : fqn.substring(dot + 1);
                if (!tailSimple.equals(simple)) continue;
                var reader = engine.binaryType(fqn);
                if (reader != null) return reader;
            }
        } catch (Throwable ignored) {
            // index indisponible
        }
        return null;
    }

    // ─── Contexte (portage simplifié de ContextAnalyzer) ───────────────

    /**
     * Nature du contexte de complétion — portage fidèle du
     * {@code CompletionKind} du projet de référence (ContextAnalyzer.kt).
     *
     * <p>Chaque kind est capturé par son visiteur dédié :</p>
     * <ul>
     *   <li><b>TYPE_REFERENCE</b> — {@code private Str|}, {@code new Foo|},
     *       {@code Foo| x}, casts, {@code extends} : le marqueur se parse
     *       en SingleTypeReference/QualifiedTypeReference ;</li>
     *   <li><b>PACKAGE_REFERENCE</b> — {@code android.wid|} HORS import : un
     *       qualifier non-type est un chemin de paquet.</li>
     * </ul>
     */
    enum Kind {
        NONE, NAME_REFERENCE, MEMBER_ACCESS,
        TYPE_REFERENCE, PACKAGE_REFERENCE, IMPORT_REFERENCE
    }

    /**
     * Contexte dérivé du marqueur résolu.
     *
     * <p>Visibilité paquet : les tests du même paquet traquent l'étape
     * exacte qui échoue (zéro devinette).</p>
     *
     * <p>Champs {@link #kind}, {@link #importPath} (chemin pointé pour
     * PACKAGE/IMPORT) et {@link #typeScope} (portée au marqueur pour
     * résoudre les types attendus). Les champs historiques
     * (importStatement/memberAccess/type) restent dérivés du kind pour
     * compatibilité tests.</p>
     */
    static final class Context {
        /** Nature du contexte. */
        Kind kind = Kind.NONE;
        /** Complétion dans un import (import java.util.L|). */
        boolean importStatement;
        /** Chemin du paquet tapé (import java.util.|L) — IMPORT/PACKAGE. */
        String importPath;
        boolean memberAccess;
        /** Type du receveur (MEMBER_ACCESS) ou type englobant (NAME_REFERENCE). */
        ReferenceBinding type;
        /**
         * Type du receveur BRUT (MEMBER_ACCESS) — primitif compris
         * ({@code boolean ok; ok.if|}) : les templates postfixes testent
         * leurs prédicats sur le {@link TypeBinding} exact, là où
         * {@link #type} (ReferenceBinding) est null pour un primitif
         * (un primitif n'a AUCUN membre réel — mais {@code ok.if} reste
         * une réécriture valide).
         */
        TypeBinding rawType;
        /** Type englobant au caret — la base "from" des règles de visibilité. */
        ReferenceBinding enclosingType;
        /** true si le qualifier est un type (appel statique) — seuls les
         *  membres statiques s'appliquent. */
        boolean staticQualifier;
        /** Contexte statique (méthode englobante statique). */
        boolean inStaticContext;
        /**
         * Type ATTENDU au caret — sommet de la pile des types
         * attendus (portage « expected » de ContextAnalyzer) : type déclaré
         * d'une initialisation de locale ({@code StringBuilder sb = <caret>}),
         * LHS d'une affectation, ou type de retour pour un {@code return}.
         * Les candidats compatibles (au niveau erasure) reçoivent le bonus
         * assignable (-300) du scoring unifié. null si non déterminé.
         */
        TypeBinding expectedType;
        /**
         * Portée ecj au marqueur pour TYPE_REFERENCE : permet
         * de résoudre chaque candidat d'index ({@code scope.getType(fqn)})
         * afin d'appliquer le classement par type attendu (pattern
         * {@code resolveType} de CandidateCollector).
         */
        Scope typeScope;
        /** Variables locales/paramètres en portée avant le marqueur. */
        final List<LocalVar> locals = new ArrayList<>();

        /** Applique le kind et synchronise les champs legacy. */
        void applyKind(Kind k) {
            this.kind = k;
            this.importStatement = k == Kind.IMPORT_REFERENCE;
            this.memberAccess = k == Kind.MEMBER_ACCESS;
        }
    }

    /**
     * Le flag {@code parameter} distingue paramètres (proximité
     * PARAMETER, kind LSP PARAMETER) et locales (LOCAL) — portage ScopeVar
     * du projet de référence.
     */
    private record LocalVar(String name, TypeBinding type, boolean parameter) {}

    /** Résultat de la résolution de qualifier : type + nature statique. */
    private record QualifierResolution(TypeBinding type, boolean staticQualifier) {}

    /**
     * Traverse l'AST résolu, localise le marqueur et en dérive le contexte.
     * Retourne null si le marqueur n'est pas trouvé (parse en échec).
     */
    private static Context analyzeContext(CompilationUnitDeclaration cud, int markerStart) {
        return analyzeContext(cud, markerStart, null, null);
    }

    /**
     * Surcharge instrumentée — {@code out} recevra une trace
     * texte de l'analyse ({@code String[]} length&gt;=1) pour diagnostic.
     * {@code source} est la source parsée par ecj (nécessaire pour scanner
     * les arguments de MessageSend et déterminer le type attendu).
     */
    static Context analyzeContext(CompilationUnitDeclaration cud,
                                  int markerStart, String[] out, String source) {
        StringBuilder trace = out != null ? new StringBuilder() : null;
        Context found = analyzeContextImpl(cud, markerStart, trace, source);
        if (out != null && trace != null) {
            out[0] = trace.toString();
        }
        return found;
    }

    private static Context analyzeContextImpl(CompilationUnitDeclaration cud,
                                              int markerStart,
                                              StringBuilder trace,
                                              String source) {
        // Import : le marqueur est dans un ImportReference — complétion de
        // paquets/types sous le chemin déjà tapé.
        //
        // Détection SIMPLIFIÉE (pattern importContext de
        // projet de référence) : « l'import dont les TOKENS contiennent le marqueur ».
        // Exiger en PLUS l'égalité EXACTE de la position source du token
        // ({@code sourcePositions[i] >>> 32 == markerStart}) serait fragile :
        // selon la forme de splice et le recovery ecj, les positions
        // rapportées divergent de quelques octets et la détection raterait
        // SILENCIEUSEMENT (le marqueur retomberait dans un
        // QualifiedNameReference sans qualifier résolu → 0 item). La
        // tolérance aux positions est le comportement du projet de référence.
        if (trace != null) {
            trace.append("imports=").append(cud.imports == null ? -1 : cud.imports.length);
        }
        if (cud.imports != null) {
            for (var imp : cud.imports) {
                if (imp.tokens == null) continue;
                int markerIdx = -1;
                for (int i = 0; i < imp.tokens.length; i++) {
                    if (String.valueOf(imp.tokens[i]).equals(MARKER)) {
                        markerIdx = i;
                        break;
                    }
                }
                if (markerIdx < 0) continue;
                Context ctx = new Context();
                ctx.applyKind(Kind.IMPORT_REFERENCE);
                StringBuilder path = new StringBuilder();
                for (int j = 0; j < markerIdx; j++) {
                    if (path.length() > 0) path.append('.');
                    path.append(String.valueOf(imp.tokens[j]));
                }
                ctx.importPath = path.toString();
                if (trace != null) {
                    trace.append(" → IMPORT path='").append(path)
                            .append('\'');
                }
                return ctx;
            }
        }
        Context[] result = {null};
        final int[] hits = {0, 0, 0, 0}; // qual, fieldRef, send, single
        final int[] nodes = {0, 0, 0}; // typeDecl, methodDecl, block
        try {
            if (trace != null) trace.append(";traverse BEGIN (scope=")
                    .append(cud.scope != null ? "ok" : "NULL").append(')');
            EcjAst.traverseResilient(cud, new org.eclipse.jdt.internal.compiler.ASTVisitor() {

                /**
                 * Publie le contexte capturé puis ARRÊTE la traversée
                 * (exception MarkerFound — zéro stack trace) : les piles
                 * (types, statique, types attendus, locales) sont copiées
                 * dans le contexte AVANT l'arrêt, tout ce qui reste à
                 * visiter est postérieur au caret.
                 */
                void captured(Context ctx) {
                    result[0] = ctx;
                    throw new MarkerFound();
                }
                final List<ReferenceBinding> typeStack = new ArrayList<>();
                final List<Boolean> staticStack = new ArrayList<>();
                /** Type de retour de la méthode englobante
                 *  (portage methodReturn de ContextAnalyzer) : alimente le
                 *  type attendu des {@code return |}. */
                final List<TypeBinding> methodReturnStack = new ArrayList<>();
                final List<LocalVar> locals = new ArrayList<>();
                /** Pile des types ATTENDUS (portage « expected »
                 *  de ContextAnalyzer) : poussée au visit
                 *  des LocalDeclaration (type déclaré), Assignment (type du
                 *  LHS) et ReturnStatement (retour de la méthode englobante),
                 *  dépilée au endVisit — la capture prend le SOMMET. */
                final List<TypeBinding> expectedStack = new ArrayList<>();

                @Override
                public boolean visit(TypeDeclaration td,
                        org.eclipse.jdt.internal.compiler.lookup.CompilationUnitScope s) {
                    nodes[0]++;
                    typeStack.add(td.binding);
                    return true;
                }

                @Override
                public boolean visit(TypeDeclaration td,
                        org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
                    nodes[0]++;
                    typeStack.add(td.binding);
                    return true;
                }

                @Override
                public boolean visit(TypeDeclaration td,
                        org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
                    nodes[0]++;
                    typeStack.add(td.binding);
                    return true;
                }

                @Override
                public void endVisit(TypeDeclaration td,
                        org.eclipse.jdt.internal.compiler.lookup.CompilationUnitScope s) {
                    pop(typeStack);
                }

                @Override
                public void endVisit(TypeDeclaration td,
                        org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
                    pop(typeStack);
                }

                @Override
                public void endVisit(TypeDeclaration td,
                        org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
                    pop(typeStack);
                }

                @Override
                public boolean visit(
                        org.eclipse.jdt.internal.compiler.ast.MethodDeclaration md,
                        org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
                    nodes[1]++;
                    boolean stat = md.binding != null && md.binding.isStatic();
                    staticStack.add(stat);
                    methodReturnStack.add(md.binding == null
                            ? null : md.binding.returnType);
                    collectArgs(md.arguments);
                    onRecoveredDeclaration(
                            String.valueOf(md.selector), md.returnType, s);
                    return true;
                }

                @Override
                public boolean visit(
                        org.eclipse.jdt.internal.compiler.ast.ConstructorDeclaration cd,
                        org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
                    nodes[1]++;
                    staticStack.add(false);
                    methodReturnStack.add(null);
                    collectArgs(cd.arguments);
                    return true;
                }

                /**
                 * Déclaration RECOVERED : ecj parse
                 * {@code private Str|} (classe incomplète) en
                 * Méthode(nom=marqueur, SANS type de retour) ou en
                 * Champ(type=marqueur / nom=marqueur sans type). Si le
                 * marqueur occupe la position du TYPE → TYPE_REFERENCE
                 * (types + mots-clés : String, StringBuilder, import…).
                 * Si un TYPE est déjà présent ({@code private String |}),
                 * le marqueur est en position de NOM → rien (parité
                 * projet de référence : ContextAnalyzer ne capture pas les noms de
                 * déclarations).
                 */
                void onRecoveredDeclaration(String selectorOrName,
                        org.eclipse.jdt.internal.compiler.ast.TypeReference declaredType,
                        Scope scope) {
                    if (result[0] != null) return;
                    boolean markerIsName = selectorOrName != null
                            && selectorOrName.equals(MARKER);
                    boolean markerIsType = declaredType != null
                            && markerIn(declaredType);
                    if (!markerIsName && !markerIsType) return;
                    if (trace != null) trace.append(";RECOVERED-DECL name=")
                            .append(markerIsName).append(" type=")
                            .append(markerIsType);
                    // Type présent ET résolu → marqueur en position de NOM
                    // → pas de candidats de type.
                    if (markerIsName && declaredType != null
                            && !markerIsType) {
                        if (trace != null) trace.append(" (position NOM — skip)");
                        return;
                    }
                    captureType(scope);
                }

                /** true si le TypeReference (ou ses segments) contient le marqueur. */
                boolean markerIn(
                        org.eclipse.jdt.internal.compiler.ast.TypeReference type) {
                    if (type == null) return false;
                    if (type instanceof SingleTypeReference str) {
                        return String.valueOf(str.token).equals(MARKER);
                    }
                    if (type instanceof QualifiedTypeReference qtr) {
                        for (char[] t : qtr.tokens) {
                            if (String.valueOf(t).equals(MARKER)) return true;
                        }
                    }
                    return false;
                }

                @Override
                public boolean visit(
                        org.eclipse.jdt.internal.compiler.ast.Block b,
                        org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
                    nodes[2]++;
                    return true;
                }

                @Override
                public boolean visit(
                        org.eclipse.jdt.internal.compiler.ast.FieldDeclaration fd,
                        org.eclipse.jdt.internal.compiler.lookup.MethodScope s) {
                    // Champ recovered (`private Str|` peut
                    // aussi se parser en FieldDeclaration) : cf.
                    // onRecoveredDeclaration.
                    onRecoveredDeclaration(fd.name == null
                            ? null : String.valueOf(fd.name), fd.type, s);
                    // Type attendu = type déclaré du champ
                    // (`private String name = ?` attend String).
                    if (fd.binding != null) {
                        expectedStack.add(fd.binding.type);
                    }
                    return true;
                }

                @Override
                public void endVisit(
                        org.eclipse.jdt.internal.compiler.ast.FieldDeclaration fd,
                        org.eclipse.jdt.internal.compiler.lookup.MethodScope s) {
                    // Pop le type attendu du champ.
                    pop(expectedStack);
                }

                @Override
                public void endVisit(
                        org.eclipse.jdt.internal.compiler.ast.MethodDeclaration md,
                        org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
                    pop(staticStack);
                    pop(methodReturnStack);
                }

                @Override
                public void endVisit(
                        org.eclipse.jdt.internal.compiler.ast.ConstructorDeclaration cd,
                        org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
                    pop(staticStack);
                    pop(methodReturnStack);
                }

                @Override
                public boolean visit(
                        org.eclipse.jdt.internal.compiler.ast.LocalDeclaration ld,
                        org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
                    if (ld.sourceStart < markerStart && ld.binding != null) {
                        locals.add(new LocalVar(String.valueOf(ld.name),
                                ld.binding.type, false));
                    }
                    // Type attendu = type déclaré de la locale
                    // (portage visit(LocalDeclaration) de ContextAnalyzer) :
                    // `StringBuilder sb = new Str|` attend StringBuilder.
                    if (ld.binding != null) {
                        expectedStack.add(ld.binding.type);
                    }
                    return true;
                }

                @Override
                public void endVisit(
                        org.eclipse.jdt.internal.compiler.ast.LocalDeclaration ld,
                        org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
                    pop(expectedStack);
                }

                // ── Suivi des affectations pour le type attendu ──

                @Override
                public boolean visit(Assignment a,
                        org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
                    // Empilé AVANT la descente dans le membre droit : tant
                    // que le marqueur est dans ce sous-arbre, l'affectation
                    // est au sommet de pile et son LHS porte le type cible.
                    TypeBinding lhsType = null;
                    try {
                        lhsType = a.lhs == null ? null : a.lhs.resolvedType;
                    } catch (Throwable ignored) {
                        // LHS non résolu.
                    }
                    expectedStack.add(lhsType);
                    return true;
                }

                @Override
                public void endVisit(Assignment a,
                        org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
                    pop(expectedStack);
                }

                @Override
                public boolean visit(
                        org.eclipse.jdt.internal.compiler.ast.ReturnStatement rs,
                        org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
                    // Type attendu d'un return = type de retour de
                    // la méthode englobante (portage ContextAnalyzer) :
                    // `String f() { return app| }` attend String.
                    expectedStack.add(methodReturnStack.isEmpty()
                            ? null : lastOfType(methodReturnStack));
                    return true;
                }

                @Override
                public void endVisit(
                        org.eclipse.jdt.internal.compiler.ast.ReturnStatement rs,
                        org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
                    pop(expectedStack);
                }

                // ── Arguments de MessageSend : type attendu ──
                // La logique est fusionnée dans le visiteur MessageSend existant
                // (voir plus bas). On y ajoute le push du type attendu.

                // ── ConditionalExpression : type attendu ──
                // `cond ? a : ?` attend le type de a (ou vice-versa).
                @Override
                public boolean visit(
                        org.eclipse.jdt.internal.compiler.ast.ConditionalExpression ce,
                        org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
                    TypeBinding t = null;
                    try {
                        // Si le marqueur est dans la branche "else", le type
                        // attendu est celui de la branche "if" (et inverse-versa).
                        if (ce.valueIfTrue != null && ce.valueIfFalse != null) {
                            if (markerStart >= ce.valueIfTrue.sourceStart
                                    && markerStart <= ce.valueIfTrue.sourceEnd) {
                                t = ce.valueIfFalse.resolvedType;
                            } else if (markerStart >= ce.valueIfFalse.sourceStart
                                    && markerStart <= ce.valueIfFalse.sourceEnd) {
                                t = ce.valueIfTrue.resolvedType;
                            }
                        }
                    } catch (Throwable ignored) {
                        // Branches incomplètes — type attendu inconnu (null).
                    }
                    expectedStack.add(t);
                    return true;
                }

                @Override
                public void endVisit(
                        org.eclipse.jdt.internal.compiler.ast.ConditionalExpression ce,
                        org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
                    pop(expectedStack);
                }

                // ── CastExpression : type attendu ──
                // `(String) ?` attend String.
                @Override
                public boolean visit(
                        org.eclipse.jdt.internal.compiler.ast.CastExpression ce,
                        org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
                    TypeBinding t = null;
                    try {
                        t = ce.type != null ? ce.type.resolvedType : null;
                    } catch (Throwable ignored) {
                        // Type de cast non résolu — pas de type attendu.
                    }
                    expectedStack.add(t);
                    return true;
                }

                @Override
                public void endVisit(
                        org.eclipse.jdt.internal.compiler.ast.CastExpression ce,
                        org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
                    pop(expectedStack);
                }

                // ── ThrowStatement ──
                // `throw ?` attend un subtype de Throwable.
                @Override
                public boolean visit(
                        org.eclipse.jdt.internal.compiler.ast.ThrowStatement ts,
                        org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
                    // On push java.lang.Throwable comme type attendu.
                    // Idéalement, on résoudrait le type d'exception exact
                    // (try-catch context), mais c'est complexe.
                    expectedStack.add(null);  // null = pas de tri par type
                    return true;
                }

                @Override
                public void endVisit(
                        org.eclipse.jdt.internal.compiler.ast.ThrowStatement ts,
                        org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
                    pop(expectedStack);
                }

                @Override
                public boolean visit(QualifiedNameReference ref,
                        org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
                    if (trace != null) trace.append(";visQN-BS")
                            .append(s != null ? "" : "(null!)");
                    onQualified(ref, s);
                    return true;
                }

                @Override
                public boolean visit(QualifiedNameReference ref,
                        org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
                    if (trace != null) trace.append(";visQN-CS")
                            .append(s != null ? "" : "(null!)");
                    onQualified(ref, s);
                    return true;
                }

                @Override
                public boolean visit(FieldReference ref,
                        org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
                    onFieldRef(ref);
                    return true;
                }

                @Override
                public boolean visit(FieldReference ref,
                        org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
                    onFieldRef(ref);
                    return true;
                }

                @Override
                public boolean visit(MessageSend ms,
                        org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
                    if (trace != null) hits[2]++;
                    if (result[0] == null
                            && String.valueOf(ms.selector).equals(MARKER)) {
                        boolean staticQual = ms.receiver instanceof SingleNameReference snr
                                && snr.binding instanceof ReferenceBinding;
                        captureMember(receiverType(ms), staticQual);
                    }
                    // Si le marqueur est dans les arguments,
                    // push le type du paramètre à la position du caret.
                    if (ms.sourceStart < markerStart && markerStart <= ms.sourceEnd
                            && source != null) {
                        TypeBinding expected = null;
                        try {
                            if (ms.binding != null && ms.binding.isValidBinding()
                                    && ms.binding.parameters != null) {
                                int argStart = -1;
                                for (int i = ms.sourceStart; i < markerStart && i < source.length(); i++) {
                                    if (source.charAt(i) == '(') { argStart = i + 1; break; }
                                }
                                if (argStart >= 0) {
                                    int paramIdx = 0, depth = 0;
                                    for (int i = argStart; i < markerStart && i < source.length(); i++) {
                                        char c = source.charAt(i);
                                        if (c == '(') depth++;
                                        else if (c == ')') depth--;
                                        else if (c == ',' && depth == 0) paramIdx++;
                                    }
                                    if (paramIdx < ms.binding.parameters.length) {
                                        expected = ms.binding.parameters[paramIdx];
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                            // Signature non résoluble — pas de type attendu.
                        }
                        expectedStack.add(expected);
                    }
                    return true;
                }

                @Override
                public void endVisit(MessageSend ms,
                        org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
                    // Pop le type attendu si on a push.
                    if (ms.sourceStart < markerStart && markerStart <= ms.sourceEnd
                            && source != null) {
                        pop(expectedStack);
                    }
                }

                @Override
                public boolean visit(SingleNameReference ref,
                        org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
                    onSingleName(ref);
                    return true;
                }

                @Override
                public boolean visit(SingleNameReference ref,
                        org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
                    onSingleName(ref);
                    return true;
                }

                // ── Positions de TYPE (pattern ContextAnalyzer
                //    du projet de référence) : `private Str|`, `new Foo|`, `Foo| x`,
                //    casts, extends/throws… ecj parse le marqueur en
                //    TypeReference. ──

                @Override
                public boolean visit(SingleTypeReference ref,
                        org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
                    onTypeRef(ref, s);
                    return true;
                }

                @Override
                public boolean visit(SingleTypeReference ref,
                        org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
                    onTypeRef(ref, s);
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

                // ── captures ──

                void onQualified(QualifiedNameReference ref, Scope scope) {
                    if (trace != null && ref.tokens.length >= 2) hits[0]++;
                    if (result[0] != null || ref.tokens.length < 2) return;
                    if (!String.valueOf(ref.tokens[ref.tokens.length - 1])
                            .equals(MARKER)) {
                        return;
                    }
                    if (trace != null) {
                        trace.append(";QUALIFIED marker last-token ok, qualifier=");
                        for (char[] t : ref.tokens) {
                            trace.append(String.valueOf(t)).append('.');
                        }
                    }
                    // Chemin pointé AVANT le marqueur — pour PACKAGE_REFERENCE.
                    StringBuilder pathB = new StringBuilder();
                    for (int i = 0; i < ref.tokens.length - 1; i++) {
                        if (pathB.length() > 0) pathB.append('.');
                        pathB.append(String.valueOf(ref.tokens[i]));
                    }
                    String qualifierPath = pathB.toString();

                    // Résout le qualifier (tout sauf le marqueur).
                    char[][] qualifier = new char[ref.tokens.length - 1][];
                    System.arraycopy(ref.tokens, 0, qualifier, 0, qualifier.length);

                    // Sur les sources avec zones non
                    // récupérables (R avant build, hiérarchie incomplète),
                    // ecj émet des nœuds de RÉCUPÉRATION dont la portée
                    // (BlockScope) est NULL. Le cheminement par scope échoue
                    // alors en NPE silencieuse.
                    // Fallback sans scope : réutiliser le binding que le
                    // compilateur a DÉJÀ posé sur ce nœud.
                    if (scope != null) {
                        QualifierResolution q =
                                resolveQualifier(scope, qualifier, ref);
                        if (q != null && q.type instanceof ReferenceBinding rb) {
                            if (trace != null) {
                                trace.append(" → resolu=")
                                        .append(rb.readableName());
                            }
                            captureMember(rb, q.staticQualifier);
                            return;
                        }
                        if (q != null && q.type != null) {
                            // Qualifier résolu PRIMITIF ({@code ok.} sur un
                            // boolean) : ce n'est PAS un paquet — MEMBER_ACCESS
                            // sans membres réels (un primitif n'en a aucun),
                            // mais les templates postfixes s'appliquent au
                            // TypeBinding brut ({@code ok.if} → if (ok) { }).
                            if (trace != null) {
                                trace.append(" → resolu(primitif)=")
                                        .append(q.type.readableName());
                            }
                            captureMember(q.type, q.staticQualifier);
                            return;
                        }
                        // PACKAGE_REFERENCE (pattern
                        // onQualifiedName du projet de référence) : un qualifier NON
                        // résolu en type est un CHEMIN DE PAQUET —
                        // {@code android.wid|}, {@code java.util.fun|} HORS
                        // import.
                        if (trace != null) {
                            trace.append(" → PACKAGE path='")
                                    .append(qualifierPath).append('\'');
                        }
                        capturePackage(qualifierPath);
                        return;
                    }

                    // Chemin sans scope — nœuds de recovery.
                    TypeBinding t = recoveredQualifierType(ref, qualifier);
                    if (t instanceof ReferenceBinding rb) {
                        if (trace != null) {
                            trace.append(" → recovery resolu=")
                                    .append(rb.readableName());
                        }
                        captureMember(rb,
                                qualifierDecidesStatic(ref, qualifier));
                    } else {
                        // Idem : recovery sans type → paquet.
                        if (trace != null) {
                            trace.append(" → recovery PACKAGE path='")
                                    .append(qualifierPath).append('\'');
                        }
                        capturePackage(qualifierPath);
                    }
                }

                /**
                 * Type du qualifier d'une référence de récupération, dérivé
                 * des bindings résolus par ecj lui-même :
                 * {@code ref.binding} porte le PREMIER segment (variable ou
                 * type), {@code ref.otherBindings} la chaîne des segments
                 * intermédiaires.
                 */
                private TypeBinding recoveredQualifierType(
                        QualifiedNameReference ref, char[][] qualifier) {
                    Binding first = ref.binding;
                    if (!(first instanceof org.eclipse.jdt.internal.compiler.lookup
                            .VariableBinding vb) || !first.isValidBinding()) {
                        return null;
                    }
                    TypeBinding type = vb.type;
                    FieldBinding[] others = ref.otherBindings;
                    int expectedOthers = qualifier.length - 1;
                    if (others == null || others.length == 0) {
                        return type;
                    }
                    // Chaîne intermédiaire : on ne garde que le dernier maillon
                    // AVANT le marqueur (les nœuds de recovery tronquent parfois).
                    int take = Math.min(expectedOthers - 1, others.length);
                    for (int i = 0; i < take; i++) {
                        if (type == null) return null;
                        type = typeOfFieldLike(type, others[i]);
                        if (type == null) return null;
                    }
                    return type;
                }

                /** Champ par nom simple sur un ReferenceBinding (sans scope). */
                private TypeBinding typeOfFieldLike(TypeBinding owner,
                                                    FieldBinding want) {
                    if (!(owner instanceof ReferenceBinding rb)) return null;
                    String wantName = String.valueOf(want.name);
                    for (FieldBinding f : rb.fields()) {
                        if (f != null && String.valueOf(f.name).equals(wantName)) {
                            return f.type;
                        }
                    }
                    return null;
                }

                /**
                 * @return true si le chemin du qualifier est manifestement
                 *         statique (le premier segment est un TYPE connu du
                 *         moteur — cas {@code R.layout.}). Sans scope on se
                 *         fie au nature du premier binding.
                 */
                private boolean qualifierDecidesStatic(
                        QualifiedNameReference ref, char[][] qualifier) {
                    return false;
                }

                void onFieldRef(FieldReference ref) {
                    if (trace != null) hits[1]++;
                    if (result[0] != null
                            || !String.valueOf(ref.token).equals(MARKER)) {
                        return;
                    }
                    captureMember(receiverType(ref), false);
                }

                void onSingleName(SingleNameReference ref) {
                    if (trace != null) hits[3]++;
                    if (result[0] != null
                            || !String.valueOf(ref.token).equals(MARKER)) {
                        return;
                    }
                    if (trace != null) trace.append(";SINGLE marker ok");
                    Context ctx = new Context();
                    ctx.applyKind(Kind.NAME_REFERENCE);
                    ctx.type = last(typeStack);
                    ctx.enclosingType = last(typeStack);
                    ctx.staticQualifier = false;
                    ctx.inStaticContext = lastBoolean(staticStack);
                    ctx.expectedType = expectedStack.isEmpty()
                            ? null : lastOfType(expectedStack);
                    ctx.locals.addAll(locals);
                    captured(ctx);
                }

                /**
                 * Marqueur en position de TYPE SIMPLE
                 * ({@code private Str|}, {@code new Foo|}) → TYPE_REFERENCE
                 * (portage onTypeRef du projet de référence) : candidats = types de
                 * l'index matchant le préfixe, avec auto-import, classés par
                 * type attendu quand disponible.
                 */
                void onTypeRef(SingleTypeReference ref, Scope scope) {
                    if (result[0] != null
                            || !String.valueOf(ref.token).equals(MARKER)) {
                        return;
                    }
                    if (trace != null) trace.append(";TYPE-REF marker ok");
                    captureType(scope);
                }

                /**
                 * Marqueur en position de TYPE QUALIFIÉ
                 * ({@code java.util.Foo|}) → sous-paquets/types du chemin
                 * (portage onQualifiedType du projet de référence). Saute le cas où
                 * la tête est une locale en portée : c'est un accès membre
                 * mal récupéré par ecj ({@code formatter.x} → « Type var »),
                 * le chemin member-access s'en charge.
                 */
                void onQualifiedType(QualifiedTypeReference ref) {
                    if (result[0] != null || ref.tokens == null
                            || ref.tokens.length < 2
                            || !String.valueOf(
                                    ref.tokens[ref.tokens.length - 1])
                                    .equals(MARKER)) {
                        return;
                    }
                    String head = String.valueOf(ref.tokens[0]);
                    for (LocalVar v : locals) {
                        if (v.name().equals(head)) return;
                    }
                    StringBuilder pathB = new StringBuilder();
                    for (int i = 0; i < ref.tokens.length - 1; i++) {
                        if (pathB.length() > 0) pathB.append('.');
                        pathB.append(String.valueOf(ref.tokens[i]));
                    }
                    if (trace != null) trace.append(";QUAL-TYPE path='")
                            .append(pathB).append('\'');
                    capturePackage(pathB.toString());
                }

                /**
                 * Capture PACKAGE_REFERENCE : complétion de
                 * sous-paquets + types directement sous {@code path}.
                 */
                void capturePackage(String path) {
                    Context ctx = new Context();
                    ctx.applyKind(Kind.PACKAGE_REFERENCE);
                    ctx.importPath = path == null ? "" : path;
                    ctx.enclosingType = last(typeStack);
                    ctx.inStaticContext = lastBoolean(staticStack);
                    captured(ctx);
                }

                /**
                 * Capture TYPE_REFERENCE (portage
                 * onTypeRef/capture du projet de référence).
                 */
                void captureType(Scope scope) {
                    Context ctx = new Context();
                    ctx.applyKind(Kind.TYPE_REFERENCE);
                    ctx.enclosingType = last(typeStack);
                    ctx.inStaticContext = lastBoolean(staticStack);
                    ctx.expectedType = expectedStack.isEmpty()
                            ? null : lastOfType(expectedStack);
                    ctx.typeScope = scope;
                    ctx.locals.addAll(locals);
                    captured(ctx);
                }

                void captureMember(TypeBinding receiverType, boolean staticQualifier) {
                    if (trace != null) {
                        trace.append(";captureMember receiver=")
                                .append(receiverType == null ? "NULL"
                                        : String.valueOf(receiverType.readableName()));
                    }
                    Context ctx = new Context();
                    ctx.applyKind(Kind.MEMBER_ACCESS);
                    ctx.rawType = receiverType;
                    ctx.type = receiverType instanceof ReferenceBinding rb ? rb : null;
                    ctx.enclosingType = last(typeStack);
                    ctx.staticQualifier = staticQualifier;
                    ctx.inStaticContext = lastBoolean(staticStack);
                    ctx.expectedType = expectedStack.isEmpty()
                            ? null : lastOfType(expectedStack);
                    ctx.locals.addAll(locals);
                    captured(ctx);
                }

                TypeBinding receiverType(MessageSend ms) {
                    try {
                        return ms.receiver.resolvedType;
                    } catch (Throwable t) {
                        return null;
                    }
                }

                TypeBinding receiverType(FieldReference ref) {
                    try {
                        return ref.receiver.resolvedType;
                    } catch (Throwable t) {
                        return null;
                    }
                }

                /** Résout le qualifier : d'abord comme TYPE (accès
                 *  statique), sinon variable + marche de champs/membres
                 *  (pattern resolveQualifier du projet de référence). Le drapeau
                 *  staticQualifier suit la nature du CHEMIN : un type ou un
                 *  membre de type est statique, un champ/variable est une
                 *  valeur d'instance. */
                QualifierResolution resolveQualifier(Scope scope, char[][] toks,
                        QualifiedNameReference ref) {
                    try {
                        TypeBinding asType = scope.getType(toks, toks.length);
                        if (asType != null && asType.isValidBinding()) {
                            if (trace != null) trace.append(";qual TYPE ")
                                    .append(asType.readableName());
                            return new QualifierResolution(asType, true);
                        }
                    } catch (Throwable ignored) {
                        // Pas un type — on essaie la marche de variables.
                    }
                    try {
                        Binding first = scope.getBinding(toks[0],
                                Binding.VARIABLE | Binding.TYPE, ref, true);
                        if (trace != null && first != null) {
                            trace.append(";qual first=")
                                    .append(first.getClass().getSimpleName())
                                    .append(" valid=").append(first.isValidBinding());
                        }
                        if (first == null || !first.isValidBinding()) return null;
                        TypeBinding type = typeOf(first);
                        boolean staticQual = first instanceof TypeBinding;
                        for (int i = 1; i < toks.length; i++) {
                            if (!(type instanceof ReferenceBinding rb)) return null;
                            FieldBinding field = scope.getField(rb, toks[i], ref);
                            if (field != null && field.isValidBinding()) {
                                type = field.type;
                                staticQual = false;
                                continue;
                            }
                            ReferenceBinding member = null;
                            for (ReferenceBinding mt : rb.memberTypes()) {
                                if (String.valueOf(mt.sourceName())
                                        .equals(String.valueOf(toks[i]))) {
                                    member = mt;
                                    break;
                                }
                            }
                            if (member == null) return null;
                            type = member;
                            staticQual = true;
                        }
                        return new QualifierResolution(type, staticQual);
                    } catch (Throwable t) {
                        if (trace != null) trace.append(";qual EX ")
                                .append(t.getClass().getSimpleName())
                                .append(": ").append(t.getMessage());
                        return null;
                    }
                }

                TypeBinding typeOf(Binding binding) {
                    if (binding instanceof org.eclipse.jdt.internal.compiler.lookup
                            .VariableBinding vb) {
                        return vb.type;
                    }
                    if (binding instanceof TypeBinding tb) {
                        return tb;
                    }
                    return null;
                }

                void collectArgs(
                        org.eclipse.jdt.internal.compiler.ast.Argument[] args) {
                    if (args == null) return;
                    for (var a : args) {
                        if (a.binding != null) {
                            locals.add(new LocalVar(String.valueOf(a.name),
                                    a.binding.type, true));
                        }
                    }
                }

                ReferenceBinding last(List<ReferenceBinding> stack) {
                    return stack.isEmpty() ? null : stack.get(stack.size() - 1);
                }

                boolean lastBoolean(List<Boolean> stack) {
                    return !stack.isEmpty() && stack.get(stack.size() - 1);
                }

                <T> void pop(List<T> stack) {
                    if (!stack.isEmpty()) stack.remove(stack.size() - 1);
                }

                <T> void popLast(List<T> stack) {
                    pop(stack);
                }

                /** Dernier TypeBinding non-null de la pile (ou null). */
                TypeBinding lastOfType(List<TypeBinding> stack) {
                    for (int i = stack.size() - 1; i >= 0; i--) {
                        TypeBinding t = stack.get(i);
                        if (t != null) return t;
                    }
                    return null;
                }

            });
        } catch (MarkerFound found) {
            // Arrêt anticipé voulu — le contexte est capturé : tout le
            // reste de la traversée serait postérieur au caret (calcul
            // gaspillé, portage ContextAnalyzer du projet de référence v3.20).
            if (trace != null) trace.append(";traverse EARLY-STOP (marker found)");
        } catch (Throwable t) {
            if (trace != null) trace.append(";traverse EXCEPTION ")
                    .append(t.getClass().getSimpleName()).append(": ")
                    .append(t.getMessage());
            // Traversée partielle — le contexte reste null si le marqueur
            // n'a pas été atteint.
        }
        if (trace != null && result[0] == null) {
            trace.append(";RESULT NULL").append(";nodes[t=").append(nodes[0])
                    .append(" m=").append(nodes[1]).append(" b=")
                    .append(nodes[2]).append(']')
                    .append(";hits[q=").append(hits[0])
                    .append(" f=").append(hits[1]).append(" m=")
                    .append(hits[2]).append(" s=").append(hits[3]).append(']');
        }
        return result[0];
    }

    // ─── Types / mots-clés / imports ────────────────────────────────────

    /**
     * Types non-importés matchant le préfixe (jars + jrt +
     * sources) — portage de {@code unimportedTypes} +
     * {@code unimportedTypeCandidate} de CandidateCollector :
     * <ul>
     *   <li><b>taken</b> — un simple name déjà pris par un candidat
     *       in-scope (locale/membre) n'est PAS re-proposé ;</li>
     *   <li><b>collision d'import</b> — si un import explicite LIE un
     *       simple name à un AUTRE FQN, l'insertion non qualifiée
     *       résoudrait au mauvais type : on insère le FQN et on n'attache
     *       PAS d'auto-import ;</li>
     *   <li><b>visibilité déjà acquise</b> — pas d'auto-import pour
     *       java.lang, le paquet courant, un FQN déjà importé ou un paquet
     *       déjà importé on-demand ({@code import java.util.*;}) ;</li>
     *   <li><b>affinité</b> — en TYPE_REFERENCE avec type attendu, chaque
     *       FQN est résolu contre la portée du marqueur (portage
     *       {@code resolveType}) pour le bonus assignable.</li>
     * </ul>
     */
    private List<CompletionItem> typeCompletions(Context ctx,
                                                 EcjPrefixMatcher matcher,
                                                 String source, Set<String> taken) {
        List<CompletionItem> out = new ArrayList<>();
        String prefix = matcher.prefix();
        if (prefix.isEmpty()) return out;
        // Package du fichier courant — les types DU paquet résolvent déjà
        // sans import.
        String currentPackage = packageOf(source);
        java.util.Set<String> importedFqns = new HashSet<>();
        java.util.Set<String> importedPackages = new HashSet<>();
        parseImports(source, importedFqns, importedPackages);
        try {
            var index = engine.typeIndex();
            for (String fqn : index.findTypesByPrefix(matcher, 60)) {
                String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
                String pkg = fqn.lastIndexOf('.') > 0
                        ? fqn.substring(0, fqn.lastIndexOf('.')) : "";
                if (taken != null && taken.contains(simple)) continue;
                // Auto-import : seulement si le type n'est PAS déjà visible
                // (java.lang auto-importé, paquet courant, import explicite,
                // import on-demand du paquet).
                boolean visible = "java.lang".equals(pkg)
                        || pkg.equals(currentPackage)
                        || importedFqns.contains(fqn)
                        || importedPackages.contains(pkg);
                // Collision : un import explicite d'AUTRE FQN même simple
                // name → insertion FQN, jamais d'auto-import (sinon la
                // complétion produirait un doublon de nom qui ne compile
                // pas — portage unimportedTypeCandidate).
                boolean collides = importedFqns.stream().anyMatch(imp ->
                        !imp.equals(fqn)
                                && imp.substring(imp.lastIndexOf('.') + 1)
                                        .equals(simple));
                TypeBinding resolved = null;
                // Résolution dès que la portée existe (pas seulement
                // pour le classement par type attendu) : le binding résolu
                // donne le VRAI kind du candidat — une ANNOTATION voyage en
                // Class+data="annotation" (badge « @ »), une INTERFACE en
                // Interface (badge « I »), un ENUM en Enum (badge « E ») —
                // au lieu d'un CLASS uniforme pour tout l'index.
                if (ctx != null && ctx.typeScope != null) {
                    resolved = resolveType(ctx.typeScope, fqn);
                }
                int score = rankScore(matcher, simple, PROX_UNIMPORTED_TYPE,
                        resolved, ctx != null ? ctx.expectedType : null,
                        false, false);
                String insert = collides ? fqn : simple;
                String autoImport = (visible || collides) ? null : fqn;
                CompletionItem.Kind kind = CompletionItem.Kind.CLASS;
                if (resolved instanceof ReferenceBinding rb) {
                    kind = kindOfType(rb);
                }
                out.add(new CompletionItem(simple,
                        kind, fqn, null, insert,
                        relevance(score), autoImport));
            }
        } catch (Throwable ignored) {
            // Index indisponible — aucune proposition de type.
        }
        return out;
    }

    /**
     * Résout un FQN contre la portée du marqueur (portage
     * {@code resolveType} de CandidateCollector) : sert au classement par
     * type attendu ({@code List x = new Array|} → ArrayList boosté).
     */
    private static TypeBinding resolveType(Scope scope, String fqn) {
        try {
            String[] parts = fqn.split("\\.");
            char[][] toks = new char[parts.length][];
            for (int i = 0; i < parts.length; i++) {
                toks[i] = parts[i].toCharArray();
            }
            TypeBinding t = scope.getType(toks, toks.length);
            return t != null && t.isValidBinding() ? t : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Extrait les imports du fichier : FQN explicites
     * ({@code import a.b.C;}) et paquets on-demand ({@code import a.b.*;}).
     */
    private static void parseImports(String source,
                                     java.util.Set<String> fqns,
                                     java.util.Set<String> packages) {
        Matcher m = IMPORT_PATTERN.matcher(source);
        while (m.find()) {
            String target = m.group(1);
            if (target == null || target.isEmpty()) continue;
            if (target.endsWith(".*")) {
                packages.add(target.substring(0, target.length() - 2));
            } else {
                fqns.add(target);
            }
        }
    }

    private static final java.util.regex.Pattern IMPORT_PATTERN =
            java.util.regex.Pattern.compile(
                    "^\\s*import\\s+(?:static\\s+)?([\\w.]+(?:\\.\\*)?)\\s*;",
                    java.util.regex.Pattern.MULTILINE);

    /** @return le nom qualifié du paquet du fichier, ou "" si absent. */
    private static String packageOf(String source) {
        if (source == null) return "";
        Matcher m = PACKAGE_DECL_PATTERN.matcher(source);
        return m.find() ? m.group(1) : "";
    }
    private static final java.util.regex.Pattern PACKAGE_DECL_PATTERN =
            java.util.regex.Pattern.compile(
                    "^\\s*package\\s+([A-Za-z_][\\w.]*)\\s*;",
                    java.util.regex.Pattern.MULTILINE);

    /**
     * Mots-clés filtrés par le matcher gradué — extension CodeIDE assumée
     * (projet de référence n'a pas de mots-clés dans son contributeur JDT) : le
     * boost « préfixe tapé » via {@link #KEYWORD_PREFIX_BONUS} place
     * « import » au-dessus des types quand un préfixe est tapé.
     */
    private static List<CompletionItem> keywordCompletions(
            EcjPrefixMatcher matcher) {
        List<CompletionItem> out = new ArrayList<>();
        String prefix = matcher.prefix();
        for (String kw : KEYWORDS) {
            if (!matcher.matches(kw)) continue;
            int score = rankScore(matcher, kw, PROX_KEYWORD, null, null,
                    false, false);
            if (!prefix.isEmpty()) {
                score -= KEYWORD_PREFIX_BONUS;
            }
            out.add(new CompletionItem(kw, CompletionItem.Kind.KEYWORD,
                    null, null, kw, relevance(score)));
        }
        return out;
    }

    // ─── Candidats (portage de CandidateCollector/InternalMembers) ────
    // NOTE : importCompletions a été absorbé par packageChildren
    // (cf. collectCandidates), qui filtre les types directs par le préfixe.

    /**
     * Membres accessibles du type du receveur (MEMBER_ACCESS) — portage
     * {@code members()} de CandidateCollector.
     */
    private static List<CompletionItem> members(Context ctx,
                                                EcjPrefixMatcher matcher,
                                                boolean appendParens) {
        List<CompletionItem> out = new ArrayList<>();
        ReferenceBinding type = ctx.type;
        if (type == null) return out;

        ReferenceBinding from = ctx.enclosingType != null
                ? ctx.enclosingType : ctx.type;
        boolean samePackage = samePackage(type, from);
        boolean onInstance = !ctx.staticQualifier;

        Set<String> seen = new HashSet<>();
        Set<String> visitedTypes = new HashSet<>();
        visitMembers(type, from, samePackage, ctx.staticQualifier, matcher,
                appendParens, out, seen, visitedTypes, ctx.expectedType);
        return out;
    }

    /**
     * Marche le graphe des super-types en collectant méthodes + champs.
     *
     * <p>Proximité par MEMBRE (portage {@code proximity(declaring,
     * from)} du projet de référence) : OWN_MEMBER si déclaré sur le type courant,
     * INHERITED sinon, OBJECT_MEMBER pour les membres de java.lang.Object ;
     * démotion statique-via-INSTANCE (+200 quand le qualifier est une
     * INSTANCE et le membre STATIC — l'appel de statique via TYPE, le bon
     * style, n'est PAS pénalisé) ; types imbriqués filtrés par
     * accessibilité (projet de référence cache les types imbriqués privés).</p>
     */
    private static void visitMembers(ReferenceBinding type, ReferenceBinding from,
                                     boolean samePackage, boolean staticQualifier,
                                     EcjPrefixMatcher matcher,
                                     boolean appendParens,
                                     List<CompletionItem> out, Set<String> seen,
                                     Set<String> visitedTypes,
                                     TypeBinding expected) {
        if (type == null || !visitedTypes.add(keyOf(type))) return;
        boolean ownType = from != null
                && keyOf(type).equals(keyOf(from));
        try {
            for (MethodBinding m : type.methods()) {
                if (m == null || m.isConstructor() || m.isSynthetic()) continue;
                if (staticQualifier && !m.isStatic()) continue;
                if (!accessible(m.isPrivate(), m.isPublic(), m.isProtected(),
                        m.declaringClass, from, samePackage)) {
                    continue;
                }
                String name = String.valueOf(m.selector);
                // Dédup PAR NOM : chaque surcharge
                // reste visible via le détail de l'item.
                if (!matcher.matches(name) || !seen.add("m:" + name)) {
                    continue;
                }
                int prox = proximityOf(type, ownType);
                boolean discouraged = !staticQualifier && m.isStatic();
                out.add(methodItem(m, appendParens, discouraged, prox,
                        expected, matcher));
            }
            for (FieldBinding f : type.fields()) {
                if (f == null || f.isSynthetic()) continue;
                if (staticQualifier && !f.isStatic()) continue;
                if (!accessible(f.isPrivate(), f.isPublic(), f.isProtected(),
                        f.declaringClass, from, samePackage)) {
                    continue;
                }
                String name = String.valueOf(f.name);
                if (!matcher.matches(name) || !seen.add("f:" + name)) continue;
                int prox = proximityOf(type, ownType);
                boolean discouraged = !staticQualifier && f.isStatic();
                out.add(fieldItem(f, discouraged, prox, expected, matcher));
            }
            // Types imbriqués : seulement via un qualifier de TYPE (Map.Entry)
            // — et accessibles seulement (projet de référence cache les types
            // imbriqués privés).
            if (staticQualifier) {
                for (ReferenceBinding nested : type.memberTypes()) {
                    if (nested == null) continue;
                    String name = String.valueOf(nested.sourceName());
                    if (!matcher.matches(name) || !seen.add("t:" + name)) continue;
                    if (!accessible(nested.isPrivate(), nested.isPublic(),
                            nested.isProtected(), nested, from, samePackage)) {
                        continue;
                    }
                    int score = rankScore(matcher, name, PROX_NESTED_TYPE,
                            null, expected, false, isDeprecated(nested));
                    out.add(new CompletionItem(name, kindOfType(nested),
                            EcjCompilerEngine.readable(nested), null, name,
                            relevance(score)));
                }
            }
        } catch (Throwable ignored) {
            // Énumération best-effort.
        }
        try {
            // samePackage DOIT être recalculé pour la récursion sur
            // superclass/superInterfaces : le propager tel quel (celui du
            // type dérivé) autoriserait à tort l'accès aux membres
            // package-private d'une superclasse située dans un AUTRE paquet.
            // On recalcule donc samePackage entre chaque super-type et le
            // type « from » (le caller).
            ReferenceBinding sup = type.superclass();
            if (sup != null) {
                boolean supSamePkg = samePackage(sup, from);
                visitMembers(sup, from, supSamePkg, staticQualifier,
                        matcher, appendParens, out, seen, visitedTypes, expected);
            }
            for (ReferenceBinding itf : type.superInterfaces()) {
                if (itf == null) continue;
                boolean itfSamePkg = samePackage(itf, from);
                visitMembers(itf, from, itfSamePkg, staticQualifier, matcher,
                        appendParens, out, seen, visitedTypes, expected);
            }
        } catch (Throwable ignored) {
            // Hiérarchie incomplète (bindings partiels).
        }
    }

    /**
     * Proximité d'un type déclarant (portage
     * {@code proximity(declaring, from)} du projet de référence) : OWN_MEMBER sur le
     * type courant, OBJECT_MEMBER sur java.lang.Object, INHERITED sinon.
     */
    private static int proximityOf(ReferenceBinding declaring, boolean ownType) {
        if (isObjectBinding(declaring)) return PROX_OBJECT_MEMBER;
        return ownType ? PROX_OWN_MEMBER : PROX_INHERITED;
    }

    /** @return true si le binding est java.lang.Object (erasure). */
    private static boolean isObjectBinding(ReferenceBinding t) {
        if (t == null) return false;
        try {
            return "java.lang.Object".equals(String.valueOf(t.readableName()));
        } catch (Throwable x) {
            return false;
        }
    }

    /** Noms en portée (NAME_REFERENCE) : locaux + membres du type englobant. */
    private static List<CompletionItem> names(Context ctx,
                                              EcjPrefixMatcher matcher,
                                              boolean appendParens) {
        Map<String, CompletionItem> out = new LinkedHashMap<>();

        // Locales (LOCAL) / paramètres (PARAMETER) — le plus proche du caret.
        for (LocalVar v : ctx.locals) {
            if (matcher.matches(v.name())) {
                int prox = v.parameter() ? PROX_PARAMETER : PROX_LOCAL;
                int score = rankScore(matcher, v.name(), prox, v.type(),
                        ctx.expectedType, false, false);
                out.putIfAbsent("v:" + v.name(), new CompletionItem(
                        v.name(), kindOfVar(v.parameter(), v.type()),
                        EcjCompilerEngine.display(v.type()), null, v.name(),
                        relevance(score)));
            }
        }

        ReferenceBinding from = ctx.type;
        if (from != null) {
            Set<String> seen = new HashSet<>();
            List<CompletionItem> memberItems = new ArrayList<>();
            visitNames(from, ctx.inStaticContext, matcher, appendParens,
                    memberItems, seen, new HashSet<>(), true,
                    ctx.expectedType);
            for (CompletionItem item : memberItems) {
                out.putIfAbsent(item.kind() + ":" + item.label(), item);
            }
        }
        return new ArrayList<>(out.values());
    }

    private static void visitNames(ReferenceBinding type, boolean staticContext,
                                   EcjPrefixMatcher matcher,
                                   boolean appendParens,
                                   List<CompletionItem> out, Set<String> seen,
                                   Set<String> visited, boolean ownType,
                                   TypeBinding expected) {
        if (type == null || !visited.add(keyOf(type))) return;
        int prox = proximityOf(type, ownType);
        try {
            for (MethodBinding m : type.methods()) {
                if (m == null || m.isConstructor() || m.isSynthetic()) continue;
                if (staticContext && !m.isStatic()) continue;
                String name = String.valueOf(m.selector);
                // Dédup par nom (idem members()).
                if (!matcher.matches(name) || !seen.add("m:" + name)) {
                    continue;
                }
                out.add(methodItem(m, appendParens, false, prox, expected,
                        matcher));
            }
            for (FieldBinding f : type.fields()) {
                if (f == null || f.isSynthetic()) continue;
                if (staticContext && !f.isStatic()) continue;
                String name = String.valueOf(f.name);
                if (!matcher.matches(name) || !seen.add("f:" + name)) continue;
                out.add(fieldItem(f, false, prox, expected, matcher));
            }
        } catch (Throwable ignored) {
            // Best-effort.
        }
        try {
            visitNames(type.superclass(), staticContext, matcher, appendParens,
                    out, seen, visited, false, expected);
            for (ReferenceBinding itf : type.superInterfaces()) {
                visitNames(itf, staticContext, matcher, appendParens, out, seen,
                        visited, false, expected);
            }
        } catch (Throwable ignored) {
            // Hiérarchie incomplète.
        }
    }

    // ─── Fabriques d'items ─────────────────────────────────────────────

    // ═══ Scoring UNIFIÉ : portage 1:1 du CompletionRanker du projet de référence ═══
    // ═══ (score interne plus bas = mieux ; pertinence publique inversée) ═══
    //
    // Score interne (PLUS BAS = mieux) : s = 1000 - bonusMatch
    //   + proximité + discouraged(200) + deprecated(400) - assignable(300).
    // La pertinence publique (plus HAUTE = mieux, API CompletionItem)
    // inverse ce score : relevance = RANK_BASE - s.
    //
    // Bonus de match (qualité de frappe) :
    //   -500 exact, -200 startsWith, -120 startsWith insensible.
    // Proximités (portage Proximity.kt) :
    //   LOCAL 0 < PARAMETER 8 < OWN_MEMBER 25 < NESTED_TYPE 35 < PACKAGE 40
    //   < INHERITED 55 < OBJECT_MEMBER 180 < UNIMPORTED_TYPE 300.
    // Tri final : score -> longueur du nom -> nom (portage compareBy).

    private static final int PROX_LOCAL = 0;
    private static final int PROX_PARAMETER = 8;
    private static final int PROX_OWN_MEMBER = 25;
    private static final int PROX_NESTED_TYPE = 35;
    private static final int PROX_PACKAGE = 40;
    private static final int PROX_INHERITED = 55;
    private static final int PROX_OBJECT_MEMBER = 180;
    private static final int PROX_UNIMPORTED_TYPE = 300;
    /** Extension CodeIDE (projet de référence n'a pas de mots-clés) : sous tout. */
    private static final int PROX_KEYWORD = 350;
    /** Mots-clés boostés quand un préfixe est tapé — « imp » place
     *  « import » au-dessus des types ImportSnippet. */
    private static final int KEYWORD_PREFIX_BONUS = 150;

    /** Base du score interne — les bonus y sont soustraits, les pénalités
     *  et la proximité y sont ajoutées (plus bas = mieux). */
    private static final int MATCH_BASE = 1000;

    /** Bonus de qualité de frappe (soustraits du score interne). */
    private static final int BONUS_MATCH_EXACT = 500;
    private static final int BONUS_MATCH_PREFIX = 200;
    private static final int BONUS_MATCH_PREFIX_CI = 120;
    private static final int BONUS_MATCH_HUMP = 60;
    /** Faiblement boosté : apparaît en bas de liste, sans bruit excessif
     *  pour les préfixes de 3 caractères et plus. */
    private static final int BONUS_MATCH_SUBSTRING = 30;

    /** Affinité avec le type attendu (assignable à l'erasure près). */
    private static final int BONUS_ASSIGNABLE = 300;

    /** Pénalités (ajoutées au score interne). */
    private static final int PENALTY_DISCOURAGED = 200;
    private static final int PENALTY_DEPRECATED = 400;

    /** Inversion score interne -> pertinence publique (desc). */
    private static final int RANK_BASE = 2000;

    /**
     * Score interne d'un candidat — portage exact de
     * {@code CompletionRanker.score} (projet de référence).
     */
    private static int rankScore(EcjPrefixMatcher matcher, String name,
                                 int proximity, TypeBinding candidateType,
                                 TypeBinding expected, boolean discouraged,
                                 boolean deprecated) {
        int s = MATCH_BASE;
        // Le scoring délègue au matcher pour déterminer le grade
        // (EXACT/PREFIX/PREFIX_CI/HUMP/SUBSTRING), puis applique le bonus
        // correspondant — les 5 grades du matcher doivent TOUS compter
        // (pas seulement 3, sinon HUMP et SUBSTRING seraient ignorés).
        EcjPrefixMatcher.Grade grade = matcher.grade(name);
        if (grade == null) {
            // Pas de match — score très élevé (le candidat ne devrait pas
            // arriver ici car matches() filtre déjà, mais par sécurité).
            return Integer.MAX_VALUE;
        }
        switch (grade) {
            case EXACT:     s -= BONUS_MATCH_EXACT; break;
            case PREFIX:    s -= BONUS_MATCH_PREFIX; break;
            case PREFIX_CI: s -= BONUS_MATCH_PREFIX_CI; break;
            case HUMP:      s -= BONUS_MATCH_HUMP; break;
            case SUBSTRING: s -= BONUS_MATCH_SUBSTRING; break;
        }
        if (expected != null && candidateType != null
                && assignableErasure(candidateType, expected)) {
            s -= BONUS_ASSIGNABLE;
        }
        s += proximity;
        if (discouraged) s += PENALTY_DISCOURAGED;
        if (deprecated) s += PENALTY_DEPRECATED;
        return s;
    }

    /** @return la pertinence publique (plus haute = mieux). */
    private static int relevance(int internalScore) {
        return RANK_BASE - internalScore;
    }

    /**
     * Compatibilité au niveau ERASURE — portage de {@code assignable} de
     * CompletionRanker : un signal de classement, pas un check de types.
     * {@code new ArrayList<…>} doit ranker pour un {@code List<String>}
     * attendu ; l'erasure capture l'intention (ArrayList sous List).
     */
    private static boolean assignableErasure(TypeBinding from, TypeBinding to) {
        try {
            TypeBinding f = from.erasure();
            TypeBinding t = to.erasure();
            if (f != null && t != null && f.isCompatibleWith(t)) return true;
            return String.valueOf(from.readableName())
                    .equals(String.valueOf(to.readableName()));
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean startsWithIgnoreCase(String s, String p) {
        return s.length() >= p.length()
                && s.regionMatches(true, 0, p, 0, p.length());
    }

    /**
     * Comparateur de tri final — portage du
     * {@code compareBy(score, name.length, name)} de CompletionRanker :
     * pertinence décroissante, puis nom le plus court, puis ordre
     * alphabétique (stabilité totale, parité projet de référence exacte).
     */
    static final Comparator<CompletionItem> RELEVANCE_ORDER =
            Comparator.comparingInt(CompletionItem::relevance).reversed()
                    .thenComparingInt(it -> it.label().length())
                    .thenComparing(CompletionItem::label);

    /**
     * Fabrique méthode avec scoring unifié. {@code proximity} suit la
     * position de déclaration (OWN_MEMBER/INHERITED/OBJECT_MEMBER) ;
     * {@code discouraged} porte la démotion statique-via-instance
     * (portage CandidateCollector : +200 quand le qualifier est une
     * INSTANCE et le membre STATIC).
     */
    private static CompletionItem methodItem(MethodBinding m, boolean appendParens,
                                             boolean discouraged, int proximity,
                                             TypeBinding expected,
                                             EcjPrefixMatcher matcher) {
        String name = String.valueOf(m.selector);
        StringBuilder detail = new StringBuilder();
        detail.append(EcjCompilerEngine.display(m.returnType)).append(' ')
                .append(name).append('(');
        for (int i = 0; i < m.parameters.length; i++) {
            if (i > 0) detail.append(", ");
            detail.append(EcjCompilerEngine.display(m.parameters[i]));
        }
        detail.append(')');
        if (m.declaringClass != null) {
            detail.append(" — ").append(EcjCompilerEngine.readable(m.declaringClass));
        }

        String insert = appendParens && m.parameters.length > 0
                ? name + "()" : name;
        boolean dep = isDeprecated(m);
        if (dep) {
            detail.append(" (déprécié)");
        }
        int score = rankScore(matcher, name, proximity,
                m.returnType, expected, discouraged, dep);
        return new CompletionItem(name, CompletionItem.Kind.METHOD,
                detail.toString(), null, insert, relevance(score));
    }

    /**
     * Fabrique champs avec scoring unifié (proximité +
     * démotion statique-via-instance + affinité type attendu + déprécié).
     */
    private static CompletionItem fieldItem(FieldBinding f, boolean discouraged,
                                            int proximity, TypeBinding expected,
                                            EcjPrefixMatcher matcher) {
        String name = String.valueOf(f.name);
        String detail = EcjCompilerEngine.display(f.type);
        if (f.declaringClass != null) {
            detail += " — " + EcjCompilerEngine.readable(f.declaringClass);
        }
        boolean dep = isDeprecated(f);
        if (dep) {
            detail += " (déprécié)";
        }
        int score = rankScore(matcher, name, proximity,
                f.type, expected, discouraged, dep);
        return new CompletionItem(name, CompletionItem.Kind.FIELD,
                detail, null, name, relevance(score));
    }

    // ─── Poids précalculés (pattern projet de référence) ────────────────────────

    /** @return true si le binding est {@code @Deprecated} (best-effort). */
    private static boolean isDeprecated(Object b) {
        if (b == null) return false;
        try {
            if (b instanceof MethodBinding m) return m.isDeprecated();
            if (b instanceof FieldBinding f) return f.isDeprecated();
            if (b instanceof ReferenceBinding t) return t.isDeprecated();
        } catch (Throwable ignored) {
            // Binding binaire incomplet — traité comme non déprécié.
        }
        return false;
    }


    private static CompletionItem.Kind kindOfType(ReferenceBinding t) {
        // isAnnotationType() AVANT isInterface() : un type
        // annotation EST une interface pour ecj (il étend Annotation) —
        // l'ordre inverse classerait toute annotation en INTERFACE.
        try {
            if (t.isAnnotationType()) return CompletionItem.Kind.ANNOTATION;
        } catch (Throwable ignored) {
            // Binding binaire — l'information peut manquer.
        }
        if (t.isInterface()) return CompletionItem.Kind.INTERFACE;
        if (t.isEnum()) return CompletionItem.Kind.ENUM;
        return CompletionItem.Kind.CLASS;
    }

    /** Paramètres distingués des locales (kind LSP PARAMETER). */
    private static CompletionItem.Kind kindOfVar(boolean parameter, TypeBinding t) {
        return parameter ? CompletionItem.Kind.PARAMETER
                : CompletionItem.Kind.VARIABLE;
    }

    // ─── Règles d'accès (portage d'InternalMembers) ────────────────────

    private static boolean accessible(boolean isPrivate, boolean isPublic,
                                       boolean isProtected,
                                       ReferenceBinding declaring,
                                       ReferenceBinding from, boolean samePackage) {
        if (from != null && declaring != null
                && keyOf(declaring).equals(keyOf(from))) {
            return true; // même classe
        }
        // Règle JLS 6.6.1 : une classe imbriquée (nested)
        // peut accéder aux membres privés de TOUTES ses classes englobantes
        // (enclosing types). Si le membre est privé et que « from » est une
        // nested class, on remonte la chaîne des enclosing types pour voir
        // si « declaring » est l'une d'entre elles — sinon, un isPrivate →
        // false immédiat casserait l'accès aux membres privés de l'outer
        // class depuis une inner class (pattern builder, itérateurs, etc.).
        if (isPrivate) {
            if (from != null && declaring != null) {
                try {
                    ReferenceBinding current = from;
                    while (current != null) {
                        if (keyOf(declaring).equals(keyOf(current))) {
                            return true; // accès autorisé via enclosing type
                        }
                        current = current.enclosingType();
                    }
                } catch (Throwable t) {
                    // best-effort — on retourne false.
                }
            }
            return false;
        }
        if (isPublic) return true;
        if (isProtected) {
            if (samePackage) return true;
            try {
                return from != null && declaring != null
                        && from.isCompatibleWith(declaring);
            } catch (Throwable t) {
                return false;
            }
        }
        return samePackage; // package-private
    }

    private static boolean samePackage(TypeBinding a, TypeBinding b) {
        if (a == null || b == null) return false;
        try {
            return String.valueOf(a.qualifiedPackageName())
                    .equals(String.valueOf(b.qualifiedPackageName()));
        } catch (Throwable t) {
            return false;
        }
    }

    private static String keyOf(ReferenceBinding t) {
        try {
            return String.valueOf(t.readableName());
        } catch (Throwable e) {
            return String.valueOf(System.identityHashCode(t));
        }
    }

    /** Matching gradué — portage du PrefixMatcher du projet de référence
     *  (prefix / prefix-ci / camel-hump / substring >= 3). */
    private static boolean matches(String name, String prefix) {
        return new EcjPrefixMatcher(prefix).matches(name);
    }

    // ─── Helpers de splice (portage de JdtCompletionService) ──────────

    /** Préfixe identifiant sous le caret. */
    static String identifierPrefix(String text, int offset) {
        int i = offset;
        while (i > 0 && (Character.isLetterOrDigit(text.charAt(i - 1))
                || text.charAt(i - 1) == '_' || text.charAt(i - 1) == '$')) {
            i--;
        }
        return text.substring(i, offset);
    }

    /** true si le premier caractère non-blanc avant start est un point. */
    private static boolean precededByDot(String text, int start) {
        int i = start - 1;
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) i--;
        return i >= 0 && text.charAt(i) == '.';
    }

    /** true si le prochain caractère non-blanc est `(` — une liste
     *  d'arguments existe déjà (pas de `()` à insérer). */
    private static boolean followedByArgList(String text, int offset) {
        int i = offset;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        return i < text.length() && text.charAt(i) == '(';
    }
}
