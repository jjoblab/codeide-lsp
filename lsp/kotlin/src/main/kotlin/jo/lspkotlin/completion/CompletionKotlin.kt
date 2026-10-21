package jo.lspkotlin.completion

import jo.lspkotlin.api.DRAPEAU_PUBLIC_KOTLIN
import jo.lspkotlin.api.DRAPEAU_STATIQUE_KOTLIN
import jo.lspkotlin.api.GenreCompletionKotlin
import jo.lspkotlin.api.NomTypeKotlin
import jo.lspkotlin.api.PertinencesKotlin
import jo.lspkotlin.api.PropositionCompletionKotlin
import jo.lspkotlin.api.VueClasspathKotlin
import jo.lspkotlin.index.AppelableKotlin
import jo.lspkotlin.index.GenreAppelable
import jo.lspkotlin.index.IndexAppelablesKotlin
import jo.lspkotlin.parse.NoeudSyntaxique
import jo.lspkotlin.parse.kt.KtDeclaration
import jo.lspkotlin.parse.kt.KtFile
import jo.lspkotlin.parse.kt.chaineAncetresA
import jo.lspkotlin.parse.kt.nomReference
import jo.lspkotlin.parse.kt.recepteurQualifie
import jo.lspkotlin.resolve.DeclarationProjet
import jo.lspkotlin.resolve.GenreDeclaration
import jo.lspkotlin.resolve.RecepteursWith
import jo.lspkotlin.resolve.ResolutionKotlin
import jo.lspkotlin.resolve.SymbolesProjet
import jo.lspkotlin.resolve.TypageExpression
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes

/**
 * Complétion Kotlin (K3, LSP_KOTLIN.md §4.1 « completion », §4.3) —
 * deux régimes :
 *
 *  - **MEMBRE** après `.`/`?.` : membres déclarés de la chaîne de
 *    supertypes du récepteur résolu (vue JVM de l'hôte) ∪ EXTENSIONS
 *    applicables (index des appelables — récepteur ET ses
 *    supertypes, motif projet tiers de référence) ;
 *  - **NOM** : locales en portée, membres de l'englobante, premier
 *    niveau du fichier, déclarations du PROJET (auto-import si hors
 *    portée), types du CLASSPATH (auto-import), appelables de
 *    premier niveau, mots-clés ;
 *  - **IMPORT** dans une directive import : sous-paquets et types du
 *    paquet tapé (vue).
 *
 * Conservateur (D3) : un récepteur non résolu ne propose RIEN (liste
 * membre vide) ; une proposition toujours fausse est pire qu'une
 * absence. Le classement encode pertinence + longueur + nom dans
 * [PropositionCompletionKotlin.texteTri] (le client LSP trie par
 * sortText seul — motif du LSP Java).
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions/LongMethod/
 * CyclomaticComplexMethod — les TROIS régimes (membre/nom/import) +
 * leurs fabriques de propositions : chaque fonction est une SECTION du
 * pipeline de complétion, le when des genres est le catalogue (patron
 * des moteurs LSP du dépôt).
 */
@Suppress(
    "TooManyFunctions",
    "LongMethod",
    "CyclomaticComplexMethod",
    "ReturnCount",
    "NestedBlockDepth",
    "LongParameterList",
)
internal object CompletionKotlin {
    /** Cap du nombre de propositions rendues. */
    private const val LIMITE_PROPOSITIONS: Int = 100

    /** Limite interne des requêtes de types à la vue. */
    private const val LIMITE_TYPES: Int = 60

    /** Mots-clés proposés en complétion de nom (filtrés par préfixe). */
    private val MOTS_CLES: List<String> =
        listOf(
            "class", "interface", "object", "fun", "val", "var", "typealias", "enum",
            "data", "sealed", "abstract", "open", "override", "private", "protected",
            "internal", "public", "companion", "init", "constructor",
            "if", "else", "when", "for", "while", "do", "try", "catch", "finally",
            "return", "throw", "break", "continue",
            "is", "in", "as", "out", "by", "where", "suspend", "inline", "reified",
            "true", "false", "null", "this", "super",
            "import", "package", "get", "set",
        )

    /**
     * Contexte de complétion — tout ce que la session sait, sans état
     * propre (fonctions pures sur les entrées).
     */
    internal data class Contexte(
        val vue: VueClasspathKotlin?,
        val index: IndexAppelablesKotlin,
        val resolution: ResolutionKotlin,
        val contexteNom: ResolutionKotlin.ContexteNom,
        val declarationsProjet: List<DeclarationProjet>,
    )

    /**
     * Propositions à [decalage] dans le document.
     *
     * @param racine arbre du document (dernier texte connu).
     * @param texte texte intégral du document.
     * @param decalage position du curseur.
     * @param contexte contexte de session.
     * @return les propositions triées par sortText (cap
     *         [LIMITE_PROPOSITIONS]) ; vide si rien n'est prouvable.
     */
    internal fun propositions(
        racine: NoeudSyntaxique,
        texte: CharSequence,
        decalage: Int,
        contexte: Contexte,
    ): List<PropositionCompletionKotlin> {
        val borne = decalage.coerceIn(0, texte.length)
        val (debutPrefixe, prefixe) = prefixeA(borne, texte)
        val regime = regimeA(debutPrefixe, borne, texte, racine)
        return when (regime) {
            Regime.MEMBRE -> propositionsMembre(racine, debutPrefixe, prefixe, contexte)
            Regime.IMPORT -> propositionsImport(racine, decalage, prefixe, contexte)
            Regime.NOM -> propositionsNom(racine, debutPrefixe, prefixe, contexte)
        }.let { dedupliquerOrdonner(it) }
    }

    /** Régimes de complétion. */
    private enum class Regime {
        MEMBRE,
        IMPORT,
        NOM,
    }

    /** Régime à la position : membre (après `.`/`?.`), import, sinon nom. */
    private fun regimeA(
        debutPrefixe: Int,
        decalage: Int,
        texte: CharSequence,
        racine: NoeudSyntaxique,
    ): Regime {
        // Directive import en ancêtre : complétion d'import.
        val chaine = chaineAncetresA(racine, decalage)
        if (chaine.any { it.type == KtNodeTypes.IMPORT_DIRECTIVE }) return Regime.IMPORT
        // Caractère significatif avant le préfixe.
        var index = debutPrefixe - 1
        while (index >= 0 && texte[index].isWhitespace()) index--
        if (index >= 0 && texte[index] == '?') {
            index--
            while (index >= 0 && texte[index].isWhitespace()) index--
        }
        if (index >= 0 && texte[index] == '.') return Regime.MEMBRE
        return Regime.NOM
    }

    /**
     * Préfixe en cours de frappe : recule sur les caractères
     * d'identifiant depuis [decalage] ; (début du préfixe, texte).
     */
    private fun prefixeA(
        decalage: Int,
        texte: CharSequence,
    ): Pair<Int, String> {
        var debut = decalage
        while (debut > 0 && estCaractereIdentifiant(texte[debut - 1])) debut--
        return Pair(debut, texte.substring(debut, decalage))
    }

    private fun estCaractereIdentifiant(car: Char): Boolean = car.isLetterOrDigit() || car == '_'

    // ── Régime MEMBRE ──────────────────────────────────────────────────

    /**
     * Propositions de membres : récepteur de l'expression qualifiée
     * englobante résolu (valeur → membres d'instance ; TYPE reconnu →
     * membres statiques), ∪ extensions sur la chaîne de supertypes.
 *
     * Exemption detekt ciblée (règle 16) : LoopWithTooManyJumpStatements —
     * les continue du nœud non qualifié et du récepteur invalide sont le
     * patron de la recherche du qualifié porteur (précédent
     * survolMembreQualifie / VueIndex.typesDansPaquet).
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun propositionsMembre(
        racine: NoeudSyntaxique,
        debutPrefixe: Int,
        prefixe: String,
        contexte: Contexte,
    ): List<PropositionCompletionKotlin> {
        val chaine = chaineAncetresA(racine, debutPrefixe)
        // L'expression qualifiée la plus profonde dont le PRÉFIXE est
        // dans le sélecteur (après le récepteur).
        var recepteur: NoeudSyntaxique? = null
        for (profondeur in chaine.indices.reversed()) {
            val noeud = chaine[profondeur]
            if (noeud.type != KtNodeTypes.DOT_QUALIFIED_EXPRESSION &&
                noeud.type != KtNodeTypes.SAFE_ACCESS_EXPRESSION
            ) {
                continue
            }
            val candidat = recepteurQualifie(noeud) ?: continue
            if (candidat.fin <= debutPrefixe) {
                recepteur = candidat
                break
            }
        }
        if (recepteur == null) return emptyList()
        // Récepteur typé (valeur) ; sinon TYPE reconnu (statiques).
        val typeValeur =
            TypageExpression.typeExpression(
                recepteur,
                racine,
                contexte.contexteNom,
                contexte.resolution,
                contexte.declarationsProjet,
            )
        val typeStatique: String? =
            if (typeValeur == null && recepteur.type == KtNodeTypes.REFERENCE_EXPRESSION) {
                contexte.resolution.fqcnDuNomEcrit(nomReference(recepteur) ?: "", contexte.contexteNom)
            } else {
                null
            }
        val fqcn = typeValeur ?: typeStatique ?: return emptyList()

        val propositions = mutableListOf<PropositionCompletionKotlin>()
        // Membres déclarés le long de la chaîne de supertypes.
        for (courant in contexte.resolution.chaineSupertypes(fqcn)) {
            val membres = contexte.vue?.membres(courant) ?: continue
            for (champ in membres.champs) {
                if (!estOffrable(champ.nom, champ.drapeaux, statiqueSeul = typeStatique != null)) continue
                if (!champ.nom.startsWith(prefixe)) continue
                propositions += proposition(
                    libelle = champ.nom,
                    genre = GenreCompletionKotlin.PROPRIETE,
                    detail = detailChamp(champ.descripteur, contexte),
                    pertinence = PertinencesKotlin.MEMBRE,
                )
            }
            for (methode in membres.methodes) {
                if (!estOffrable(methode.nom, methode.drapeaux, statiqueSeul = typeStatique != null)) continue
                if (!methode.nom.startsWith(prefixe)) continue
                propositions += propositionMethode(methode.nom, methode.descripteur, contexte)
            }
        }
        // Membres du PROJET : un récepteur de type PROJET n'est PAS dans
        // la vue (classpath) — ses déclarations (fichier courant + scan)
        // sont la source de vérité, conteneur = FQCN.
        for (declaration in contexte.declarationsProjet) {
            if (declaration.conteneur != fqcn) continue
            if (!declaration.nom.startsWith(prefixe)) continue
            val libelle = libelleDe(declaration)
            propositions += PropositionCompletionKotlin(
                libelle = libelle,
                genre = genreDe(declaration.genre),
                detail = detailDeclaration(declaration, contexte),
                texteTri = texteTri(PertinencesKotlin.MEMBRE, libelle),
            )
        }
        // Extensions : récepteur + supertypes + alias JVM/Kotlin.
        val recepteursExtension = extensionRecepteurs(fqcn, contexte)
        for (recepteurExtension in recepteursExtension) {
            for (extension in contexte.index.extensionsSur(recepteurExtension)) {
                if (!extension.nom.startsWith(prefixe)) continue
                propositions += propositionAppelable(extension, PertinencesKotlin.MEMBRE - 1)
            }
        }
        return propositions
    }

    /** Membre offrable : public/protected, nom propre, statique selon le régime.
 *
Drapeau JVM `protected` (membres offrables). */
    private const val DRAPEAU_PROTEGE: Int = 0x0004

    /** Borne du segment « longueur » de la clé de tri. */
    private const val BORNE_LONGUEUR_TRI: Int = 999

    private fun estOffrable(
        nom: String,
        drapeaux: Int,
        statiqueSeul: Boolean,
    ): Boolean {
        if (nom.isEmpty() || nom.contains('$') || nom.startsWith("<")) return false
        val visible = (drapeaux and DRAPEAU_PUBLIC_KOTLIN) != 0 || (drapeaux and DRAPEAU_PROTEGE) != 0
        if (!visible) return false
        val statique = (drapeaux and DRAPEAU_STATIQUE_KOTLIN) != 0
        return if (statiqueSeul) statique else true
    }

    /** Récepteurs d'extensions à sonder (chaîne + variantes). */
    private fun extensionRecepteurs(
        fqcn: String,
        contexte: Contexte,
    ): List<String> {
        val recepteurs = LinkedHashSet<String>()
        for (courant in contexte.resolution.chaineSupertypes(fqcn)) {
            contexte.resolution.variantesRecepteur(courant).forEach { recepteurs.add(it) }
        }
        return recepteurs.toList()
    }

    // ── Régime NOM ────────────────────────────────────────────────────

    /** Propositions de noms (portées + projet + classpath + mots-clés).
 *
     * Exemption detekt ciblée (règle 16) : LoopWithTooManyJumpStatements —
     * chaque continue SAUTE une SOURCE de propositions (locales,
     * englobantes, premier niveau, projet, types, appelables,
     * mots-clés) : le patron même de la fusion.
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun propositionsNom(
        racine: NoeudSyntaxique,
        debutPrefixe: Int,
        prefixe: String,
        contexte: Contexte,
    ): List<PropositionCompletionKotlin> {
        val propositions = mutableListOf<PropositionCompletionKotlin>()
        // 1. Locales/paramètres (la plus proche gagne le dédoublonnage).
        for (locale in SymbolesProjet.localesEnPortee(racine, debutPrefixe)) {
            if (!locale.declaration.nom.startsWith(prefixe)) continue
            propositions += proposition(
                libelle = locale.declaration.nom,
                genre = GenreCompletionKotlin.VARIABLE,
                detail = locale.declaration.typeRetour?.let { contexte.resolution.renduCourt(it) },
                pertinence = PertinencesKotlin.LOCALE,
            )
        }
        // 2. Membres des classes englobantes (this implicite).
        for (membre in SymbolesProjet.membresEnglobants(racine, debutPrefixe)) {
            val decl = membre as? jo.lspkotlin.parse.kt.KtDeclaration ?: continue
            val nom = decl.nom() ?: continue
            if (!nom.startsWith(prefixe)) continue
            propositions += propositionMembreProjet(decl, nom)
        }
        // 2 bis. K5 : membres du RÉCEPTEUR IMPLICITE d'un `with`
        // englobant — classpath (chaîne de supertypes) puis projet
        // (conteneur = FQCN), même régime que les membres qualifiés.
        val recepteurWith =
            RecepteursWith.recepteurImplicite(
                racine,
                debutPrefixe,
                contexte.contexteNom,
                contexte.resolution,
                contexte.declarationsProjet,
            )
        if (recepteurWith != null) {
            for (courant in contexte.resolution.chaineSupertypes(recepteurWith)) {
                val membres = contexte.vue?.membres(courant) ?: continue
                for (champ in membres.champs) {
                    if (!estOffrable(champ.nom, champ.drapeaux, statiqueSeul = false)) continue
                    if (!champ.nom.startsWith(prefixe)) continue
                    propositions +=
                        proposition(
                            libelle = champ.nom,
                            genre = GenreCompletionKotlin.PROPRIETE,
                            detail = detailChamp(champ.descripteur, contexte),
                            pertinence = PertinencesKotlin.MEMBRE,
                        )
                }
                for (methode in membres.methodes) {
                    if (!estOffrable(methode.nom, methode.drapeaux, statiqueSeul = false)) continue
                    if (!methode.nom.startsWith(prefixe)) continue
                    propositions += propositionMethode(methode.nom, methode.descripteur, contexte)
                }
            }
            for (declaration in contexte.declarationsProjet) {
                if (declaration.conteneur != recepteurWith) continue
                if (!declaration.nom.startsWith(prefixe)) continue
                val libelle = libelleDe(declaration)
                propositions +=
                    PropositionCompletionKotlin(
                        libelle = libelle,
                        genre = genreDe(declaration.genre),
                        detail = detailDeclaration(declaration, contexte),
                        texteTri = texteTri(PertinencesKotlin.MEMBRE, libelle),
                    )
            }
        }
        // 3. Premier niveau du fichier courant.
        for (declaration in KtFile(racine).declarations()) {
            val nom = declaration.nom() ?: continue
            if (!nom.startsWith(prefixe)) continue
            propositions += propositionDebutNiveau(declaration, nom)
        }
        // 4. Déclarations du projet (auto-import si hors portée).
        for (declaration in contexte.declarationsProjet) {
            if (!declaration.nom.startsWith(prefixe)) continue
            if (declaration.genre == GenreDeclaration.PARAMETRE) continue
            if (declaration.genre == GenreDeclaration.LOCALE) continue
            val libelle = libelleDe(declaration)
            propositions += PropositionCompletionKotlin(
                libelle = libelle,
                genre = genreDe(declaration.genre),
                detail = detailDeclaration(declaration, contexte),
                texteTri = texteTri(PertinencesKotlin.PROJET, libelle),
                fqnImport = fqnImportDeclaration(declaration, contexte),
            )
        }
        // 5. Types du classpath (auto-import si hors portée).
        val types = contexte.vue?.nomsParPrefixe(prefixe, LIMITE_TYPES) ?: emptyList()
        for (type in types) {
            propositions += propositionTypeClasspath(type, contexte)
        }
        // 6. Appelables de premier niveau du classpath Kotlin.
        for (nom in contexte.index.nomsTopParPrefixe(prefixe, LIMITE_TYPES)) {
            for (appelable in contexte.index.topParNom(nom)) {
                propositions += propositionAppelable(appelable, PertinencesKotlin.CLASSPATH)
            }
        }
        // 7. Mots-clés.
        for (mot in MOTS_CLES) {
            if (!mot.startsWith(prefixe)) continue
            propositions += proposition(mot, GenreCompletionKotlin.MOT_CLE, null, PertinencesKotlin.MOT_CLE)
        }
        return propositions
    }

    // ── Régime IMPORT ─────────────────────────────────────────────────

    /** Sous-paquets et types du paquet tapé dans la directive import.
 *
Exemption detekt ciblée (règle 16) : ReturnCount — issues distinctes. */
    @Suppress("ReturnCount")
    private fun propositionsImport(
        racine: NoeudSyntaxique,
        decalage: Int,
        prefixe: String,
        contexte: Contexte,
    ): List<PropositionCompletionKotlin> {
        val chaine = chaineAncetresA(racine, decalage)
        val directive = chaine.firstOrNull { it.type == KtNodeTypes.IMPORT_DIRECTIVE } ?: return emptyList()
        val qualifie =
            KtFile(racine)
                .directivesImport()
                .firstOrNull { it.noeud == directive }
                ?.nomQualifie()
                ?: prefixe
        val paquet = qualifie.substringBeforeLast('.', "")
        val propositions = mutableListOf<PropositionCompletionKotlin>()
        contexte.vue?.sousPaquetsDirects(paquet)?.forEach { sous ->
            if (sous.substringAfterLast('.').startsWith(prefixe)) {
                propositions += proposition(
                    sous.substringAfterLast('.'),
                    GenreCompletionKotlin.PAQUET,
                    sous,
                    PertinencesKotlin.CLASSPATH,
                )
            }
        }
        contexte.vue?.typesDansPaquet(paquet)?.forEach { fqcn ->
            val simple = fqcn.substringAfterLast('.')
            if (simple.startsWith(prefixe)) {
                propositions += proposition(simple, GenreCompletionKotlin.CLASSE, fqcn, PertinencesKotlin.CLASSPATH)
            }
        }
        return propositions
    }

    // ── Fabriques de propositions ─────────────────────────────────────

    /** Proposition simple (mots-clés, champs, types…). */
    private fun proposition(
        libelle: String,
        genre: GenreCompletionKotlin,
        detail: String?,
        pertinence: Int,
    ): PropositionCompletionKotlin =
        PropositionCompletionKotlin(
            libelle = libelle,
            genre = genre,
            detail = detail,
            texteTri = texteTri(pertinence, libelle),
        )

    /** Méthode JVM : libelle `nom()`, détail signature traduite. */
    private fun propositionMethode(
        nom: String,
        descripteur: String,
        contexte: Contexte,
    ): PropositionCompletionKotlin =
        PropositionCompletionKotlin(
            libelle = "$nom()",
            genre = GenreCompletionKotlin.FONCTION,
            detail = detailMethode(nom, descripteur, contexte),
            texteTri = texteTri(PertinencesKotlin.MEMBRE, nom),
        )

    /** Appelable Kotlin (top-level/extension) de l'index. */
    private fun propositionAppelable(
        appelable: AppelableKotlin,
        pertinence: Int,
    ): PropositionCompletionKotlin =
        PropositionCompletionKotlin(
            libelle = if (appelable.genre == GenreAppelable.FONCTION) "${appelable.nom}()" else appelable.nom,
            genre =
                if (appelable.genre == GenreAppelable.FONCTION) {
                    GenreCompletionKotlin.FONCTION
                } else {
                    GenreCompletionKotlin.PROPRIETE
                },
            detail = detailAppelable(appelable),
            texteTri = texteTri(pertinence, appelable.nom),
            fqnImport = if (appelable.recepteur == null) "${appelable.paquet}.${appelable.nom}" else null,
        )

    /** Membre de l'englobante (déclaration Kt* vivante). */
    private fun propositionMembreProjet(
        declaration: jo.lspkotlin.parse.kt.KtDeclaration,
        nom: String,
    ): PropositionCompletionKotlin {
        val detail =
            when (declaration) {
                is jo.lspkotlin.parse.kt.KtFunction -> detailFonction(
                    declaration.parametres().map { Pair(it.nom(), it.type()) },
                    declaration.typeRetour(),
                )

                is jo.lspkotlin.parse.kt.KtProperty -> declaration.type()

                else -> null
            }
        return PropositionCompletionKotlin(
            libelle = if (declaration is jo.lspkotlin.parse.kt.KtFunction) "$nom()" else nom,
            genre =
                when (declaration) {
                    is jo.lspkotlin.parse.kt.KtFunction -> GenreCompletionKotlin.FONCTION
                    is jo.lspkotlin.parse.kt.KtProperty -> GenreCompletionKotlin.PROPRIETE
                    is jo.lspkotlin.parse.kt.KtClass ->
                        if (declaration.estInterface()) {
                            GenreCompletionKotlin.INTERFACE
                        } else {
                            GenreCompletionKotlin.CLASSE
                        }

                    else -> GenreCompletionKotlin.OBJET
                },
            detail = detail,
            texteTri = texteTri(PertinencesKotlin.MEMBRE, nom),
        )
    }

    /** Déclaration de premier niveau du fichier courant. */
    private fun propositionDebutNiveau(
        declaration: jo.lspkotlin.parse.kt.KtDeclaration,
        nom: String,
    ): PropositionCompletionKotlin {
        val decl = jo.lspkotlin.parse.kt.KtDeclaration::class.java
        val base = propositionMembreProjet(declaration, nom)
        return base.copy(texteTri = texteTri(PertinencesKotlin.PROJET, nom))
    }

    /** Type du classpath : auto-import si le nom n'est pas en portée. */
    private fun propositionTypeClasspath(
        type: NomTypeKotlin,
        contexte: Contexte,
    ): PropositionCompletionKotlin {
        val fqcn =
            type.fqcns.firstOrNull()
                ?: return proposition(type.nomSimple, GenreCompletionKotlin.CLASSE, null, PertinencesKotlin.CLASSPATH)
        val enPortee =
            contexte.contexteNom.importsParNom.containsKey(type.nomSimple) ||
                contexte.contexteNom.paquet?.let { fqcn.startsWith("$it.") } == true
        return PropositionCompletionKotlin(
            libelle = type.nomSimple,
            genre = GenreCompletionKotlin.CLASSE,
            detail = fqcn,
            texteTri = texteTri(PertinencesKotlin.CLASSPATH, type.nomSimple),
            fqnImport = if (enPortee) null else fqcn,
        )
    }

    /** Libellé d'une déclaration projet (fonctions : parenthèses). */
    private fun libelleDe(declaration: DeclarationProjet): String =
        if (declaration.genre == GenreDeclaration.FONCTION) "${declaration.nom}()" else declaration.nom

    /** Genre LSP d'un genre de déclaration. */
    private fun genreDe(genre: GenreDeclaration): GenreCompletionKotlin =
        when (genre) {
            GenreDeclaration.CLASSE, GenreDeclaration.ENTREE_ENUM -> GenreCompletionKotlin.CLASSE
            GenreDeclaration.INTERFACE -> GenreCompletionKotlin.INTERFACE
            GenreDeclaration.OBJET -> GenreCompletionKotlin.OBJET
            GenreDeclaration.FONCTION -> GenreCompletionKotlin.FONCTION
            GenreDeclaration.PROPRIETE -> GenreCompletionKotlin.PROPRIETE
            GenreDeclaration.PARAMETRE, GenreDeclaration.LOCALE -> GenreCompletionKotlin.VARIABLE
            GenreDeclaration.ALIAS -> GenreCompletionKotlin.ALIAS
        }

    /** Détail (signature) d'une déclaration projet. */
    private fun detailDeclaration(
        declaration: DeclarationProjet,
        contexte: Contexte,
    ): String? =
        when (declaration.genre) {
            GenreDeclaration.FONCTION -> detailFonction(declaration.parametres, declaration.typeRetour)
            GenreDeclaration.PROPRIETE -> declaration.typeRetour?.let { contexte.resolution.renduCourt(it) }
            GenreDeclaration.CLASSE, GenreDeclaration.INTERFACE ->
                declaration.paquet?.let { paquet ->
                    val prefixe =
                        if (declaration.genre == GenreDeclaration.INTERFACE) "interface " else "class "
                    "$prefixe$paquet.${declaration.nom}"
                }

            else -> null
        }

    /** Import à dériver pour une déclaration hors portée (paquet.nom). */
    private fun fqnImportDeclaration(
        declaration: DeclarationProjet,
        contexte: Contexte,
    ): String? {
        if (declaration.genre == GenreDeclaration.PARAMETRE || declaration.genre == GenreDeclaration.LOCALE) return null
        if (declaration.paquet == null) return null
        val fqn = "${declaration.paquet}.${declaration.nom}"
        val enPortee =
            contexte.contexteNom.importsParNom[declaration.nom] == fqn ||
                contexte.contexteNom.paquet == declaration.paquet
        return if (enPortee) null else fqn
    }

    /** Signature d'une fonction : `fun nom(p: T): R`. */
    private fun detailFonction(
        parametres: List<Pair<String?, String?>>,
        retour: String?,
    ): String {
        val rendu = parametres.joinToString(", ") { (nom, type) ->
            "$nom: ${type ?: "?"}"
        }
        val suffixe = retour?.let { ": $it" } ?: ""
        return "fun ($rendu)$suffixe"
    }

    /** Signature d'un appelable de l'index Kotlin. */
    private fun detailAppelable(appelable: AppelableKotlin): String =
        when (appelable.genre) {
            GenreAppelable.FONCTION ->
                "fun " + prefixeRecepteur(appelable) + appelable.nom +
                    "(" + parametresRendus(appelable) + ")" + suffixeRetour(appelable)

            GenreAppelable.PROPRIETE ->
                "val ${prefixeRecepteur(appelable)}${appelable.nom}${suffixeRetour(appelable)}"
        }

    /** Préfixe récepteur rendu (« String. ») d'un appelable. */
    private fun prefixeRecepteur(appelable: AppelableKotlin): String =
        appelable.recepteur?.let { "${it.substringAfterLast('.')}." } ?: ""

    /** Paramètres rendus (« a: T, b: U ») d'un appelable. */
    private fun parametresRendus(appelable: AppelableKotlin): String =
        appelable.parametres.joinToString(", ") { parametre ->
            "${parametre.nom ?: "?"}: ${parametre.type ?: "?"}"
        }

    /** Suffixe de retour rendu (« : T ») d'un appelable. */
    private fun suffixeRetour(appelable: AppelableKotlin): String =
        appelable.typeRetour?.let { ": $it" } ?: ""

    /** Détail d'un champ JVM (descripteur = type du champ). */
    private fun detailChamp(
        descripteur: String,
        contexte: Contexte,
    ): String? {
        val traduit = contexte.resolution.traduireDescripteur("()$descripteur")
        if (traduit.retour == "void") return null
        return contexte.resolution.renduCourt(traduit.retour)
    }

    /** Détail d'une méthode JVM : `fun nom(p: T): R`. */
    private fun detailMethode(
        nom: String,
        descripteur: String,
        contexte: Contexte,
    ): String {
        val traduit = contexte.resolution.traduireDescripteur(descripteur)
        val rendu = traduit.parametres.joinToString(", ") { contexte.resolution.renduCourt(it) }
        val retour = contexte.resolution.renduCourt(traduit.retour)
        return "fun $nom($rendu): $retour"
    }

    /** Clé de tri totale (pertinence décroissante, longueur, nom). */
    private fun texteTri(
        pertinence: Int,
        libelle: String,
    ): String =
        "%04d".format(Int.MAX_VALUE - pertinence) +
            "%03d".format(minOf(BORNE_LONGUEUR_TRI, libelle.length)) +
            libelle

    /** Déduplonne (genre+libelle) et borne ; tri par sortText. */
    private fun dedupliquerOrdonner(
        propositions: List<PropositionCompletionKotlin>,
    ): List<PropositionCompletionKotlin> {
        val vus = HashSet<String>()
        val uniques = mutableListOf<PropositionCompletionKotlin>()
        for (proposition in propositions) {
            if (vus.add("${proposition.genre}:${proposition.libelle}")) {
                uniques.add(proposition)
            }
        }
        return uniques
            .sortedBy { it.texteTri }
            .take(LIMITE_PROPOSITIONS)
    }
}
