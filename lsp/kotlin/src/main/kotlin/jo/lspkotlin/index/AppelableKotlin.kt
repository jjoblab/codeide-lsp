package jo.lspkotlin.index

/**
 * Appelable de premier niveau d'une bibliothèque Kotlin du classpath
 * (K3, LSP_KOTLIN.md §4.1 « index ») — fonction ou propriété, simple ou
 * EXTENSION, décodée de l'annotation `@Metadata` (façade de fichier :
 * `XxxKt` — motif projet tiers de référence « KotlinCallableIndex à clés taggées »).
 *
 * Le périmètre est VOLONTAIREMENT restreint aux appelables de premier
 * niveau : les MEMBRES de classes restent dans la vue JVM de l'hôte
 * (ADR 0111 `java.members`, D4 réutilisation), qui couvre Java et
 * Android — l'index Kotlin ne comble que le VIDE Kotlin (top-level +
 * extensions, invisibles côté Java).
 *
 * @property genre fonction ou propriété.
 * @property nom nom appelé (`creer`).
 * @property paquet paquet du fichier porteur (`androidx.core.widget`).
 * @property recepteur FQCN du récepteur pour une EXTENSION
 *           (`android.widget.TextView`), `null` sinon — clé de la
 *           complétion membre `ext:`.
 * @property typeRetour type de retour rendu (Kotlin-ish, nom court —
 *           `String`, `List<View>`), `null` si inconnu.
 * @property parametres paramètres (nom + type rendu).
 * @property conteneur FQCN de la façade/fichier porteur
 *           (`androidx.core.widget.WidgetsKt`) — pour l'import
 *           auto-éditable côté hôte.
 */
public data class AppelableKotlin(
    public val genre: GenreAppelable,
    public val nom: String,
    public val paquet: String,
    public val recepteur: String?,
    public val typeRetour: String?,
    public val parametres: List<ParametreAppelable>,
    public val conteneur: String,
)

/** Genre d'appelable de premier niveau. */
public enum class GenreAppelable {
    /** `fun` de premier niveau (extension comprise). */
    FONCTION,

    /** `val`/`var` de premier niveau (extension comprise). */
    PROPRIETE,
}

/**
 * Paramètre d'un appelable (nom conservateur : null si absent des
 * métadonnées — signatures de sources décompilées).
 *
 * @property nom nom du paramètre.
 * @property type type rendu (nom court), `null` si non représentable.
 */
public data class ParametreAppelable(
    public val nom: String?,
    public val type: String?,
)

/** Clés taggées de l'index des appelables (motif projet tiers de référence, ADR 0111). */
public object ClesAppelables {
    /** Préfixe des clés d'appelables de premier niveau. */
    public const val PREFIXE_TOP: String = "top:"

    /** Préfixe des clés d'extensions (récepteur FQCN). */
    public const val PREFIXE_EXT: String = "ext:"

    /** Clé d'un appelable de premier niveau par nom. */
    public fun cleTop(nom: String): String = PREFIXE_TOP + nom

    /** Clé d'une extension par récepteur FQCN. */
    public fun cleExtension(recepteur: String): String = PREFIXE_EXT + recepteur
}
