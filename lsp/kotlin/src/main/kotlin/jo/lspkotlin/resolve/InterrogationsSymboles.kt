package jo.lspkotlin.resolve

import jo.lspkotlin.api.ContenuSurvoleKotlin
import jo.lspkotlin.api.EmplacementKotlin
import jo.lspkotlin.parse.JetonSyntaxique
import jo.lspkotlin.parse.NoeudSyntaxique
import jo.lspkotlin.parse.kt.KtDeclaration
import jo.lspkotlin.parse.kt.KtElement
import jo.lspkotlin.parse.kt.KtFile
import jo.lspkotlin.parse.kt.chaineAncetresA
import jo.lspkotlin.parse.kt.recepteurQualifie
import org.jetbrains.kotlin.kmp.lexer.KtTokens
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes

/**
 * Survol et définition (K3, LSP_KOTLIN.md §4.4) — résolution de
 * l'IDENTIFIANT au curseur vers sa déclaration (projet : document
 * courant + racines sources) ou son information de classpath (vue JVM
 * + index des appelables).
 *
 * Définition : PROJET seul à K3 — les cibles du classpath n'ont pas de
 * source attachée (D3 documenté ; le survol porte leur FQN, les
 * sources-jars sont K4+ — l'équivalent « décompilé » du LSP Java
 * viendra avec le mode profond).
 *
 * Conservateur (D3) : `null`/vide quand rien n'est prouvable.
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — chaque étape
 * de la CHAÎNE de résolution (nom déclaré, locale, membre qualifié,
 * déclaration projet, type, appelable) est UNE fonction : le catalogue
 * EST l'architecture, pas une dérive.
 */
@Suppress("TooManyFunctions")
internal object InterrogationsSymboles {
    /**
     * Contexte d'interrogation — miroir de celui de la complétion
     * (session sans état propre).
     */
    internal data class Contexte(
        val contexteNom: ResolutionKotlin.ContexteNom,
        val resolution: ResolutionKotlin,
        val declarationsProjet: List<DeclarationProjet>,
        val index: jo.lspkotlin.index.IndexAppelablesKotlin,
        val vue: jo.lspkotlin.api.VueClasspathKotlin?,
    )

    /**
     * Survol à [decalage] : signature de la déclaration visée (locale,
     * paramètre, membre, premier niveau, projet) ; membre qualifié
     * (propriétaire classpath) ; type (FQCN) ; appelable de premier
     * niveau en portée.
     */
    internal fun survol(
        racine: NoeudSyntaxique,
        decalage: Int,
        contexte: Contexte,
    ): ContenuSurvoleKotlin? {
        val jeton = identifiantA(racine, decalage) ?: return null
        val nom = jeton.texte.toString()
        val chaine = chaineAncetresA(racine, decalage)
        // CHAÎNE de résolution — chaque étape est une ISSUE distincte
        // (D3 : la première qui PROUVE gagne, le reste sous-rapporte) :
        // nom déclaré → locale → membre qualifié → déclaration projet →
        // type classpath → appelable de premier niveau.
        return survolNomDeclare(racine, decalage, contexte)
            ?: survolLocale(nom, racine, decalage)
            ?: survolMembreQualifie(nom, chaine, decalage, contexte)
            ?: survolDeclarationProjet(nom, contexte)
            ?: survolTypeClasspath(nom, contexte)
            ?: survolAppelablePortee(nom, contexte)
    }

    /** Survol du NOM DÉCLARÉ sous le curseur (locale comprise). */
    private fun survolNomDeclare(
        racine: NoeudSyntaxique,
        decalage: Int,
        contexte: Contexte,
    ): ContenuSurvoleKotlin? =
        declarationAuCurseur(racine, decalage, contexte.contexteNom.paquet)
            ?.let { declaration -> survolDeclaration(declaration) }

    /** Survol d'une LOCALE/paramètre en portée (la plus proche gagne). */
    private fun survolLocale(
        nom: String,
        racine: NoeudSyntaxique,
        decalage: Int,
    ): ContenuSurvoleKotlin? =
        SymbolesProjet.localesEnPortee(racine, decalage)
            .firstOrNull { it.declaration.nom == nom }
            ?.let { locale ->
                ContenuSurvoleKotlin(
                    "${prefixeLocale(locale.declaration.genre)} $nom: ${locale.declaration.typeRetour ?: "?"}",
                )
            }

    /** Survol d'une DÉCLARATION du projet portant ce nom (première). */
    private fun survolDeclarationProjet(
        nom: String,
        contexte: Contexte,
    ): ContenuSurvoleKotlin? =
        contexte.declarationsProjet
            .firstOrNull { declaration ->
                declaration.nom == nom &&
                    declaration.genre != GenreDeclaration.PARAMETRE &&
                    declaration.genre != GenreDeclaration.LOCALE
            }
            ?.let { declaration -> survolDeclaration(declaration) }

    /** Survol d'un TYPE classpath (FQCN, vérifié contre la vue). */
    private fun survolTypeClasspath(
        nom: String,
        contexte: Contexte,
    ): ContenuSurvoleKotlin? =
        contexte.resolution
            .fqcnDuNomEcrit(nom, contexte.contexteNom)
            ?.takeIf { fqcn -> contexte.resolution.membresDe(fqcn) != null }
            ?.let { fqcn -> ContenuSurvoleKotlin("class $fqcn") }

    /** Survol d'un APPELABLE de premier niveau en portée (importé/joker). */
    private fun survolAppelablePortee(
        nom: String,
        contexte: Contexte,
    ): ContenuSurvoleKotlin? =
        contexte.index
            .topParNom(nom)
            .firstOrNull { appelable -> appelable.recepteur == null && estEnPortee(appelable, contexte) }
            ?.let { appelable -> ContenuSurvoleKotlin(signatureAppelable(appelable)) }

    /**
     * Définitions à [decalage] : emplacement du NOM déclaré, projet
     * seul (document courant + racines sources) — liste vide pour une
     * cible classpath (D3 : jamais d'URI factice).
 *
Exemption detekt ciblée (règle 16) : ReturnCount — nom déclaré/locale/projet = trois ISSUES. */
    @Suppress("ReturnCount")
    internal fun definitions(
        racine: NoeudSyntaxique,
        decalage: Int,
        uri: String,
        contexte: Contexte,
    ): List<EmplacementKotlin> {
        val jeton = identifiantA(racine, decalage) ?: return emptyList()
        val nom = jeton.texte.toString()
        // CHAÎNE : nom déclaré (locale comprise) → locale en portée →
        // déclarations du projet (document courant en tête).
        val locale =
            declarationAuCurseur(racine, decalage, uri, contexte.contexteNom.paquet)
                ?: SymbolesProjet.localesEnPortee(racine, decalage)
                    .firstOrNull { it.declaration.nom == nom }
                    ?.let { locale -> locale.declaration.copy(uri = uri) }
        if (locale != null) {
            return listOf(EmplacementKotlin(locale.uri, locale.debutNom, locale.finNom))
        }
        return contexte.declarationsProjet
            .filter { declaration ->
                declaration.nom == nom &&
                    declaration.genre != GenreDeclaration.PARAMETRE &&
                    declaration.genre != GenreDeclaration.LOCALE
            }
            .sortedBy { it.uri != uri }
            .map { declaration -> EmplacementKotlin(declaration.uri, declaration.debutNom, declaration.finNom) }
    }

    /**
     * Déclaration dont le NOM est sous le curseur (survol/définition du
     * nom déclaré — locale comprise : la règle « pas encore déclarée »
     * des portées de complétion ne doit pas s'appliquer ici). La plus
     * profonde gagne (locale avant membre avant premier niveau).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount/CyclomaticComplexMethod —
     * chaque garde du parcours d'ancêtres (pas une déclaration, nom
     * introuvable, hors plage, jeton absent) est une ISSUE distincte,
     * même patron que les résolveurs du LSP Java.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    private fun declarationAuCurseur(
        racine: NoeudSyntaxique,
        decalage: Int,
        uri: String? = null,
        paquet: String? = null,
    ): DeclarationProjet? {
        val chaine = chaineAncetresA(racine, decalage)
        for (profondeur in chaine.indices.reversed()) {
            val declaration = KtElement.depuis(chaine[profondeur]) as? KtDeclaration ?: continue
            val jeton = declaration.nomJeton() ?: continue
            if (jeton.debut > decalage || decalage > jeton.fin) continue
            return DeclarationProjet(
                genre = genreDeDeclaration(declaration),
                nom = jeton.texte.toString(),
                uri = uri ?: "",
                debutNom = jeton.debut,
                finNom = jeton.fin,
                paquet = paquet,
                recepteur = recepteurDeDeclaration(declaration),
                typeRetour = typeDeDeclaration(declaration),
                parametres =
                    (declaration as? jo.lspkotlin.parse.kt.KtFunction)
                        ?.parametres()
                        ?.map { parametre -> Pair(parametre.nom(), parametre.type()) }
                        ?: emptyList(),
                conteneur = null,
            )
        }
        return null
    }

    /** Genre d'une déclaration Kt* vivante. */
    private fun genreDeDeclaration(declaration: KtDeclaration): GenreDeclaration =
        when (declaration) {
            is jo.lspkotlin.parse.kt.KtClass ->
                if (declaration.estInterface()) GenreDeclaration.INTERFACE else GenreDeclaration.CLASSE

            is jo.lspkotlin.parse.kt.KtFunction -> GenreDeclaration.FONCTION
            is jo.lspkotlin.parse.kt.KtProperty -> GenreDeclaration.PROPRIETE
            is jo.lspkotlin.parse.kt.KtParameter -> GenreDeclaration.PARAMETRE
            else -> GenreDeclaration.OBJET
        }

    /** Récepteur d'extension écrit d'une déclaration (null sinon). */
    private fun recepteurDeDeclaration(declaration: KtDeclaration): String? =
        (declaration as? jo.lspkotlin.parse.kt.KtFunction)?.recepteurExtension()
            ?: (declaration as? jo.lspkotlin.parse.kt.KtProperty)?.recepteurExtension()

    /** Type écrit d'une déclaration (retour/valeur/paramètre). */
    private fun typeDeDeclaration(declaration: KtDeclaration): String? =
        when (declaration) {
            is jo.lspkotlin.parse.kt.KtFunction -> declaration.typeRetour()
            is jo.lspkotlin.parse.kt.KtProperty -> declaration.type()
            is jo.lspkotlin.parse.kt.KtParameter -> declaration.type()
            else -> null
        }

    // ── Résolution de l'identifiant au curseur ─────────────────────────

    /**
     * Jeton IDENTIFIER au curseur : référence de la chaîne d'ancêtres
     * (la plus profonde), sinon nom d'une déclaration englobante.
     * Null si le curseur n'est sur aucun identifiant exploitable.
 *
     * Jeton IDENTIFIER au curseur : référence de la chaîne d'ancêtres
     * (la plus profonde), sinon nom d'une déclaration englobante.
     * Null si le curseur n'est sur aucun identifiant exploitable.
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque sortie
     * (référence trouvée, nom déclaré trouvé, rien) est une ISSUE.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    private fun identifiantA(
        racine: NoeudSyntaxique,
        decalage: Int,
    ): JetonSyntaxique? {
        val chaine = chaineAncetresA(racine, decalage)
        // Référence / type écrit : l'identifiant SOUS le curseur.
        for (profondeur in chaine.indices.reversed()) {
            val noeud = chaine[profondeur]
            if (noeud.type != KtNodeTypes.REFERENCE_EXPRESSION &&
                noeud.type != KtNodeTypes.USER_TYPE &&
                noeud.type != KtNodeTypes.TYPE_REFERENCE
            ) {
                continue
            }
            val jeton =
                noeud.jetonsDescendants().firstOrNull {
                    it.type == KtTokens.IDENTIFIER && it.debut <= decalage && decalage <= it.fin
                }
            if (jeton != null) return jeton
        }
        // Nom déclaré d'une déclaration englobante (survol du NOM).
        for (profondeur in chaine.indices.reversed()) {
            val declaration = KtElement.depuis(chaine[profondeur]) as? KtDeclaration ?: continue
            val jeton = declaration.nomJeton() ?: continue
            if (jeton.debut <= decalage && decalage <= jeton.fin) return jeton
        }
        return null
    }

    /** Nom qualifié d'une déclaration (paquet + nom). */
    private fun nomQualifie(declaration: DeclarationProjet): String =
        declaration.paquet?.let { "$it.${declaration.nom}" } ?: declaration.nom

    /** Préfixe de rendu d'une locale (paramètre ou val). */
    private fun prefixeLocale(genre: GenreDeclaration): String =
        if (genre == GenreDeclaration.PARAMETRE) "param" else "val"

    /** Signature rendue d'un appelable de l'index. */
    private fun signatureAppelable(appelable: jo.lspkotlin.index.AppelableKotlin): String {
        val parametres =
            appelable.parametres.joinToString(", ") { parametre ->
                "${parametre.nom ?: "?"}: ${parametre.type ?: "?"}"
            }
        val retour = appelable.typeRetour?.let { ": $it" } ?: ""
        return "fun ${appelable.nom}($parametres)$retour"
    }

    /** Signature rendue d'une déclaration FONCTION du projet. */
    private fun signatureFonction(declaration: DeclarationProjet): String {
        val recepteur = declaration.recepteur?.let { "${it.substringAfterLast('.')}." } ?: ""
        val parametres =
            declaration.parametres.joinToString(", ") { (nom, type) -> "$nom: ${type ?: "?"}" }
        val retour = declaration.typeRetour?.let { ": $it" } ?: ""
        return "fun $recepteur${declaration.nom}($parametres)$retour"
    }

    // ── Survals composites ─────────────────────────────────────────────

    /**
     * Survol d'un SÉLECTEUR qualifié (`a.b|`) : récepteur résolu →
     * champ/méthode homonyme (premier porteur de la chaîne) →
     * signature + propriétaire ; extensions de l'index comprises.
 *
     * Survol d'un SÉLECTEUR qualifié (`a.b|`) : récepteur résolu →
     * champ/méthode homonyme (premier porteur de la chaîne) →
     * signature + propriétaire ; extensions de l'index comprises.
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount/CyclomaticComplexMethod/NestedBlockDepth —
     * la CASCADE de résolution (pas de qualifié, récepteur non typé,
     * champ absent, méthode absente, extension absente) est le contrat
     * même du conservatisme D3 : chaque échec est une ISSUE distincte.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements")
    private fun survolMembreQualifie(
        nom: String,
        chaine: List<NoeudSyntaxique>,
        decalage: Int,
        contexte: Contexte,
    ): ContenuSurvoleKotlin? {
        // L'expression qualifiée englobante dont le curseur est dans le SÉLECTEUR
        // (le récepteur finit AVANT le curseur).
        var recepteur: NoeudSyntaxique? = null
        for (profondeur in chaine.indices.reversed()) {
            val noeud = chaine[profondeur]
            if (noeud.type != KtNodeTypes.DOT_QUALIFIED_EXPRESSION &&
                noeud.type != KtNodeTypes.SAFE_ACCESS_EXPRESSION
            ) {
                continue
            }
            val candidat = recepteurQualifie(noeud) ?: continue
            if (candidat.fin <= decalage) {
                recepteur = candidat
                break
            }
        }
        val noeudRecepteur = recepteur ?: return null
        val typeRecepteur =
            TypageExpression.typeExpression(
                noeudRecepteur,
                chaine.first(),
                contexte.contexteNom,
                contexte.resolution,
                contexte.declarationsProjet,
            ) ?: return null
        // Champ : descripteur = type.
        val champ = contexte.resolution.champsNommes(typeRecepteur, nom).firstOrNull()
        if (champ != null) {
            val traduit = contexte.resolution.traduireDescripteur("()${champ.descripteur}")
            val type = if (traduit.retour == "void") "?" else contexte.resolution.renduCourt(traduit.retour)
            return ContenuSurvoleKotlin("val $nom: $type")
        }
        // Méthode : signature de la première homonyme + propriétaire.
        val methode = contexte.resolution.methodesNommees(typeRecepteur, nom).firstOrNull()
        if (methode != null) {
            val traduit = contexte.resolution.traduireDescripteur(methode.methode.descripteur)
            val rendu = traduit.parametres.joinToString(", ") { contexte.resolution.renduCourt(it) }
            return ContenuSurvoleKotlin(
                "fun $nom($rendu): ${contexte.resolution.renduCourt(traduit.retour)} — ${methode.proprietaire}",
            )
        }
        // Extension de l'index sur ce récepteur (ou un supertype/alias).
        for (courant in contexte.resolution.chaineSupertypes(typeRecepteur)) {
            for (variante in contexte.resolution.variantesRecepteur(courant)) {
                for (extension in contexte.index.extensionsSur(variante)) {
                    if (extension.nom != nom) continue
                    val rendu = extension.parametres.joinToString(", ") { "${it.nom ?: "?"}: ${it.type ?: "?"}" }
                    return ContenuSurvoleKotlin(
                        "fun ${courant.substringAfterLast('.')}.$nom($rendu)" +
                            extension.typeRetour.orEmpty().let { if (it.isEmpty()) "" else ": $it" },
                    )
                }
            }
        }
        return null
    }

    /** Survol d'une déclaration projet : signature rendue.
 *
     * Survol d'une déclaration projet : signature rendue.
     *
     * Exemption detekt ciblée (règle 16) : CyclomaticComplexMethod — un
     * genre = un rendu, le catalogue EST le when.
     */
    @Suppress("CyclomaticComplexMethod")
    private fun survolDeclaration(declaration: DeclarationProjet): ContenuSurvoleKotlin? =
        when (declaration.genre) {
            GenreDeclaration.FONCTION -> ContenuSurvoleKotlin(signatureFonction(declaration))

            GenreDeclaration.PROPRIETE ->
                ContenuSurvoleKotlin("val ${declaration.nom}: ${declaration.typeRetour ?: "?"}")

            GenreDeclaration.CLASSE -> ContenuSurvoleKotlin("class ${nomQualifie(declaration)}")

            GenreDeclaration.INTERFACE -> ContenuSurvoleKotlin("interface ${nomQualifie(declaration)}")

            GenreDeclaration.OBJET -> ContenuSurvoleKotlin("object ${declaration.nom}")

            GenreDeclaration.ALIAS -> ContenuSurvoleKotlin("typealias ${declaration.nom}")

            GenreDeclaration.PARAMETRE, GenreDeclaration.LOCALE, GenreDeclaration.ENTREE_ENUM -> null
        }

    /** Appelable de premier niveau en PORTÉE (paquet importé/joker/courant).
 *
     * Appelable de premier niveau en PORTÉE (paquet importé/joker/courant).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque règle
     * de portée est une ISSUE (paquet courant, import exact, joker,
     * défaut Kotlin, hors portée).
     */
    @Suppress("ReturnCount")
    private fun estEnPortee(
        appelable: jo.lspkotlin.index.AppelableKotlin,
        contexte: Contexte,
    ): Boolean {
        if (appelable.paquet == contexte.contexteNom.paquet) return true
        if (contexte.contexteNom.importsParNom.values.any { it == "${appelable.paquet}.${appelable.nom}" }) return true
        if (contexte.contexteNom.importsJoker.any { it == appelable.paquet }) return true
        // Import par défaut : paquets kotlin usuels toujours en portée.
        if (appelable.paquet in PAQUETS_DEFAUT) return true
        return false
    }

    private val PAQUETS_DEFAUT: Set<String> =
        setOf(
            "kotlin",
            "kotlin.annotation",
            "kotlin.collections",
            "kotlin.comparisons",
            "kotlin.io",
            "kotlin.ranges",
            "kotlin.sequences",
            "kotlin.text",
        )
}
