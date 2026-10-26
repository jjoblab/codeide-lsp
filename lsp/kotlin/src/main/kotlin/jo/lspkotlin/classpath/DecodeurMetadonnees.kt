package jo.lspkotlin.classpath

import jo.lspkotlin.api.KindEntreeKotlin
import jo.lspkotlin.index.AppelableKotlin
import jo.lspkotlin.index.GenreAppelable
import jo.lspkotlin.index.ParametreAppelable
import kotlin.metadata.visibility
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmFunction
import kotlin.metadata.KmPackage
import kotlin.metadata.KmProperty
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeParameter
import kotlin.metadata.Visibility
import kotlin.metadata.jvm.KotlinClassMetadata
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Décodeur des métadonnées Kotlin d'une entrée de classpath (K3,
 * LSP_KOTLIN.md §4.1 « classpath », §4.2 : « @Metadata décodée seulement
 * pour les jars qui ont un module Kotlin — saut des autres sans décoder
 * une seule classe »).
 *
 * Entrée : jar OU AAR (les jars internes classes.jar et ceux de libs
 * sont parcourus en mémoire — ZipInputStream, aucune extraction sur
 * disque, ADR 0111 §10 pour l'esprit). Sortie : les APPELABLES de
 * premier niveau (fonctions/propriétés, extensions comprises) — les
 * membres de classes restent dans la vue JVM de l'hôte (D4).
 *
 * Porte Kotlin : l'entrée zip doit porter un fichier .kotlin_module sous META-INF
 * — un jar Java saute ENTIER (zéro classe décodée, coût constant).
 *
 * Tolérant (D3) : toute classe illisible/métadonnée incohérente est
 * IGNORÉE (readLenient + garde) — l'index est PARTIEL honnête, jamais
 * faux ; l'entrée absente rend une liste vide.
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions (jar/AAR/
 * classe/métadonnée + fonctions/propriétés = le pipeline du décodage)
 * et NestedBlockDepth (decoderAar : AAR → jars internes → entrées —
 * trois niveaux de conteneurs PAR CONCEPTION, précédent AnalyseurClass-
 * pathSysteme d':lsp:index).
 */
@Suppress("TooManyFunctions", "NestedBlockDepth", "ReturnCount")
internal object DecodeurMetadonnees {
    /**
     * Décode tous les appelables de premier niveau d'une entrée.
     *
     * @param fichier jar ou AAR sur disque.
     * @param kind nature du conteneur.
     * @return les appelables (dédoublonnés par (genre, nom, recepteur,
     *         arité) — les surcharges restent, les doublons de pont
     *         synthétiques partent).
     */
    internal fun decoder(
        fichier: File,
        kind: KindEntreeKotlin,
    ): List<AppelableKotlin> =
        when (kind) {
            KindEntreeKotlin.JAR -> decoderJar(fichier)
            KindEntreeKotlin.AAR -> decoderAar(fichier)
        }

    /** Jar plat : porte Kotlin puis classes une à une. */
    private fun decoderJar(fichier: File): List<AppelableKotlin> {
        if (!fichier.isFile) return emptyList()
        val resultat = mutableListOf<AppelableKotlin>()
        ZipFile(fichier).use { zip ->
            if (!porteKotlin(zip.entries().asSequence().map { it.name })) return emptyList()
            val entrees = zip.entries().asSequence().filter { it.name.endsWith(SUFFIXE_CLASSE) }
            for (entree in entrees) {
                val octets = zip.getInputStream(entree).use { it.readBytes() }
                resultat += appelablesDepuisOctets(octets)
            }
        }
        return resultat.dedupliques()
    }

    /** AAR : jars internes parcourus EN MÉMOIRE (classes.jar et jars de libs). */
    private fun decoderAar(fichier: File): List<AppelableKotlin> {
        if (!fichier.isFile) return emptyList()
        val resultat = mutableListOf<AppelableKotlin>()
        ZipFile(fichier).use { aar ->
            for (jar in aar.entries().asSequence().filter { estJarInterne(it.name) }) {
                val octetsJar = aar.getInputStream(jar).use { it.readBytes() }
                // UN SEUL passage : noms (porte Kotlin) + octets des classes.
                val noms = mutableListOf<String>()
                val octetsClasses = mutableListOf<ByteArray>()
                ZipInputStream(ByteArrayInputStream(octetsJar)).use { interne ->
                    var entree = interne.nextEntry
                    while (entree != null) {
                        noms.add(entree.name)
                        if (entree.name.endsWith(SUFFIXE_CLASSE)) {
                            octetsClasses.add(interne.readBytes())
                        }
                        entree = interne.nextEntry
                    }
                }
                if (!porteKotlin(noms.asSequence())) continue
                octetsClasses.forEach { octets -> resultat += appelablesDepuisOctets(octets) }
            }
        }
        return resultat.dedupliques()
    }

    /** classes.jar et jars de libs (AAR v8, ADR 0111 §10). */
    private fun estJarInterne(nom: String): Boolean =
        nom == "classes.jar" || (nom.startsWith("libs/") && nom.endsWith(".jar"))

    /** L'entrée porte-t-elle un module Kotlin (fichier .kotlin_module sous META-INF) ? */
    private fun porteKotlin(noms: Sequence<String>): Boolean =
        noms.any { it.startsWith("META-INF/") && it.endsWith(".kotlin_module") }

    /**
     * Appelables d'UN fichier de classe : lecture brute (identité +
     * @Metadata) puis readLenient — seules les FACADES de fichiers
     * (FileFacade, MultiFileClassPart) portent des appelables de
     * premier niveau ; les classes réelles/synthétiques rendent vide
     * (leurs membres vivent dans la vue JVM).
     */
    private fun appelablesDepuisOctets(octets: ByteArray): List<AppelableKotlin> {
        val infos = LecteurClassBrut.lire(octets) ?: return emptyList()
        val metadonnees = infos.metadonnees ?: return emptyList()
        val kmPackage: KmPackage =
            when (val lu = KotlinClassMetadata.readLenient(metadonnees)) {
                is KotlinClassMetadata.FileFacade -> lu.kmPackage
                is KotlinClassMetadata.MultiFileClassPart -> lu.kmPackage
                else -> return emptyList()
            }
        val paquet = infos.fqcn.substringBeforeLast('.')
        val conteneur = infos.fqcn
        return kmPackage.functions.mapNotNull { appelableDe(it, paquet, conteneur) } +
            kmPackage.properties.mapNotNull { appelableDe(it, paquet, conteneur) }
    }

    /** Fonction → appelable (extensions : récepteur FQCN, null sinon). */
    private fun appelableDe(
        fonction: KmFunction,
        paquet: String,
        conteneur: String,
    ): AppelableKotlin? {
        if (!estIndexable(fonction.visibility)) return null
        return AppelableKotlin(
            genre = GenreAppelable.FONCTION,
            nom = fonction.name,
            paquet = paquet,
            recepteur = recepteurFqcn(fonction.receiverParameterType),
            typeRetour = rendreType(fonction.returnType, fonction.typeParameters),
            parametres =
                fonction.valueParameters.map {
                    ParametreAppelable(it.name, rendreType(it.type, fonction.typeParameters))
                },
            conteneur = conteneur,
        )
    }

    /** Propriété → appelable (extensions : récepteur FQCN, null sinon). */
    private fun appelableDe(
        propriete: KmProperty,
        paquet: String,
        conteneur: String,
    ): AppelableKotlin? {
        if (!estIndexable(propriete.visibility)) return null
        return AppelableKotlin(
            genre = GenreAppelable.PROPRIETE,
            nom = propriete.name,
            paquet = paquet,
            recepteur = recepteurFqcn(propriete.receiverParameterType),
            typeRetour = rendreType(propriete.returnType, propriete.typeParameters),
            parametres = emptyList(),
            conteneur = conteneur,
        )
    }

    /**
     * Visibilité indexable : PUBLIC et PROTECTED (offrable) — INTERNAL
     * est propre à un module, PRIVATE/LOCAL invisibles (D3 : une
     * proposition impossible à résoudre est un faux positif).
     */
    private fun estIndexable(visibilite: Visibility): Boolean =
        visibilite == Visibility.PUBLIC || visibilite == Visibility.PROTECTED

    /**
     * FQCN du récepteur d'une extension — uniquement les types de CLASSE
     * simples (`fun TextView.…`) : un récepteur générique (`fun <T>
     * List<T>.…`) ou de paramètre de type n'est pas une clé stable
     * (sous-rapport D3, K4 pour les génériques).
     */
    private fun recepteurFqcn(type: KmType?): String? =
        (type?.classifier as? KmClassifier.Class)?.name?.remplacerSeparateurs()

    /**
     * Rend un type en affichage Kotlin court : nom simple du
     * classificateur + arguments rendus récursivement (`List<String>`),
     * ou le NOM du paramètre de type nu (« T » — vague 4 : la MARQUE
     * du générique lisible par le diagnostic). Null pour un type non
     * représentable simplement (flexible…) — le détail de complétion
     * omet alors le type.
     */
    private fun rendreType(
        type: KmType?,
        parametresGeneriques: List<KmTypeParameter> = emptyList(),
    ): String? {
        if (type == null) return null
        // VAGUE 4 (retour utilisateurs 25/10/2026 — U1 « showSnackbar »
        // après remember) : un retour PARAMÈTRE DE TYPE (`fun <T>
        // remember(calculation: () -> T): T`) est rendu par son NOM
        // (« T ») — la MARQUE du générique lisible par le diagnostic
        // (les types concrets du runtime portent 3+ lettres). Le
        // classifier TypeParameter était AVANT muet (null) : toute
        // la famille remember/run/let restait non typée.
        val classifier = type.classifier
        if (classifier is KmClassifier.TypeParameter) {
            return parametresGeneriques.firstOrNull { it.id == classifier.id }?.name
        }
        // Les noms de classificateurs sont au format JVM (kotlin/String,
        // slashes) — normalisés en FQCN à points pour TOUT le moteur.
        val nom = (classifier as? KmClassifier.Class)?.name?.remplacerSeparateurs() ?: return null
        val rendu = StringBuilder(nom.substringAfterLast('.'))
        if (type.arguments.isNotEmpty()) {
            rendu.append('<')
            rendu.append(type.arguments.joinToString(", ") { projection ->
                rendreType(projection.type, parametresGeneriques) ?: "?"
            })
            rendu.append('>')
        }
        return rendu.toString()
    }

    /** Déduplique les ponts synthétiques (même genre/nom/récepteur/arité). */
    private fun List<AppelableKotlin>.dedupliques(): List<AppelableKotlin> {
        val vus = HashSet<Int>()
        return filter { appelable ->
            vus.add(
                appelable.genre.ordinal * FACTEUR_GENRE +
                    appelable.nom.hashCode() * FACTEUR_NOM +
                    (appelable.recepteur?.hashCode() ?: 0) +
                    appelable.parametres.size,
            )
        }
    }

    /** Normalise un nom de classificateur JVM (« kotlin/String » → « kotlin.String »). */
    private fun String.remplacerSeparateurs(): String = replace('/', '.')

    private const val SUFFIXE_CLASSE: String = ".class"

    /** Facteurs du hachage de déduplication (mélange des composantes). */
    private const val FACTEUR_GENRE: Int = 31

    private const val FACTEUR_NOM: Int = 7
}
