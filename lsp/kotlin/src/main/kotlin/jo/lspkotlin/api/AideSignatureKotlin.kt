package jo.lspkotlin.api

/**
 * Types NEUTRES de l'aide à la signature (`textDocument/signatureHelp`,
 * S1 — plan de stabilisation LSP §3.2) : le moteur n'expose AUCUN type
 * LSP4J (condition D6 du découplage « pack langue »), l'hôte traduit
 * vers le protocole.
 *
 * Le contrat rendu est le COUPLE rendu côté Java (`EcjSignatureHelp`) :
 * étiquettes et libellés de paramètres sont des TEXTES PRÊTS À
 * AFFICHER — l'hôte n'a RIEN à recomposer (le client éditeur ne lit
 * que la forme STRING des libellés de paramètres ; la forme plage
 * [début, fin] du protocole LSP est ignorée par l'éditeur cel).
 */

/**
 * Aide à la signature à [SessionLangageKotlin.signature] — l'appel
 * englobant le curseur, ses candidates et la position active.
 *
 * @property signatures les candidates de l'appel (projet d'abord,
 *           classpath ensuite) — NON VIDE quand l'aide existe.
 * @property signatureActive indice dans [signatures] de la candidate
 *           compatible avec l'arité courante (première trouvée) ;
 *           0 quand aucune ne prouve (l'appel est peut-être en
 *           cours d'écriture).
 * @property parametreActif indice du paramètre SOUS LE CURSEUR
 *           (arguments ordinaires comptés de gauche à droite ; un
 *           lambda final compte pour UN argument de plus).
 */
public data class AideSignatureKotlin(
    public val signatures: List<SignatureKotlin>,
    public val signatureActive: Int,
    public val parametreActif: Int,
)

/**
 * Candidate d'appel — une SURCHARGE possible du nom appelé.
 *
 * @property etiquette signature complète prête à afficher
 *           (`nom(texte: CharSequence): Unit`) — constructeurs
 *           compris (`Nom(p1: T1)`), sans préfixe `fun` (bruit de
 *           popup ; l'appelé EST une fonction ou un constructeur).
 * @property documentation texte d'accompagnement (KDoc rendu),
 *           `null` si non prouvé — ADR 0230 : la candidate PROJET
 *           porte la KDoc de sa déclaration (document courant →
 *           arbre vif ; autre fichier du projet → port de la session,
 *           lecture disque + parse bornées) ; les candidates du
 *           CLASSPATH restent sans doc (le bytecode n'en porte pas,
 *           D3 — jamais une ligne inventée).
 * @property parametres libellés de paramètres PRÊTS À AFFICHER
 *           (`texte: CharSequence`) — même nombre que la signature
 *           ; un paramètre sans nom rend son type seul.
 */
public data class SignatureKotlin(
    public val etiquette: String,
    public val documentation: String?,
    public val parametres: List<String>,
)
