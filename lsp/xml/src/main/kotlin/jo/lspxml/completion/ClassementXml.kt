package jo.lspxml.completion

/**
 * SCOREUR de complétion XML (XE2, risque §7 « complétion bruyante ») :
 * le classement (fréquence SDK, poids sémantique) et la CLÉ de tri
 * voyagent ici — un seul endroit à accorder quand le terrain dira.
 */
internal object ClassementXml {
    /**
     * Vues USUELLES d'abord (fréquence SDK — le candidat probable doit
     * rester en tête de palette même à préfixe égal).
     */
    internal val BALISES_FREQUCENTES: List<String> =
        listOf(
            "TextView", "Button", "ImageView", "EditText", "LinearLayout",
            "RelativeLayout", "FrameLayout", "CheckBox", "Switch",
            "RecyclerView", "ViewPager", "Spinner", "ImageButton",
        )

    /** Aperçu des énumérations/fanions dans le détail d'une proposition. */
    internal const val APERCU_VALEURS: Int = 6

    /** Rang de classement d'une RÉFÉRENCE projet (@type/nom, ?attr). */
    internal const val RANG_REFERENCE: Int = 20

    /** Rang de classement d'une référence FRAMEWORK (après projet). */
    internal const val RANG_REFERENCE_FW: Int = 30

    /** Rang de classement d'une BALISE de vue PROJET (après framework, avant courant). */
    internal const val RANG_VUE_PROJET: Int = 50

    /** Rang de classement d'une VALEUR d'énumération/fanion. */
    internal const val RANG_VALEUR: Int = 10

    /** Rang de classement d'une BALISE (fréquentes d'abord). */
    internal fun rangBalise(nom: String): Int {
        val rang = BALISES_FREQUCENTES.indexOf(nom)
        return if (rang >= 0) rang else RANG_COURANT
    }

    /** Rang de classement d'un ATTRIBUT (id, layout_*, reste, tools:). */
    internal fun rangAttribut(nom: String): Int =
        when {
            nom == "android:id" -> RANG_ID

            nom.startsWith("android:layout_") -> RANG_LAYOUT

            nom.startsWith("tools:") -> RANG_OUTILS

            else -> RANG_COURANT
        }

    /**
     * Clé de tri LSP (le client trie par sortText SEUL — la pertinence
     * décroissante, la longueur puis le libellé voyagent DANS la clé,
     * motif du LSP Java/Kotlin).
     */
    internal fun cleTri(classement: Int, libelle: String): String {
        val rang = classement.coerceAtLeast(0).coerceAtMost(PLAFOND_CLASSEMENT)
        return rang.toString().padStart(LARGEUR_CLASSEMENT, '0') +
            libelle.length.toString().padStart(LARGEUR_LONGUEUR, '0') +
            libelle
    }

    private const val RANG_ID: Int = 0

    private const val RANG_LAYOUT: Int = 1

    private const val RANG_OUTILS: Int = 80

    private const val RANG_COURANT: Int = 60

    private const val PLAFOND_CLASSEMENT: Int = 99

    private const val LARGEUR_CLASSEMENT: Int = 2

    private const val LARGEUR_LONGUEUR: Int = 4
}
