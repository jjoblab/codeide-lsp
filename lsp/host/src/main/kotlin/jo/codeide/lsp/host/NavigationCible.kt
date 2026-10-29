package jo.codeide.lsp.host

/**
 * Une cible de navigation multi-fichiers (GO TO Implementations / Super) :
 * le chemin ABSOLU du fichier déclarant, la plage du NOM de la déclaration,
 * et le libellé du picker.
 *
 * **Bascule moteur neuf (INTEGRATION-CODEIDE §3.2.2)** : autrefois le type
 * `jo.lspjava.api.NavigationTarget` de la façade vendord — le contrat de
 * SÉRIALISATION est repris À L'IDENTIQUE (champs publics `path`, `start`,
 * `end`, `label`) : la sérialisation Gson de LSP4J mappe les champs un à
 * un et le client `:cel-lsp` possède son miroir exact — le JSON sur le fil
 * est INCHANGÉ, aucune adaptation client n'est requise. Le type vit
 * désormais ICI (surface propre de l'hôte, la dépendance vendord est
 * retirée) : le LSP Kotlin l'emprunte pour son `superDefinition`, miroir
 * du contrat du serveur Java.
 *
 * @property path chemin absolu du fichier déclarant (sans préfixe `file://`).
 * @property start offset du NOM de la déclaration dans ce fichier.
 * @property end fin (exclusive) du nom.
 * @property label libellé picker (« Simple  ·  pkg » / « name  ·  Super »).
 */
public data class NavigationCible(
    public val path: String,
    public val start: Int,
    public val end: Int,
    public val label: String,
)
