package jo.codeide.lsp.model

import jo.codeide.tooling.protocol.ClasspathEntry
import jo.codeide.tooling.protocol.ClasspathKind
import jo.codeide.tooling.protocol.ClasspathModule
import jo.codeide.tooling.protocol.ClasspathResult

/** Faux lecteur de statistiques — fichiers en mémoire (taille, mtime). */
internal class FauxStats {
    val fichiers = mutableMapOf<String, FauxFichier>()

    fun ajouter(
        chemin: String,
        taille: Long = 100L,
        mtime: Long = 1_000L,
    ): FauxStats {
        fichiers[chemin] = FauxFichier(taille, mtime, "contenu:$taille")
        return this
    }

    fun retoucher(
        chemin: String,
        taille: Long? = null,
        mtime: Long? = null,
    ) {
        val actuel = fichiers.getValue(chemin)
        fichiers[chemin] = actuel.copy(taille = taille ?: actuel.taille, mtime = mtime ?: actuel.mtime)
    }

    fun supprimer(chemin: String) {
        fichiers.remove(chemin)
    }

    val lecteur: LecteurStatistiquesFichier =
        object : LecteurStatistiquesFichier {
            override fun existe(chemin: String): Boolean = chemin in fichiers

            override fun taille(chemin: String): Long = fichiers[chemin]?.taille ?: 0L

            override fun mtime(chemin: String): Long = fichiers[chemin]?.mtime ?: 0L

            override fun empreintePartielle(chemin: String): String? = fichiers[chemin]?.contenu
        }

    data class FauxFichier(
        val taille: Long,
        val mtime: Long,
        val contenu: String,
    )
}

/** Bâtisseurs de fixtures du protocole v8 pour les tests du modèle LSP. */
internal object FixturesClasspath {
    fun resultat(
        projectDir: String = "/projets/essai",
        modules: List<ClasspathModule>,
    ): ClasspathResult =
        ClasspathResult(
            id = "id-1",
            protocolVersion = 8,
            projectDir = projectDir,
            modules = modules,
        )

    // LongParameterList assumé : bâtisseur de fixtures de test — chaque
    // paramètre épingle un champ du protocole v8.
    @Suppress("LongParameterList")
    fun module(
        nom: String,
        sources: List<String> = emptyList(),
        sourcesTest: List<String>? = emptyList(),
        entrees: List<ClasspathEntry> = emptyList(),
        generes: List<String>? = null,
        androidJar: String? = null,
        variante: String? = null,
        ignore: Boolean = false,
        raisonIgnore: String? = null,
        avertissements: List<String>? = null,
    ): ClasspathModule =
        ClasspathModule(
            name = nom,
            sourceDirs = sources,
            sourceDirsTest = sourcesTest,
            entries = entrees,
            nbJars = null,
            nbAars = null,
            nbSources = null,
            varianteAndroid = variante,
            nbDependancesProjet = null,
            fichiersGeneres = generes,
            androidJar = androidJar,
            ignore = ignore,
            raisonIgnore = raisonIgnore,
            avertissements = avertissements,
        )

    fun jar(
        chemin: String,
        portee: String? = "compile",
    ): ClasspathEntry = ClasspathEntry(path = chemin, kind = ClasspathKind.JAR, scope = portee)

    fun aarNormalise(
        jarExtrait: String,
        aarOrigine: String,
        portee: String? = "compile",
    ): ClasspathEntry = ClasspathEntry(path = jarExtrait, kind = ClasspathKind.JAR, scope = portee, aar = aarOrigine)

    fun aarBrut(chemin: String): ClasspathEntry = ClasspathEntry(path = chemin, kind = ClasspathKind.AAR)

    fun dossier(chemin: String): ClasspathEntry = ClasspathEntry(path = chemin, kind = ClasspathKind.DOSSIER)

    fun moduleFrere(
        cible: String,
        portee: String? = "compile",
    ): ClasspathEntry = ClasspathEntry(path = cible, kind = ClasspathKind.MODULE, scope = portee)

    /** Chemin de cache Maven réaliste (sha1 du chemin = identité). */
    fun cheminMaven(
        groupe: String,
        artefact: String,
        version: String,
    ): String {
        val sha1 = "a" + "$groupe$artefact$version".padStart(39, 'b')
        return "/home/.gradle/caches/modules-2/files-2.1/$groupe/$artefact/$version/$sha1/$artefact-$version.jar"
    }

    /** Chemin de transform réaliste (AAR explosé). */
    private const val HACHE_TRANSFORM = "91d8547094a6bf7615ba0dae817fc867"

    /** Chemin de transform réaliste (AAR explosé). */
    fun cheminTransform(
        artefact: String,
        version: String,
    ): String = "/home/.gradle/caches/9.7.1/transforms/$HACHE_TRANSFORM/transformed/$artefact-$version/jars/classes.jar"
}
