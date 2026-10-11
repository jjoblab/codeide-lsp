package jo.lspjava.api;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Représente un élément de complétion proposé à l'utilisateur.
 *
 * <p>{@link #autoImportFqn} : un candidat de type hors {@code java.lang}
 * porte le FQN à importer à la validation. Le serveur LSP dérive alors un
 * {@code additionalTextEdits} avec l'insertion d'import (pattern
 * auto-import à l'acceptation de la complétion, comme Android Studio) —
 * l'utilisateur n'a pas à déclencher « Organiser les imports » séparément.</p>
 *
 * <p><strong>Items snippet (templates live/postfix) :</strong> un item
 * marqué {@link #snippet} porte un {@link #insertText} en <em>syntaxe
 * snippet LSP</em> ({@code $0}, {@code ${1:défaut}}, index répétés = arrêts
 * liés) — la couche LSP doit l'émettre avec
 * {@code insertTextFormat=Snippet}. Les templates postfixes réécrivent
 * l'expression réceptrice : {@link #additionalEdits} transporte la
 * suppression du récepteur (et l'insertion d'auto-import éventuelle) sous
 * forme d'éditions de texte décalées — jamais appliquées à la plage de
 * remplacement principale (contrainte LSP respectée par construction).</p>
 *
 * @author jo@Dev
 */
public final class CompletionItem {

    /** Les différentes catégories d'éléments de complétion. */
    public enum Kind {
        KEYWORD, CLASS, INTERFACE, ENUM, ANNOTATION,
        METHOD, CONSTRUCTOR, FIELD, VARIABLE,
        PARAMETER, TYPE_PARAMETER, PACKAGE, MODULE,
        SNIPPET, TEXT
    }

    /**
     * Édition de texte décalée appliquée à l'acceptation du candidat
     * (outside de la plage de remplacement principale) — format interne
     * transportable, converti en {@code TextEdit} LSP4J par le serveur.
     */
    public record TextEdit(int start, int end, String newText) {}

    private final String label;
    private final Kind kind;
    private final String detail;
    private final String documentation;
    private final String insertText;
    private final int relevance;
    /**
     * FQN à importer à l'acceptation du candidat (auto-import), ou null.
     * Le serveur LSP dérive un {@code additionalTextEdits} LSP4J avec
     * l'insertion {@code "import <fqn>;\n"} à la bonne position (après le
     * package et les imports existants). Exclu pour {@code java.lang.*}
     * (auto-importé par défaut en Java).
     */
    private final String autoImportFqn;
    /**
     * true si {@link #insertText} est en syntaxe snippet LSP
     * ({@code insertTextFormat=Snippet} côté protocole) — templates
     * live/postfix.
     */
    private final boolean snippet;
    /**
     * Éditions de texte supplémentaires (offsets de la source ORIGINALE),
     * appliquées à l'acceptation du candidat en plus du remplacement
     * principal — p.ex. la suppression du récepteur par une template
     * postfixe, ou l'insertion d'import qu'elle exige.
     */
    private final List<TextEdit> additionalEdits;

    /**
     * Crée un élément de complétion.
     *
     * @param label       le libellé affiché
     * @param kind        la catégorie
     * @param detail      détail supplémentaire (peut être null)
     * @param documentation documentation (peut être null)
     * @param insertText  le texte à insérer (peut être null, utilise label par défaut)
     * @param relevance   score de pertinence (plus élevé = plus pertinent)
     */
    public CompletionItem(String label, Kind kind, String detail, String documentation,
                          String insertText, int relevance) {
        this(label, kind, detail, documentation, insertText, relevance, null);
    }

    /**
     * Crée un élément de complétion avec auto-import.
     *
     * @param label       le libellé affiché
     * @param kind        la catégorie
     * @param detail      détail supplémentaire (peut être null)
     * @param documentation documentation (peut être null)
     * @param insertText  le texte à insérer (peut être null, utilise label par défaut)
     * @param relevance   score de pertinence (plus élevé = plus pertinent)
     * @param autoImportFqn FQN à importer à l'acceptation du candidat, ou null
     */
    public CompletionItem(String label, Kind kind, String detail, String documentation,
                          String insertText, int relevance, String autoImportFqn) {
        this(label, kind, detail, documentation, insertText, relevance,
                autoImportFqn, false, Collections.emptyList());
    }

    /**
     * Crée un élément de complétion complet (templates live/postfix).
     *
     * @param label       le libellé affiché
     * @param kind        la catégorie (SNIPPET pour une template)
     * @param detail      détail supplémentaire (peut être null)
     * @param documentation documentation (peut être null)
     * @param insertText  le texte à insérer — syntaxe snippet LSP si
     *                    {@code snippet=true}
     * @param relevance   score de pertinence (plus élevé = plus pertinent)
     * @param autoImportFqn FQN à importer à l'acceptation, ou null
     * @param snippet     true si insertText est en syntaxe snippet LSP
     * @param additionalEdits éditions supplémentaires (offsets source
     *                    originale), vide si aucune
     */
    public CompletionItem(String label, Kind kind, String detail, String documentation,
                          String insertText, int relevance, String autoImportFqn,
                          boolean snippet, List<TextEdit> additionalEdits) {
        this.label = Objects.requireNonNull(label, "label");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.detail = detail;
        this.documentation = documentation;
        this.insertText = insertText;
        this.relevance = relevance;
        this.autoImportFqn = autoImportFqn;
        this.snippet = snippet;
        this.additionalEdits = additionalEdits == null
                ? Collections.emptyList() : List.copyOf(additionalEdits);
    }

    /**
     * Crée un élément de complétion simple.
     *
     * @param label le libellé
     * @param kind  la catégorie
     */
    public CompletionItem(String label, Kind kind) {
        this(label, kind, null, null, null, 0);
    }

    /** @return le libellé */
    public String label() { return label; }

    /** @return la catégorie */
    public Kind kind() { return kind; }

    /** @return le détail */
    public String detail() { return detail; }

    /** @return la documentation */
    public String documentation() { return documentation; }

    /** @return le texte à insérer */
    public String insertText() { return insertText != null ? insertText : label; }

    /** @return le score de pertinence */
    public int relevance() { return relevance; }

    /**
     * @return le FQN à importer à l'acceptation du candidat (auto-import),
     *         ou null si aucun import n'est requis (java.lang.*, même package,
     *         non-type, etc.).
     */
    public String autoImportFqn() { return autoImportFqn; }

    /**
     * @return true si le texte inséré est en syntaxe snippet LSP
     *         (templates live/postfix) — à émettre avec
     *         {@code insertTextFormat=Snippet}
     */
    public boolean isSnippet() { return snippet; }

    /**
     * @return les éditions de texte supplémentaires (offsets source
     *         originale) appliquées à l'acceptation du candidat —
     *         suppression du récepteur / insertion d'import des templates
     *         postfixes ; vide pour les candidats ordinaires
     */
    public List<TextEdit> additionalEdits() { return additionalEdits; }

    @Override
    public String toString() {
        return "CompletionItem[" + label + " (" + kind + ")"
                + (snippet ? " snippet" : "")
                + (autoImportFqn != null ? " import:" + autoImportFqn : "")
                + (!additionalEdits.isEmpty()
                        ? " edits:" + additionalEdits.size() : "") + "]";
    }
}
