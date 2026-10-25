package jo.lspkotlin.synthetics

import jo.lspkotlin.resolve.DeclarationProjet
import jo.lspkotlin.resolve.GenreDeclaration
import java.io.File

/**
 * Classes SYNTHÉTIQUES Android (K5, LSP_KOTLIN.md §5, décision D5) :
 * `R`, `BuildConfig`, ViewBinding et DataBinding entrent dans le modèle
 * de symboles AVANT TOUT BUILD — le délai « databinding/R invisibles
 * avant build » (dette v0.108.0 côté Java) disparaît côté Kotlin sans
 * une ligne de génération.
 *
 * GENERALISATION de la sonde `hasGeneratedDatabindingClasses`
 * (v0.108.0, LSP Java) : la COUPURE reste la même — dès qu'une classe
 * générée RÉELLE existe dans le scan (racines `generes` post-build),
 * le groupe synthétique HOMONYME s'EFFACE (la sortie du build est la
 * source de vérité, jamais contredite) ; la session applique la
 * coupure au moment de fusionner.
 *
 * Sources (toutes DÉMONTRABLES, zéro devinette D3) :
 *  - racine `src/main/java|kotlin` → répertoire frère `src/main/res`
 *    et manifeste `src/main/AndroidManifest.xml` (convention AGP —
 *    une racine hors convention ne produit RIEN, sous-rapport) ;
 *  - paquet du manifeste = espace de noms (manifeste sans attribut
 *    `package` : aucun groupe — jamais de FQCN inventé) ;
 *  - layouts (`res/layout*`) pour ViewBinding/DataBinding + `R.layout`
 *    et `R.id` ; `values*` pour `R.string`/`R.color`/… ; fichiers de
 *    `drawable*`/`mipmap*`/`raw*`/`anim*`.
 *
 * ViewBinding : la classe est synthétisée pour tout layout à racine
 * VUE (l'indicateur `viewBinding` du build.gradle n'est PAS lisible
 * depuis les sources — si le drapeau est éteint, la référence erronée
 * sera silencieusement acceptée à l'édition puis ATTRAPÉE PAR LE
 * BUILD : c'est un sous-rapport de diagnostic, jamais un faux
 * positif). DataBinding : la racine `<layout>` du fichier EST la
 * preuve (AGP refuse de compiler un tel layout sans dataBinding).
 *
 * Partagé par le moteur, paresseux, invalidé au changement de racines
 * (même patron que ScanProjet) ; bornes de fichiers partout (D3).
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — un
 * FABRIQUEUR par famille de groupes (R, BuildConfig, bindings) plus
 * les petites déclarations : le catalogue des sources synthétiques,
 * patron SymbolesProjet/ScanProjet.
 */
@Suppress("TooManyFunctions")
internal class ClassesSynthetiques {
    /**
     * Verrou interne du calcul paresseux (ADR 0212) : les requêtes de
     * session tournent désormais en PARALLÈLE sous verrou de lecture —
     * le cache paresseux (`racines`/`groupes`/`calcule`) est muté et lu
     * hors de tout verrou de session, il DOIT se synchroniser lui-même
     * (le calcul reste paresseux, UNE seule construction à la fois,
     * les lecteurs parallèles attendent au pire UNE construction).
     */
    private val verrouCalcul = Any()

    /** Racines courantes (identité d'invalidation) — sous [verrouCalcul]. */
    private var racines: List<File> = emptyList()

    /** Groupes du dernier calcul (vide avant le premier) — sous [verrouCalcul]. */
    private var groupes: List<GroupeSynthetique> = emptyList()

    /** Calcul effectué pour les racines courantes ? — sous [verrouCalcul]. */
    private var calcule = false

    /**
     * (Re)définit les racines sources — mêmes racines : NO-OP chaud ;
     * changement : re-calcul paresseux au prochain [groupes].
     */
    internal fun definirRacines(nouvelles: List<File>) {
        synchronized(verrouCalcul) {
            if (nouvelles == racines) return
            racines = nouvelles
            groupes = emptyList()
            calcule = false
        }
    }

    /**
     * Groupes synthétiques courants — calcul paresseux au premier accès
     * (le coût est celui du parcours des ressources, borné).
     */
    internal fun groupes(): List<GroupeSynthetique> =
        synchronized(verrouCalcul) {
            if (!calcule) {
                groupes = construire(racines)
                calcule = true
            }
            groupes
        }

    /**
     * Un groupe synthétique — toutes les déclarations d'UNE classe de
     * tête : la coupure de la session retire le groupe ENTIER dès que
     * la classe homonyme RÉELLE existe (la classe générée porte ses
     * propres membres, garder les nôtres créerait des doublons).
     *
     * @property fqcn FQCN de la classe de tête (`ns.R`).
     * @property declarations la classe + ses membres imbriqués.
     */
    internal class GroupeSynthetique(
        internal val fqcn: String,
        internal val declarations: List<DeclarationProjet>,
    )

    /** Construit tous les groupes des racines (paquets distincts fusionnés).
 *
 * Exemption detekt ciblée (règle 16) : LoopWithTooManyJumpStatements —
 * les continue de la racine hors convention et du paquet déjà vu sont
 * les bornes du catalogue (une branche par cas, patron ScanProjet). */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun construire(racines: List<File>): List<GroupeSynthetique> {
        val groupes = mutableListOf<GroupeSynthetique>()
        val paquetsVus = mutableSetOf<String>()
        for (racine in racines) {
            val srcMain = srcMainDe(racine) ?: continue
            val res = File(srcMain, "res").takeIf { it.isDirectory } ?: continue
            val manifest = File(srcMain, "AndroidManifest.xml").takeIf { it.isFile } ?: continue
            val paquet = AnalyseRessourcesAndroid.paquetDuManifest(manifest) ?: continue
            if (!paquetsVus.add(paquet)) continue
            groupes += groupesDuPaquet(paquet, res)
        }
        return groupes
    }

    /**
     * Répertoire `src/main` d'une racine source Java/Kotlin (convention
     * AGP — une racine hors convention ne produit RIEN, sous-rapport).
     */
    private fun srcMainDe(racine: File): File? =
        racine.parentFile
            ?.takeIf { parent -> parent.name == "main" }
            ?.takeIf { racine.name == "java" || racine.name == "kotlin" }

    /**
     * Groupes d'UN paquet : R, BuildConfig, bindings par layout.
     *
     * Exemption detekt ciblée (règle 16) : LongMethod — le catalogue
     * des familles de groupes, une branche par famille (patron
     * SymbolesProjet.extraire).
     */
    @Suppress("LongMethod")
    private fun groupesDuPaquet(
        paquet: String,
        res: File,
    ): List<GroupeSynthetique> {
        val groupes = mutableListOf<GroupeSynthetique>()
        val layouts = AnalyseRessourcesAndroid.layouts(res)
        // R : familles DÉMONTRÉES seules (une sous-classe vide n'existe pas).
        val familles = famillesR(res, layouts)
        if (familles.isNotEmpty()) groupes += groupeR(paquet, familles)
        groupes += groupeBuildConfig(paquet)
        for (layout in layouts) {
            if (layout.racine == "merge") continue
            groupes += groupeBinding(paquet, layout)
        }
        return groupes
    }

    /** Familles R remplies : `values` + noms de fichiers + layouts + ids. */
    private fun famillesR(
        res: File,
        layouts: List<AnalyseRessourcesAndroid.LayoutAnalyse>,
    ): Map<String, List<String>> {
        val familles = LinkedHashMap<String, List<String>>()
        AnalyseRessourcesAndroid.entreesValeurs(res).forEach { (famille, noms) ->
            familles[famille] = noms
        }
        val nomsLayouts = layouts.map { it.fichier.name.substringBeforeLast('.') }
        if (nomsLayouts.isNotEmpty()) familles["layout"] = nomsLayouts.distinct()
        val ids =
            layouts.flatMap { analyse -> analyse.ids.keys }.distinct().let { ids ->
                if (ids.isEmpty()) null else ids
            }
        ids?.let { familles["id"] = it }
        for ((prefixe, famille) in PREFIXES_FICHIERS) {
            val noms = AnalyseRessourcesAndroid.nomsFichiers(res, prefixe).distinct()
            if (noms.isNotEmpty()) familles[famille] = noms
        }
        return familles
    }

    /**
     * Groupe `R` : classe de tête + sous-objets par famille + une
     * propriété `Int` par entrée (les membres vivent du FQCN de leur
     * conteneur — clés de la complétion membre projet).
     */
    private fun groupeR(
        paquet: String,
        familles: Map<String, List<String>>,
    ): GroupeSynthetique {
        val fqcnR = "$paquet.R"
        val declarations = mutableListOf<DeclarationProjet>()
        declarations += classe(paquet, "R")
        for ((famille, noms) in familles.toSortedMap()) {
            val fqcnFamille = "$fqcnR.$famille"
            declarations += DeclarationProjet(
                genre = GenreDeclaration.OBJET,
                nom = famille,
                uri = "",
                debutNom = 0,
                finNom = 0,
                paquet = paquet,
                recepteur = null,
                typeRetour = fqcnFamille,
                parametres = emptyList(),
                conteneur = fqcnR,
            )
            noms.take(BORN_ENTREES_FAMILLE).forEach { nom ->
                declarations += DeclarationProjet(
                    genre = GenreDeclaration.PROPRIETE,
                    nom = nom,
                    uri = "",
                    debutNom = 0,
                    finNom = 0,
                    paquet = paquet,
                    recepteur = null,
                    typeRetour = "Int",
                    parametres = emptyList(),
                    conteneur = fqcnFamille,
                )
            }
        }
        return GroupeSynthetique(fqcnR, declarations)
    }

    /**
     * Groupe `BuildConfig` : la CLASSE seule — les champs réels
     * (APPLICATION_ID, VERSION_NAME, DEBUG…) appartiennent au build,
     * les inventer serait mentir (D3) : le type résout, les membres
     * sous-rapportent.
     */
    private fun groupeBuildConfig(paquet: String): GroupeSynthetique {
        val fqcn = "$paquet.BuildConfig"
        return GroupeSynthetique(fqcn, listOf(classe(paquet, "BuildConfig")))
    }

    /**
     * Groupe Binding d'un layout — ViewBinding (`ns.NomBinding`) ou
     * DataBinding (`ns.databind…` : AGP loge ces classes sous le
     * sous-paquet `databinding`).
     *
     * Champs : une propriété par `@+id` (type du porteur), `root` (type
     * de la racine), variables `<data>` (DataBinding) ; méthodes
     * `inflate`/`bind` au retour du binding — arité des METHODES
     * vararg (les vraies signatures AGP ont des surcharges que nous
     * ne modélisons pas : le contrôle d'arité sous-rapporte, il ne
     * PEUT pas inventer) ; constructeur jamais contrôlé (privé chez
     * AGP — un appel est une erreur de build, pas de notrefait).
     */
    private fun groupeBinding(
        paquet: String,
        layout: AnalyseRessourcesAndroid.LayoutAnalyse,
    ): GroupeSynthetique {
        val nomClasse = nomBinding(layout.fichier.name)
        // ADR 0219 : AGP génère TOUJOURS les bindings dans le sous-paquet
        // `databinding` — ViewBinding compris (le template Android Studio
        // « Empty Views Activity » importe com.ex.app.databinding.
        // ActivityMainBinding) ; l'ancienne règle « databinding pour le
        // DataBinding seul » ne matchait JAMAIS l'import réel du ViewBinding.
        val paquetClasse = "$paquet.databinding"
        val fqcn = "$paquetClasse.$nomClasse"
        val uri = layout.fichier.toURI().toString()
        val declarations = mutableListOf<DeclarationProjet>()
        declarations += classe(paquetClasse, nomClasse, uri, aVararg = true)
        for ((nom, type) in layout.ids) {
            declarations += DeclarationProjet(
                genre = GenreDeclaration.PROPRIETE,
                nom = nom,
                uri = uri,
                debutNom = 0,
                finNom = 0,
                paquet = paquetClasse,
                recepteur = null,
                typeRetour = type,
                parametres = emptyList(),
                conteneur = fqcn,
            )
        }
        if (layout.dataBinding) {
            for ((nom, type) in layout.variables) {
                declarations += DeclarationProjet(
                    genre = GenreDeclaration.PROPRIETE,
                    nom = nom,
                    uri = uri,
                    debutNom = 0,
                    finNom = 0,
                    paquet = paquetClasse,
                    recepteur = null,
                    typeRetour = type,
                    parametres = emptyList(),
                    conteneur = fqcn,
                )
            }
        } else {
            declarations += DeclarationProjet(
                genre = GenreDeclaration.PROPRIETE,
                nom = "root",
                uri = uri,
                debutNom = 0,
                finNom = 0,
                paquet = paquetClasse,
                recepteur = null,
                typeRetour = AnalyseRessourcesAndroid.typeVue(layout.racine),
                parametres = emptyList(),
                conteneur = fqcn,
            )
        }
        declarations += fonctionBinding(fqcn, paquetClasse, uri, "inflate", nomClasse)
        declarations += fonctionBinding(fqcn, paquetClasse, uri, "bind", nomClasse)
        return GroupeSynthetique(fqcn, declarations)
    }

    /** Classe de tête d'un groupe (constructeur jamais contrôlé si [aVararg]). */
    private fun classe(
        paquet: String,
        nom: String,
        uri: String = "",
        aVararg: Boolean = false,
    ): DeclarationProjet =
        DeclarationProjet(
            genre = GenreDeclaration.CLASSE,
            nom = nom,
            uri = uri,
            debutNom = 0,
            finNom = 0,
            paquet = paquet,
            recepteur = null,
            typeRetour = null,
            parametres = emptyList(),
            conteneur = null,
            aVararg = aVararg,
        )

    /** Méthode `inflate`/`bind` d'un binding (arité vararg — surcharges AGP). */
    private fun fonctionBinding(
        fqcn: String,
        paquet: String,
        uri: String,
        nom: String,
        nomClasse: String,
    ): DeclarationProjet =
        DeclarationProjet(
            genre = GenreDeclaration.FONCTION,
            nom = nom,
            uri = uri,
            debutNom = 0,
            finNom = 0,
            paquet = paquet,
            recepteur = null,
            typeRetour = nomClasse,
            parametres = emptyList(),
            conteneur = fqcn,
            aVararg = true,
        )

    /** `activity_main.xml` → `ActivityMainBinding` (Pascal, séparateurs fondus). */
    private fun nomBinding(nomFichier: String): String {
        val pascal =
            nomFichier.substringBeforeLast('.')
                .split('_', '-', '.')
                .filter { it.isNotBlank() }
                .joinToString("") { morceau -> morceau.replaceFirstChar { it.uppercase() } }
        return "${pascal}Binding"
    }

    private companion object {
        /** Entrées par famille R (troncation honnête au-delà). */
        private const val BORN_ENTREES_FAMILLE: Int = 300

        /** Familles R lues depuis les NOMS DE FICHIERS de ressources. */
        private val PREFIXES_FICHIERS: List<Pair<String, String>> =
            listOf(
                Pair("drawable", "drawable"),
                Pair("mipmap", "mipmap"),
                Pair("raw", "raw"),
                Pair("anim", "anim"),
                Pair("color", "color"),
            )
    }
}
