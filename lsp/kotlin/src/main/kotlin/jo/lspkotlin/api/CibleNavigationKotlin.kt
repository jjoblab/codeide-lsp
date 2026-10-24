package jo.lspkotlin.api

/**
 * Une cible de navigation GO TO « Super » Kotlin (ADR 0210 — miroir
 * neutre du `NavigationTarget` du LSP Java : l'hôte traduit vers le DTO
 * de transport partagé).
 *
 * @property uri URI du fichier déclarant (`file://…`).
 * @property debutNom décalage de début du JETON nom de la cible
 *           (membre outrepassé dans le supertype, ou nom du supertype).
 * @property finNom fin (exclusif) du jeton nom.
 * @property libelle libellé picker (`« name  ·  Super »` pour un
 *           membre, `« Simple  ·  pkg »` pour un type — même
 *           convention que le moteur ecj du LSP Java).
 */
public data class CibleNavigationKotlin(
    public val uri: String,
    public val debutNom: Int,
    public val finNom: Int,
    public val libelle: String,
)
