package jo.lspxml.resources

import jo.lspxml.parse.ParseurXml
import jo.lspxml.parse.Plage
import jo.lspxml.parse.XmlElement
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * INDEX des ressources du PROJET (XE3, LSP_XML.md §3 X5 et §4.1
 * « resources ») — construit depuis les racines sources du classpath
 * (convention AGP : `src/main/res/…`), utilisé par la complétion de
 * VALEURS (`@type/nom`), la résolution de références (hover,
 * définition) et la règle `xml.referenceNonResolue`.
 *
 * Structure : type → nom → déclarants (le `res/values` porteur pour
 * les entrées nommées, le FICHIER pour les ressources de dossier —
 * layouts, drawables…).
 *
 * INCREMENTAL par fichier (empreinte = mtime — l'esprit ADR 0111
 * « un jar inchangé n'est jamais redécodé » adapté aux ressources :
 * un fichier inchangé n'est jamais re-parsé ; une re-définition des
 * MÊMES racines ne re-scanne que ce qui a changé). Les dossiers sont
 * re-listés à chaque définition (un nouveau fichier apparaît — coût
 * d'un listage, pas d'un re-parse).
 *
 * Tolérant (X4/D3) : fichier illisible → IGNORÉ (sous-rapport, jamais
 * d'exception) ; racines sans `res/` → ignorées (bibliothèque JVM).
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — cycle de vie
 * (définir/vider), requêtes (résoudre/noms/types) et scan (familles,
 * fichiers, values, purge) — UNE classe d'index cohérente, pas un
 * regroupement de convenance.
 */
@Suppress("TooManyFunctions")
internal class IndexRessources {
    /** Entrée déclarante : fichier + plage du nom DÉCLARANT. */
    internal class Declaration(
        val fichier: File,
        val plage: Plage,
    )

    /** Type → nom → déclarants (premier porteur en tête, ORDRE DU
     *  DOCUMENT — LinkedHashMap par carte interne, l'ordre des noms
     *  fait la complétion). */
    private val entrees =
        ConcurrentHashMap<String, MutableMap<String, MutableList<Declaration>>>()

    /** Empreinte des fichiers déjà scannés (chemin → mtime). */
    private val empreintes = ConcurrentHashMap<String, Long>()

    /** Racines courantes (une redéfinition des MÊMES racines = delta). */
    @Volatile
    private var racines: List<File> = emptyList()

    /**
     * (Re)définit les racines sources — scan incrémental (mtime par
     * fichier), idempotent : les mêmes racines ne re-parsent que les
     * fichiers changés.
     */
    internal fun definir(nouvellesRacines: List<File>) {
        racines = nouvellesRacines
        val vues = mutableListOf<File>()
        for (racine in nouvellesRacines) {
            val res = racine.resolve("res")
            if (res.isDirectory) vues.add(res)
        }
        val vivants = mutableSetOf<String>()
        for (res in vues) {
            scannerDossierRes(res, vivants)
        }
        // Les déclarants d'un fichier DISPARU partent (l'index ne ment
        // jamais — une référence résolue doit l'être VRAIMENT).
        purger(vivants)
    }

    /** Résout une entrée PROJET (premier déclarant du type+nom). */
    internal fun resoudre(
        type: String,
        nom: String,
    ): Declaration? = entrees[type]?.get(nom)?.firstOrNull()

    /** Noms du TYPE (ordre d'insertion — le document décide). */
    internal fun nomsParType(type: String): List<String> {
        val parNom = entrees[type] ?: return emptyList()
        return synchronized(parNom) { parNom.keys.toList() }
    }

    /** Types présents dans l'index (pour la complétion `@type/`). */
    internal fun types(): Set<String> = entrees.keys.toSet()

    /** L'index est-il CHAUD (des racines ont été scannées) ? */
    internal val estChaud: Boolean
        get() = racines.isNotEmpty()

    /** Vide tout (fermeture de session). */
    internal fun vider() {
        entrees.clear()
        empreintes.clear()
        racines = emptyList()
    }

    // ── Scan ───────────────────────────────────────────────────────

    /** Parcourt `res/` : chaque sous-dossier est une FAMILLE. */
    private fun scannerDossierRes(
        res: File,
        vivants: MutableSet<String>,
    ) {
        val familles = res.listFiles() ?: return
        for (famille in familles.sortedBy { it.name }) {
            val familleValues = famille.name.startsWith("values")
            val connue =
                famille.isDirectory && (familleValues || typeDeDossier(famille.name) != null)
            when {
                !connue -> Unit
                familleValues -> scannerDossierValues(famille, vivants)
                else -> scannerDossierFichiers(famille, vivants)
            }
        }
    }

    /** Famille de FICHIERS (layout, drawable…) : nom = base SANS extension. */
    private fun scannerDossierFichiers(
        famille: File,
        vivants: MutableSet<String>,
    ) {
        val type = typeDeDossier(famille.name) ?: return
        val fichiers = famille.listFiles() ?: return
        for (fichier in fichiers.sortedBy { it.name }) {
            val nom = fichier.name.substringBeforeLast('.')
            if (fichier.isFile && nom.isNotEmpty()) {
                enregistrer(type, nom, Declaration(fichier, Plage(0, 0)), vivants)
            }
        }
    }

    /**
     * Famille `values*` : entrées NOMMÉES (le `name` fait la ressource,
     *  le TAG décide le type — le dossier ne porte PAS de type unique).
     *
     * Exemption detekt ciblée (règle 16) : SwallowedException — un
     * values.xml illisible est IGNORÉ (X4 : sous-rapport, l'exception
     * est le signal) ; LoopWithTooManyJumpStatements — les gardes du
     * scan (non-XML, mtime identique, racine non-resources) sont des
     * états DISTINCTS du protocole de scan.
     */
    @Suppress("SwallowedException", "LoopWithTooManyJumpStatements")
    private fun scannerDossierValues(
        famille: File,
        vivants: MutableSet<String>,
    ) {
        val fichiers = famille.listFiles() ?: return
        for (fichier in fichiers.sortedBy { it.name }) {
            if (!fichier.isFile || !fichier.name.endsWith(".xml", ignoreCase = true)) continue
            val chemin = fichier.path
            val mtime = fichier.lastModified()
            vivants.add(chemin)
            if (empreintes[chemin] == mtime) continue // inchangé : déjà indexé
            // Le fichier a CHANGÉ : ses ANCIENNES entrées partent avant
            // le re-scan (l'index ne garde jamais des entrées périmées).
            retirerFichier(chemin)
            empreintes[chemin] = mtime
            val document =
                try {
                    ParseurXml().analyser(fichier.readText())
                } catch (t: Throwable) {
                    continue // illisible : ignoré (X4)
                }
            for (ressources in document.racines) {
                if (ressources.nom != "resources") continue
                for (enfant in ressources.enfants) {
                    enregistrerEntreeValues(enfant, fichier, vivants)
                }
            }
        }
    }

    /** Une entrée de values : le TAG décide le TYPE, `name` le nom. */
    private fun enregistrerEntreeValues(
        enfant: XmlElement,
        fichier: File,
        vivants: MutableSet<String>,
    ) {
        val nom = enfant.attribut("name")?.valeur ?: return
        val type = typeDEntree(enfant.nom) ?: return
        enregistrer(type, nom, Declaration(fichier, enfant.plageNom), vivants)
    }

    /** Enregistre une entrée (une carte ORDONNÉE par type, un seul
     *  écrivain à la fois — synchronized avec [nomsParType]). */
    private fun enregistrer(
        type: String,
        nom: String,
        declaration: Declaration,
        vivants: MutableSet<String>,
    ) {
        vivants.add(declaration.fichier.path)
        empreintes[declaration.fichier.path] = declaration.fichier.lastModified()
        val parNom =
            entrees.computeIfAbsent(type) { LinkedHashMap() }
        synchronized(parNom) {
            val declarants =
                parNom.getOrPut(nom) { java.util.concurrent.CopyOnWriteArrayList() }
            if (declarants.none { it.fichier == declaration.fichier }) {
                declarants.add(declaration)
            }
        }
    }

    /** Retire les déclarants d'un fichier DISPARU ou CHANGÉ (index honnête). */
    private fun retirerFichier(chemin: String) {
        for (parNom in entrees.values) {
            synchronized(parNom) {
                val iterateur = parNom.entries.iterator()
                while (iterateur.hasNext()) {
                    val declarants = iterateur.next().value
                    // Reconstruction explicite (removeAll(prédicat) n'est pas
                    // garanti sur CopyOnWriteArrayList — clear+addAll le sont).
                    val restants = declarants.filter { it.fichier.path != chemin }
                    declarants.clear()
                    declarants.addAll(restants)
                    if (declarants.isEmpty()) iterateur.remove()
                }
            }
        }
    }

    /** Purge les fichiers DISPARUS de la passe (l'index ne ment jamais). */
    private fun purger(vivants: Set<String>) {
        val disparus = empreintes.keys.filter { it !in vivants }
        for (chemin in disparus) {
            retirerFichier(chemin)
        }
        empreintes.keys.retainAll(vivants)
    }

    /** Type de ressource d'un DOSSIER de res/ (`layout-land` → layout) —
     *  les dossiers `values*` sont traités À PART (entrées nommées). */
    private fun typeDeDossier(nomDossier: String): String? {
        val base = nomDossier.substringBefore('-')
        return if (base in FAMILLES) base else null
    }

    /** Type de ressource d'une ENTRÉE de values (`string-array` → array). */
    private fun typeDEntree(tag: String): String? =
        when (tag) {
            "string-array", "integer-array" -> "array"
            "plurals" -> "plurals"
            "declare-styleable" -> null // les attrs vivent par <attr name>
            "item" -> null // vit sous style/plurals
            else -> if (tag in FAMILLES) tag else null
        }

    private companion object {
        /** Familles de ressources reconnues (convention aapt). */
        private val FAMILLES: Set<String> =
            setOf(
                "anim", "animator", "array", "attr", "bool", "color", "dimen",
                "drawable", "font", "fraction", "id", "integer", "interpolator",
                "layout", "menu", "mipmap", "plurals", "raw", "string",
                "style", "transition", "xml",
            )
    }
}
