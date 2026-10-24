package jo.lspkotlin.api

/**
 * Une fonction `@Preview @Composable` du document (preview Compose C1 —
 * PREVIEW.md §4.4 : la découverte vit dans le moteur de langue, la
 * sémantique COMPOSE vit chez le consommateur ; ici, seul le modèle
 * NEUTRE traverse la frontière D6).
 *
 * La fonction porte ses paramètres d'annotation (`widthDp`,
 * `showBackground`…) en TEXTE BRUT de la source (l'interprétation des
 * valeurs appartient au rendu) et le CORPS réduit à un arbre
 * d'appels : chaque appel direct du bloc et du LAMBDA FINAL de
 * contenu devient un [AppelFonctionKotlin] ; toute autre expression
 * (locale, contrôle, opérateur) devient honnêtement
 * [ExpressionNonInterpreteeKotlin] — le rendu en tire une erreur
 * TYPIQUE, jamais un faux contenu.
 *
 * Les appels de COMPOSABLES DU PROJET sont INLINÉS dans leur propre
 * arbre d'appels (cycle et budgets coupés honnêtement —
 * [AppelFonctionKotlin.genreInline]) : l'arbre rendu est CLOS, le
 * processus de rendu n'a jamais à relire du Kotlin.
 *
 * @param nom nom de la fonction annotée (`ApercuAccueil`).
 * @param paquet package du document (`com.ex.app`), null si absent.
 * @param debutNom début INCLUSIF du jeton nom de la fonction.
 * @param finNom fin EXCLUSIVE du jeton nom.
 * @param parametres arguments de l'annotation `@Preview`, dans l'ordre
 *        écrit — chaque paramètre porte son nom (`widthDp`, `null`
 *        pour un positionnel) et sa valeur TRANCHÉE dans la source
 *        (`"100"`, `"true"` — guillemets conservés pour une chaîne).
 * @param corps l'arbre d'appels du corps (VIDE si le corps est
 *        absent/en édition — jamais null une fois la preview retenue).
 * @param coupureBudget vrai si la marche du corps a été TRONQUÉE par le
 *        budget de nœuds (garde-fou PC4 côté moteur) : le rendu en tire
 *        un bandeau TYPIQUE — jamais un faux rendu complet en silence.
 * @param ligneAnnotation ligne 1-INDEXÉE de l'annotation `@Preview`
 *        DANS LE DOCUMENT (le `@` — arguments multi-lignes compris, la
 *        ligne de départ reste l'entrée d'annotation ; écho
 *        `PreviewPickerLineMarkerProvider` de la référence : le marqueur
 *        de gouttière vit sur la ligne de `@Preview`, pas sur celle du
 *        `fun`). `0` = inconnu (annotation sans plage) — le consommateur
 *        renonce honnêtement à placer un marqueur pour CETTE preview.
 */
public data class PreviewKotlin(
    public val nom: String,
    public val paquet: String?,
    public val debutNom: Int,
    public val finNom: Int,
    public val parametres: List<ParametrePreviewKotlin>,
    public val corps: List<NoeudAppelKotlin>,
    public val coupureBudget: Boolean = false,
    public val ligneAnnotation: Int = 0,
)

/**
 * Un paramètre d'annotation `@Preview` (texte brut — le rendu décide
 * quoi en tirer).
 *
 * @param nom nom écrit (`widthDp`), `null` pour un argument positionnel.
 * @param valeurTexte valeur TRANCHÉE dans la source (`100`, `true`,
 *        `"spec:shape=Normal"` avec ses guillemets), `null` si
 *        l'argument n'a pas d'expression (édition en cours).
 */
public data class ParametrePreviewKotlin(
    public val nom: String?,
    public val valeurTexte: String?,
)

/**
 * Nœud de l'arbre d'appels neutre du corps d'une [PreviewKotlin] —
 * [AppelFonctionKotlin] (appel interprétable) ou
 * [ExpressionNonInterpreteeKotlin] (tout le reste, rendu en erreur
 * typée par le consommateur).
 */
public sealed interface NoeudAppelKotlin

/**
 * Un APPEL de fonction du corps (`Text("…")`, `Column { … }`,
 * `CarteProfil()`).
 *
 * @param nom nom de l'appelé (`Text`, `Column`, `CarteProfil`) —
 *        pour un appel qualifié `a.b(…)`, le nom du SÉLECTEUR (`b`).
 * @param recepteur texte du récepteur écrit (`Modifier`, `Color`) pour
 *        un appel qualifié, `null` pour un appel simple.
 * @param ligne ligne 1-indexée du début de l'appel (diagnostics de
 *        rendu côté preview).
 * @param arguments arguments EXPLICITES de l'appel : chaque argument
 *        porte son nom (si nommé), son genre (LITTÉRAL / RÉFÉRENCE /
 *        AUTRE) et sa valeur TRANCHÉE dans la source quand elle est
 *        lisible.
 * @param contenu le contenu du LAMBDA FINAL de l'appel
 *        (`Column { … }`), déjà marché — les lambdas passés en
 *        ARGUMENT (`onClick = { … }`) sont hors interprétation C1
 *        (comportement, pas contenu).
 * @param genreInline statut de l'inlining composable-projet :
 *        [GenreInlineKotlin.AUCUN] pour un appel de bibliothèque ;
 *        [GenreInlineKotlin.PROJET_INLINE] quand le corps du
 *        composable appelé est inliné dans [contenu] ; les autres
 *        valeurs rendent l'appel NON interprétable côté rendu
 *        (erreur typée honnête).
 */
public data class AppelFonctionKotlin(
    public val nom: String,
    public val recepteur: String?,
    public val ligne: Int,
    public val arguments: List<ArgumentAppelKotlin>,
    public val contenu: List<NoeudAppelKotlin>,
    public val genreInline: GenreInlineKotlin = GenreInlineKotlin.AUCUN,
) : NoeudAppelKotlin

/**
 * Statut d'inline d'un [AppelFonctionKotlin] vers un composable du
 * PROJET (les autres appels portent [AUCUN]).
 */
public enum class GenreInlineKotlin {
    /** Appel de bibliothèque ou inconnu — pas d'inlining attendu. */
    AUCUN,

    /** Composable du projet : corps inliné dans [AppelFonctionKotlin.contenu]. */
    PROJET_INLINE,

    /** Composable du projet en CYCLE (A → B → A) : non inliné. */
    PROJET_CYCLE,

    /** Budget d'interprétation dépassé (profondeur ou nœuds) : non inliné. */
    PROJET_BUDGET,

    /** Composable du projet PARAMÉTRÉ (les arguments ne traversent pas
     *  l'inline en C1) : non inliné. */
    PROJET_PARAMETRES,

    /** Corps du composable illisible/introuvable : non inliné. */
    PROJET_ILLISIBLE,
}

/**
 * Un argument explicite d'appel (`"Bonjour"`, `widthDp = 100`,
 * `color = MaterialTheme.colorScheme.primary`).
 *
 * @param nom nom écrit AVANT `=` (`widthDp`), `null` pour positionnel.
 * @param genre LITTÉRAL (chaîne, nombre, booléen, null), RÉFÉRENCE
 *        (identifiant nu `Color.Red` ou sélecteur simple), AUTRE
 *        (lambda, opérateur, appel imbriqué — non interprétable C1).
 * @param texte valeur TRANCHÉE dans la source quand le nœud de valeur
 *        existe (`"Bonjour"` avec guillemets, `100`, `true`),
 *        `null` sinon.
 */
public data class ArgumentAppelKotlin(
    public val nom: String?,
    public val genre: GenreArgumentAppelKotlin,
    public val texte: String?,
)

/**
 * Genre de la valeur d'un [ArgumentAppelKotlin] — le rendu n'interprète
 * que les LITTÉRAUX en C1.
 */
public enum class GenreArgumentAppelKotlin {
    /** Chaîne, nombre, booléen, `null` — interprétable. */
    LITTERAL,

    /** Identifiant ou sélecteur nu (`Color.Red`, `nom`) — non interprété C1. */
    REFERENCE,

    /** Lambda, opérateur, appel imbriqué — non interprété C1. */
    AUTRE,
}

/**
 * Une expression du corps qui N'EST PAS un appel — locale
 * (`val x = …`), contrôle (`if`, `when`), opérateur, référence nue.
 * Le rendu en tire une erreur TYPIQUE (expression non interprétée),
 * jamais un faux contenu.
 *
 * @param texte expression TRANCHÉE dans la source (tronquée à 120
 *        caractères par la collecte).
 * @param ligne ligne 1-indexée du début.
 */
public data class ExpressionNonInterpreteeKotlin(
    public val texte: String,
    public val ligne: Int,
) : NoeudAppelKotlin
