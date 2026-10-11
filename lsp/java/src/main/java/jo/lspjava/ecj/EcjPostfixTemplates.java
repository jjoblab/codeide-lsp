package jo.lspjava.ecj;

import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeIds;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Templates postfixes de complétion Java — taper {@code expr.key} réécrit
 * TOUTE l'expression : {@code list.sout} devient
 * {@code System.out.println(list);}, {@code name.var} devient
 * {@code String name2 = name;} avec un tab stop sur le nom.
 *
 * <p>Elles apparaissent dans le popup ordinaire en position d'accès membre
 * (contexte {@code MEMBER} du moteur) : chaque template produit un item
 * {@code SNIPPET} dont le texte inséré EST la réécriture (syntaxe snippet
 * LSP : {@code $0} curseur final, {@code ${1:défaut}} tab stop lié), et
 * dont l'<em>additional text edit</em> supprime le texte
 * {@code expr.} d'origine. Aucun nouveau chemin éditeur — tout passe par
 * la complétion + les snippets natifs LSP.</p>
 *
 * <p>Les prédicats de type (booléen, tableau, itérable, référence…)
 * s'appuient sur les {@link TypeBinding} internes d'ecj déjà résolus par
 * le moteur de complétion — la même source de vérité que les membres
 * proposés, donc une template ne peut pas se proposer là où elle ne
 * compile pas.</p>
 *
 * <p>Portage de {@code PostfixTemplates.kt} (projet de référence v3.20, module
 * lang-jdt) en syntaxe snippet LSP native.</p>
 *
 * @author jo@Dev
 */
public final class EcjPostfixTemplates {

    /**
     * Pertinence d'une template dont la clé n'est pas entièrement tapée —
     * juste sous les vrais membres.
     */
    public static final int RELEVANCE_TEMPLATE_PARTIEL = 100;

    /** Pertinence d'une template dont la clé est entièrement tapée — en tête. */
    public static final int RELEVANCE_TEMPLATE_EXACT = 2500;

    private EcjPostfixTemplates() {
        // Classe utilitaire — jamais instanciée.
    }

    /**
     * Construit les items postfixes pour {@code receiver.prefix} au caret.
     *
     * @param text         le tampon vivant (source complète courante)
     * @param keyStart     début de la clé tapée (début de la plage de
     *                     remplacement de la complétion)
     * @param keyEnd       fin de la clé (= position du caret)
     * @param prefix       la clé tapée (peut être vide)
     * @param qualifierType le type résolu du récepteur, ou {@code null}
     * @param staticQualifier {@code true} si le récepteur est un nom de
     *                     type ({@code Foo.new})
     * @param importOffset l'offset d'insertion d'un auto-import (fin de
     *                     la dernière déclaration d'import), ou 0
     * @return les templates applicables ; vide quand il n'y a pas de
     *         récepteur {@code .} à réécrire
     */
    public static List<PostfixItem> itemsFor(
            String text, int keyStart, int keyEnd, String prefix,
            TypeBinding qualifierType, boolean staticQualifier,
            int importOffset) {
        Integer dot = dotBefore(text, keyStart);
        if (dot == null) return List.of();
        int recvStart = receiverStart(text, dot);
        if (recvStart >= dot) return List.of();
        String receiver = text.substring(recvStart, dot).trim();
        if (receiver.isEmpty()) return List.of();

        Ctx ctx = new Ctx(receiver, qualifierType, staticQualifier);
        // L'additional edit supprime « récepteur. » (la clé elle-même est
        // la plage de remplacement principale de la complétion).
        int deleteStart = recvStart;
        int deleteEnd = keyStart;

        List<PostfixItem> out = new ArrayList<>();
        for (Template t : TEMPLATES) {
            if (!matches(t.key, prefix)) continue;
            if (!t.applicable.test(ctx)) continue;
            String snippet = t.build.apply(ctx);
            if (snippet == null) continue;
            out.add(new PostfixItem(
                    t.key,
                    t.example.replace("%r", receiver),
                    t.description,
                    snippet,
                    deleteStart,
                    deleteEnd,
                    // Seule la template « opt » ajoute un import.
                    "opt".equals(t.key) ? "java.util.Optional" : null,
                    importOffset,
                    t.key.equals(prefix)
                            ? RELEVANCE_TEMPLATE_EXACT
                            : RELEVANCE_TEMPLATE_PARTIEL));
        }
        return out;
    }

    /** Un item postfixe prêt à être converti en item de complétion LSP. */
    public record PostfixItem(
            /** Clé postfixe (ex. {@code sout}) — sert d'étiquette. */
            String key,
            /** Aperçu du résultat, récepteur interpolé. */
            String example,
            /** Description courte affichée en documentation. */
            String description,
            /** Texte inséré, en syntaxe snippet LSP. */
            String snippet,
            /** Début de la plage à supprimer (le récepteur + le point). */
            int deleteStart,
            /** Fin de la plage à supprimer (= début de la clé tapée). */
            int deleteEnd,
            /** FQN à auto-importer, ou {@code null}. */
            String importFqn,
            /** Offset où insérer l'import (informationnel pour la couche LSP). */
            int importOffset,
            /** Pertinence lspjava (plus haute = mieux). */
            int relevance) {
    }

    // ─── Correspondance de préfixe ─────────────────────────────────────

    /**
     * Correspondance souple : le préfixe tapé est un préfixe insensible à
     * la casse de la clé de la template.
     */
    private static boolean matches(String key, String prefix) {
        return prefix.isEmpty()
                || key.toLowerCase(Locale.ROOT)
                        .startsWith(prefix.toLowerCase(Locale.ROOT));
    }

    // ─── Scan du récepteur (sur le tampon original) ────────────────────

    /**
     * Le {@code .} immédiatement avant {@code keyStart} (espaces sautés),
     * ou {@code null} si la clé n'est pas un accès membre.
     */
    private static Integer dotBefore(String text, int keyStart) {
        int i = keyStart - 1;
        while (i >= 0 && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i--;
        return i >= 0 && text.charAt(i) == '.' ? i : null;
    }

    /**
     * Début de l'expression récepteur qui se termine juste avant le
     * {@code dot}. Scan arrière sur un suffixe équilibré : identifiants,
     * points et groupes entre crochets ({@code )]/]} … {@code (/[}),
     * plus littéraux chaîne/caractère, en s'arrêtant au premier
     * opérateur/séparateur/crochet ouvrant à profondeur 0. Suffisant
     * pour les récepteurs courants ({@code a.b}, {@code foo()},
     * {@code arr[i]}, {@code "x"}, {@code a.b().c}) ; un opérateur
     * initial comme {@code !x.} ne conserve que {@code x}.
     */
    static int receiverStart(String text, int dot) {
        int i = dot - 1;
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) i--;
        int depth = 0;
        while (i >= 0) {
            char c = text.charAt(i);
            if (c == ')' || c == ']') {
                depth++;
                i--;
            } else if (c == '(' || c == '[') {
                if (depth == 0) return i + 1;
                depth--;
                i--;
            } else if (depth > 0) {
                i--;
            } else if (c == '"' || c == '\'') {
                // Remonter par-dessus un littéral entier.
                char q = c;
                i--;
                while (i >= 0 && !(text.charAt(i) == q
                        && (i == 0 || text.charAt(i - 1) != '\\'))) i--;
                i--;
            } else if (Character.isLetterOrDigit(c) || c == '_'
                    || c == '$' || c == '.') {
                i--;
            } else {
                return i + 1;
            }
        }
        return i + 1;
    }

    // ─── Prédicats de type sur les bindings ecj ────────────────────────

    /** Contexte de récepteur : texte, type résolu et statique du qualifier. */
    private static final class Ctx {
        final String receiver;
        final TypeBinding type;
        final boolean staticQualifier;

        Ctx(String receiver, TypeBinding type, boolean staticQualifier) {
            this.receiver = receiver;
            this.type = type;
            this.staticQualifier = staticQualifier;
        }

        boolean isBoolean() {
            if (type == null) return false;
            return type.id == TypeIds.T_boolean
                    || type.id == TypeIds.T_JavaLangBoolean;
        }

        boolean isPrimitive() {
            return type != null && type.isBaseType();
        }

        boolean isVoid() {
            return type != null && type.id == TypeIds.T_void;
        }

        boolean isArray() {
            return type != null && type.isArrayType();
        }

        boolean isInt() {
            if (type == null) return false;
            return type.id == TypeIds.T_int || type.id == TypeIds.T_short
                    || type.id == TypeIds.T_byte || type.id == TypeIds.T_long;
        }

        boolean isReference() {
            return type != null && !type.isBaseType();
        }

        boolean isIterable() {
            if (type == null) return false;
            if (type.isArrayType()) return true;
            return type instanceof ReferenceBinding rb
                    && superMatches(rb, "java.lang.Iterable");
        }

        /** Nom simple du type du récepteur pour une réécriture var/cast. */
        String typeName() {
            return simpleName(type);
        }
    }

    /**
     * Parcourt la hiérarchie (superclasse + superinterfaces) pour vérifier
     * que {@code fqn} y figure. Garde-fou anti-cycles par ensemble de noms.
     */
    private static boolean superMatches(ReferenceBinding t, String fqn) {
        Set<String> seen = new HashSet<>();
        return walk(t, fqn, seen);
    }

    private static boolean walk(ReferenceBinding r, String fqn, Set<String> seen) {
        if (r == null) return false;
        String name = String.valueOf(r.readableName());
        if (!seen.add(name)) return false;
        if (name.equals(fqn)) return true;
        try {
            if (walk(r.superclass(), fqn, seen)) return true;
            ReferenceBinding[] itfs = r.superInterfaces();
            if (itfs != null) {
                for (ReferenceBinding itf : itfs) {
                    if (walk(itf, fqn, seen)) return true;
                }
            }
        } catch (Throwable ignored) {
            // Hiérarchie binaire incomplète — considérée comme non trouvable.
        }
        return false;
    }

    /**
     * Nom d'affichage simple d'un binding : dernier segment hors package,
     * génériques conservés, crochets de tableau conservés.
     */
    static String simpleName(TypeBinding t) {
        if (t == null) return "var";
        try {
            String n = String.valueOf(t.readableName());
            int lt = n.indexOf('<');
            String head = lt >= 0 ? n.substring(0, lt) : n;
            String generics = lt >= 0 ? n.substring(lt) : "";
            int dot = head.lastIndexOf('.');
            String simple = dot >= 0 ? head.substring(dot + 1) : head;
            return simple + generics;
        } catch (Throwable ignored) {
            // Binding binaire illisible — repli neutre.
            return "var";
        }
    }

    /**
     * Suggère un nom de variable depuis le type : première lettre en
     * minuscule ({@code String} → {@code string}, {@code List} →
     * {@code list}), génériques et crochets retirés.
     */
    private static String nameFor(Ctx ctx) {
        String n = simpleName(ctx.type);
        int lt = n.indexOf('<');
        if (lt > 0) n = n.substring(0, lt);
        while (n.endsWith("[]")) n = n.substring(0, n.length() - 2);
        if (n.isEmpty() || !Character.isLetter(n.charAt(0))) return "value";
        return Character.toLowerCase(n.charAt(0)) + n.substring(1);
    }

    // ─── La table des templates ────────────────────────────────────────

    /** Prédicat d'applicabilité sur le contexte récepteur. */
    @FunctionalInterface
    private interface Applicable {
        boolean test(Ctx ctx);
    }

    /** Fabrique du texte snippet (syntaxe LSP), ou {@code null} pour renoncer. */
    @FunctionalInterface
    private interface Build {
        String apply(Ctx ctx);
    }

    private record Template(String key, String example, String description,
                            Applicable applicable, Build build) {
    }

    private static final Applicable ANY_VALUE = ctx -> !ctx.isVoid();

    private static final Template[] TEMPLATES = {
            new Template("sout", "System.out.println(%r);",
                    "Impression sur la sortie standard", ANY_VALUE,
                    ctx -> "System.out.println(" + ctx.receiver + ");$0"),
            new Template("serr", "System.err.println(%r);",
                    "Impression sur la sortie d'erreur", ANY_VALUE,
                    ctx -> "System.err.println(" + ctx.receiver + ");$0"),
            new Template("return", "return %r;",
                    "Retourner l'expression", ANY_VALUE,
                    ctx -> "return " + ctx.receiver + ";$0"),
            new Template("var", "Type name = %r;",
                    "Introduire une variable locale", ANY_VALUE,
                    ctx -> ctx.typeName() + " " + "${1:" + nameFor(ctx)
                            + "} = " + ctx.receiver + ";$0"),
            new Template("field", "this.name = %r;",
                    "Affecter à un champ", ANY_VALUE,
                    ctx -> "this." + "${1:" + nameFor(ctx) + "} = "
                            + ctx.receiver + ";$0"),
            new Template("par", "(%r)",
                    "Parenthéser l'expression", ANY_VALUE,
                    ctx -> "(" + ctx.receiver + ")$0"),
            new Template("not", "!(%r)",
                    "Nier un booléen", Ctx::isBoolean,
                    ctx -> "!(" + ctx.receiver + ")$0"),
            new Template("if", "if (%r) { }",
                    "Envelopper dans un if", Ctx::isBoolean,
                    ctx -> "if (" + ctx.receiver + ") {\n    $0\n}"),
            new Template("else", "if (!(%r)) { }",
                    "Envelopper dans un if inversé", Ctx::isBoolean,
                    ctx -> "if (!(" + ctx.receiver + ")) {\n    $0\n}"),
            new Template("while", "while (%r) { }",
                    "Envelopper dans un while", Ctx::isBoolean,
                    ctx -> "while (" + ctx.receiver + ") {\n    $0\n}"),
            new Template("null", "if (%r == null) { }",
                    "Test de nullité", Ctx::isReference,
                    ctx -> "if (" + ctx.receiver + " == null) {\n    $0\n}"),
            new Template("nn", "if (%r != null) { }",
                    "Test de non-nullité", Ctx::isReference,
                    ctx -> "if (" + ctx.receiver + " != null) {\n    $0\n}"),
            new Template("notnull", "if (%r != null) { }",
                    "Test de non-nullité", Ctx::isReference,
                    ctx -> "if (" + ctx.receiver + " != null) {\n    $0\n}"),
            new Template("for", "for (int i = 0; i < arr.length; i++)",
                    "Boucle indexée", ctx -> ctx.isArray() || ctx.isInt(),
                    ctx -> forIndexed(ctx)),
            new Template("fori", "for (int i = 0; i < n; i++)",
                    "Boucle indexée", ctx -> ctx.isArray() || ctx.isInt(),
                    EcjPostfixTemplates::forIndexed),
            new Template("iter", "for (var item : coll)",
                    "Itérer (for-each)", Ctx::isIterable,
                    ctx -> "for (var ${1:item} : " + ctx.receiver + ") {\n    $0\n}"),
            new Template("opt", "Optional.ofNullable(%r)",
                    "Envelopper dans un Optional", Ctx::isReference,
                    ctx -> "Optional.ofNullable(" + ctx.receiver + ")$0"),
            new Template("cast", "((Type) %r)",
                    "Caster l'expression", ANY_VALUE,
                    ctx -> "((${1:Type}) " + ctx.receiver + ")$0"),
            new Template("instanceof", "%r instanceof Type",
                    "Test instanceof", Ctx::isReference,
                    ctx -> ctx.receiver + " instanceof ${1:Type}$0"),
            new Template("new", "new Type()",
                    "Instancier le type", ctx -> ctx.staticQualifier,
                    ctx -> "new " + ctx.receiver + "($0)"),
    };

    /** Borne de la boucle indexée : {@code arr.length} ou l'entier lui-même. */
    private static String forIndexed(Ctx ctx) {
        String bound = ctx.isArray() ? ctx.receiver + ".length" : ctx.receiver;
        return "for (int ${1:i} = 0; ${1:i} < " + bound + "; ${1:i}++) {\n    $0\n}";
    }
}
