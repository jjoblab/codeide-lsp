package jo.lspkotlin.resolve

import jo.lspkotlin.api.ContenuSurvoleKotlin
import jo.lspkotlin.api.EmplacementKotlin
import jo.lspkotlin.documentation.DocumentationKotlin
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
     *
     * [uri] identifie le document COURANT (ADR 0207 : la KDoc de la
     * déclaration élue accompagne AUSSI les sites d'usage quand elle
     * vit dans ce document — l'arbre vif est déjà en mémoire).
     */
    internal fun survol(
        racine: NoeudSyntaxique,
        decalage: Int,
        uri: String,
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
            ?: survolDeclarationProjet(nom, racine, uri, contexte)
            ?: survolTypeClasspath(nom, contexte)
            ?: survolAppelablePortee(nom, contexte)
    }

    /**
     * Survol du NOM DÉCLARÉ sous le curseur (locale comprise) — S2 :
     * la KDoc du document courant accompagne la signature. Les
     * déclarations du SCAN (autres fichiers) gardent la signature
     * seule : re-parser un fichier tiers pour son doc n'est pas un
     * coût K3 (choix documenté dans [ContenuSurvoleKotlin]).
     */
    private fun survolNomDeclare(
        racine: NoeudSyntaxique,
        decalage: Int,
        contexte: Contexte,
    ): ContenuSurvoleKotlin? =
        declarationAuCurseur(racine, decalage, contexte.contexteNom.paquet)?.let { projet ->
            survolDeclaration(projet)?.let { base ->
                // S2 : KDoc du document courant — la déclaration VIVE
                // sous le curseur porte son DOC_COMMENT (les déclarations
                // du SCAN gardent la signature seule, choix documenté).
                val documentation =
                    declarationViveAuCurseur(racine, decalage)?.let { declaration ->
                        DocumentationKotlin.kdocDe(declaration)
                    }
                base.copy(documentation = documentation)
            }
        }

    /**
     * Déclaration VIVE dont le nom est sous le curseur (porteur du
     * DOC_COMMENT — S2, KDoc). Même marche que [declarationAuCurseur]
     * (la plus profonde gagne), mais rend l'élément d'arbre au lieu de
     * sa projection [DeclarationProjet].
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount/LoopWithTooManyJumpStatements —
     * chaque garde du parcours d'ancêtres (pas une déclaration, nom
     * introuvable, hors plage) est une ISSUE distincte, même patron
     * que [declarationAuCurseur].
     */
    @Suppress("ReturnCount", "LoopWithTooManyJumpStatements")
    private fun declarationViveAuCurseur(
        racine: NoeudSyntaxique,
        decalage: Int,
    ): KtDeclaration? {
        val chaine = chaineAncetresA(racine, decalage)
        for (profondeur in chaine.indices.reversed()) {
            val declaration = KtElement.depuis(chaine[profondeur]) as? KtDeclaration ?: continue
            val jeton = declaration.nomJeton() ?: continue
            if (jeton.debut > decalage || decalage > jeton.fin) continue
            return declaration
        }
        return null
    }

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

    /** Survol d'une DÉCLARATION du projet portant ce nom (première) —
     * ADR 0207 : quand la déclaration élue vit dans le document
     * COURANT, sa KDoc VIVE (recherche par plage du nom dans l'arbre
     * en mémoire — aucun re-parse) accompagne la signature aux sites
     * d'USAGE, miroir du survol du nom déclaré ; les déclarations du
     * SCAN (autres fichiers) gardent la signature seule (choix K3
     * documenté — l'option 2 de l'ADR rouvrira le dossier).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — la chaîne
     * élue → rendu → KDoc conditionnelle est une ISSUE par étape,
     * même patron que le reste de la chaîne de survol. */
    @Suppress("ReturnCount")
    private fun survolDeclarationProjet(
        nom: String,
        racine: NoeudSyntaxique,
        uri: String,
        contexte: Contexte,
    ): ContenuSurvoleKotlin? {
        val elue =
            contexte.declarationsProjet
                .firstOrNull { declaration ->
                    declaration.nom == nom &&
                        declaration.genre != GenreDeclaration.PARAMETRE &&
                        declaration.genre != GenreDeclaration.LOCALE
                } ?: return null
        val base = survolDeclaration(elue) ?: return null
        // KDoc UNIQUEMENT pour la déclaration du document courant —
        // l'arbre vif est déjà en mémoire (choix K3 documenté pour le
        // scan : aucun re-parse de fichier tiers).
        if (elue.uri != uri) return base
        val documentation =
            declarationViveAPlage(racine, elue)?.let { declaration ->
                DocumentationKotlin.kdocDe(declaration)
            }
        return base.copy(documentation = documentation)
    }

    /**
     * Déclaration VIVE du document courant portant EXACTEMENT la
     * plage du nom de [elue] (recherche à début du nom — la plus
     * profonde gagne, plage ET nom vérifiés : jamais de jumelage
     * approximatif).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount/
     * LoopWithTooManyJumpStatements — chaque garde du parcours (pas
     * une déclaration, plage distincte, nom distinct) est une ISSUE,
     * même patron que declarationViveAuCurseur.
     */
    @Suppress("ReturnCount", "LoopWithTooManyJumpStatements")
    private fun declarationViveAPlage(
        racine: NoeudSyntaxique,
        elue: DeclarationProjet,
    ): KtDeclaration? {
        val chaine = chaineAncetresA(racine, elue.debutNom)
        for (profondeur in chaine.indices.reversed()) {
            val declaration = KtElement.depuis(chaine[profondeur]) as? KtDeclaration ?: continue
            val jeton = declaration.nomJeton() ?: continue
            if (jeton.debut != elue.debutNom || jeton.fin != elue.finNom) continue
            if (jeton.texte.toString() != elue.nom) continue
            return declaration
        }
        return null
    }

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
            // K5 : les synthétiques sans cible de fichier (constantes R)
            // n'ouvrent RIEN plutôt qu'une URI factice (D3).
            .filter { declaration -> declaration.uri.isNotBlank() }
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
    internal fun identifiantA(
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

    /**
     * Appelable de premier niveau en PORTÉE (paquet importé/joker/courant).
     *
     * Délègue au prédicat PARTAGÉ `ResolutionKotlin.appelableEnPortee`
     * (ADR 0203) : le survol et la voie 6 de `kt.unresolved` portent la
     * MÊME sémantique de visibilité (paquet courant, import exact,
     * joker, paquets par défaut de Kotlin JVM).
     */
    private fun estEnPortee(
        appelable: jo.lspkotlin.index.AppelableKotlin,
        contexte: Contexte,
    ): Boolean = contexte.resolution.appelableEnPortee(appelable, contexte.contexteNom)
}
