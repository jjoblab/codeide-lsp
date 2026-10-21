package jo.lspjava.ecj;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Live templates en position d'instruction — abréviations qui s'étendent
 * sans récepteur : taper {@code sout} et accepter insère
 * {@code System.out.println();} avec le curseur à l'intérieur.
 *
 * <p>Contrepartie Java des postfix templates ({@link EcjPostfixTemplates}),
 * dans le style des IDE de bureau. Émise en position de nom/instruction (contexte
 * {@code NAME} du moteur de complétion), chaque abréviation devient un
 * candidat {@code SNIPPET} dont le texte inséré utilise la <strong>syntaxe
 * snippet LSP</strong> ({@code $0} = position finale du curseur,
 * {@code ${1:défaut}} = tab stop avec placeholder, un index répété =
 * arrêts liés/miroirs) — c'est l'éditeur client qui gère la navigation
 * entre tab stops, aucun mécanisme éditeur supplémentaire n'est requis.</p>
 *
 * <p>Pertinence : une clé pas encore entièrement tapée reste SOUS les
 * vrais symboles ({@link #RELEVANCE_TEMPLATE_PARTIEL}) ; une clé
 * entièrement tapée remonte en tête ({@link #RELEVANCE_TEMPLATE_EXACT})
 * — convention de signe inspirée du tier weigher du projet tiers de référence, adaptée
 * à l'échelle de pertinence de lspjava (plus HAUTE = mieux).</p>
 *
 * <p>Portage de {@code LiveTemplates.kt} (projet tiers de référence v3.20, module
 * lang-jdt) en syntaxe snippet LSP native.</p>
 *
 * @author jo@Dev
 */
public final class EcjLiveTemplates {

    /**
     * Pertinence d'une template dont la clé n'est pas encore entièrement
     * tapée — sous les vrais symboles (les valeurs réelles du moteur
     * culminent autour de 2000).
     */
    public static final int RELEVANCE_TEMPLATE_PARTIEL = 100;

    /** Pertinence d'une template dont la clé est entièrement tapée — en tête. */
    public static final int RELEVANCE_TEMPLATE_EXACT = 2500;

    private EcjLiveTemplates() {
        // Classe utilitaire — jamais instanciée.
    }

    /**
     * Les templates correspondant au préfixe tapé.
     *
     * @param prefix le préfixe d'identifiant courant (vide = caret nu ;
     *               on n'inonde PAS le popup à caret nu, il faut au moins
     *               un caractère tapé)
     * @return les templates applicables, dans l'ordre de la table
     */
    public static List<TemplateItem> itemsFor(String prefix) {
        List<TemplateItem> out = new ArrayList<>();
        if (prefix == null || prefix.isEmpty()) return out;
        String p = prefix.toLowerCase(Locale.ROOT);
        for (Template t : TEMPLATES) {
            if (!t.key.startsWith(p)) continue;
            out.add(new TemplateItem(t.key, t.snippet, t.preview, t.description,
                    t.key.equals(prefix)
                            ? RELEVANCE_TEMPLATE_EXACT
                            : RELEVANCE_TEMPLATE_PARTIEL));
        }
        return out;
    }

    /** Une template vivante prête à être convertie en item de complétion. */
    public record TemplateItem(
            /** Abréviation tapée (ex. {@code sout}) — sert d'étiquette. */
            String key,
            /** Texte inséré, en syntaxe snippet LSP (à mapper sur
             *  {@code insertText} + {@code insertTextFormat=Snippet}). */
            String snippet,
            /** Aperçu du résultat (ex. {@code System.out.println();}). */
            String preview,
            /** Description courte affichée en documentation. */
            String description,
            /** Pertinence lspjava (plus haute = mieux). */
            int relevance) {
    }

    /** Définition interne : clé, aperçu, description et expansion snippet LSP. */
    private record Template(String key, String preview, String description,
                            String snippet) {
    }

    private static final Template[] TEMPLATES = {
            new Template("sout", "System.out.println();",
                    "Impression sur la sortie standard",
                    "System.out.println($0);"),
            new Template("souf", "System.out.printf(\"\", );",
                    "Impression formatée",
                    "System.out.printf(\"${1:format}\", $0);"),
            new Template("soutv", "System.out.println(value);",
                    "Impression d'une valeur",
                    "System.out.println(${1:value});$0"),
            new Template("serr", "System.err.println();",
                    "Impression sur la sortie d'erreur",
                    "System.err.println($0);"),
            new Template("psvm", "public static void main(String[] args) { }",
                    "Méthode main",
                    "public static void main(String[] args) {\n    $0\n}"),
            new Template("psf", "private static final",
                    "Champ constant privé",
                    "private static final $0"),
            new Template("psfi", "private static final int NAME = ;",
                    "Constante int privée",
                    "private static final int ${1:NAME} = $0;"),
            new Template("psfs", "private static final String NAME = \"\";",
                    "Constante String privée",
                    "private static final String ${1:NAME} = \"$0\";"),
            new Template("fori", "for (int i = 0; i < n; i++) { }",
                    "Boucle for indexée",
                    "for (int ${1:i} = 0; ${1:i} < ${2:n}; ${1:i}++) {\n    $0\n}"),
            new Template("iter", "for (var item : iterable) { }",
                    "Boucle for-each",
                    "for (var ${1:item} : ${2:iterable}) {\n    $0\n}"),
            new Template("ife", "if (cond) { }",
                    "Instruction if",
                    "if (${1:cond}) {\n    $0\n}"),
            new Template("ifn", "if (o == null) { }",
                    "Test de nullité",
                    "if (${1:o} == null) {\n    $0\n}"),
            new Template("inn", "if (o != null) { }",
                    "Test de non-nullité",
                    "if (${1:o} != null) {\n    $0\n}"),
            new Template("whilet", "while (cond) { }",
                    "Boucle while",
                    "while (${1:cond}) {\n    $0\n}"),
            new Template("thr", "throw new RuntimeException();",
                    "Lever une exception",
                    "throw new ${1:RuntimeException}($0);"),
            new Template("try", "try { } catch (Exception e) { }",
                    "try/catch",
                    "try {\n    $0\n} catch (${1:Exception} ${2:e}) {\n}"),
    };
}
