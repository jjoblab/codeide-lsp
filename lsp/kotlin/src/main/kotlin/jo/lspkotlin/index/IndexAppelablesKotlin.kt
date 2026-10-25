package jo.lspkotlin.index

import jo.lspkotlin.api.EntreeClasspathKotlin
import jo.lspkotlin.classpath.DecodeurMetadonnees
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Index des APPELABLES Kotlin du classpath (K3, LSP_KOTLIN.md §4.1
 * « index » — homologue Kotlin du `KotlinCallableIndex` du projet tiers de référence,
 * clés taggées `top:`/`ext:`).
 *
 * PARTAGÉ PAR LE MOTEUR (toutes les sessions d'un projet) : les
 * définitions de classpath arrivent répétées (une par sync + une par
 * connexion avant l'ADR 0211) — les SEGMENTS DISQUE par empreinte font
 * qu'un jar inchangé n'est JAMAIS redécodé (motif ADR 0111 §1-§3 : l'identité du
 * contenu EST la clé ; ici les clés viennent de l'hôte W6, le moteur
 * ne re-hache que le NOM de segment).
 *
 * ADR 0211 (demande D1 de CodeIDE) — TROIS leviers nouveaux :
 *
 * 1. **Garde d'empreintes** : `definir` aux MÊMES empreintes dans le
 *    MÊME ordre est un NO-OP CHAUD (la liste ordonnée qui a construit
 *    le snapshot courant est retenue — l'ordre fait la préséance des
 *    candidats, il fait partie de l'identité). Chaque changement
 *    d'onglet `.kt` (branchement de session) ne paie plus RIEN.
 * 2. **Cache mémoire par empreinte** : un segment lu une fois ne
 *    retourne JAMAIS au disque, même quand la garde échoue (classpath
 *    réordonné, ajout/retrait d'une entrée) — seules les empreintes
 *    NOUVELLES paient la lecture disque, et un échec n'est JAMAIS
 *    mis en cache (une reprise ciblée le retente toujours, ADR 0214).
 * 3. **Contrat de couverture** : [couvre] dit si le snapshot courant
 *    couvre une liste d'entrées ; [nourrissageMoteur] dit si le moteur
 *    a PRIS EN MAIN le nourrissage (ADR 0211) — la session s'en sert
 *    pour se BRANCHER sans re-lire au lieu de re-définir.
 *
 * Stockage : `<dossierIndex>/kotlin-appelables/<sha256(empreinte)>.kidx`
 * — sous-répertoire DÉDIÉ de l'index partagé (jamais de collision avec
 * les segments `:lsp:index`) ; `dossierIndex` null = index en mémoire
 * seule (tests, environnements éphémères). Un changement de
 * `dossierIndex` N'INVALIDE RIEN : l'empreinte EST l'identité du
 * contenu, où que le segment dorme.
 *
 * Auto-guérison ADR 0111 §4 : un segment corrompu/moisi est
 * re-décodé et réécrit — l'index est un CACHE, jamais une autorité.
 *
 * Concurrence : snapshot IMMUBLE publié volatil — les requêtes lisent
 * l'état cohérent d'une définition, les redéfinitions remplacent TOUT ;
 * le cache mémoire est une ConcurrentHashMap (lectures sans verrou).
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — le catalogue
 * de requêtes (3) + le cache de segments (charge/lecture/écriture/
 * sérialisation) + le contrat de passage (garde/couverture/issue) :
 * chaque fonction est UNE section du format KCIDX ou UN verbe du
 * contrat ADR 0211/0213/0214, même patron que VueIndex/ServiceIndexDisque
 * d':lsp:index.
 */
@Suppress("TooManyFunctions")
internal class IndexAppelablesKotlin {
    /** Snapshot courant (vide avant la première définition). */
    @Volatile
    private var snapshot: Snapshot = Snapshot(emptyMap(), emptyList(), emptyMap(), emptyList())

    /**
     * Empreintes (ORDONNÉES) qui ont construit le snapshot courant —
     * la garde du no-op chaud et la clé de [couvre] (ADR 0211).
     */
    @Volatile
    private var empreintesSnapshot: List<String> = emptyList()

    /**
     * Cache MÉMOIRE par empreinte (ADR 0211) — un segment lu une fois
     * ne retourne plus au disque ; les échecs n'y entrent JAMAIS (une
     * reprise ciblée les retente, ADR 0214). BORNÉ : au-delà du plafond
     * la table est vidangée (une sync qui enchaîne des classpaths
     * massivement différents paie au plus une relecture complète).
     */
    private val cacheSegments = ConcurrentHashMap<String, List<AppelableKotlin>>()

    /**
     * Le moteur a-t-il PRIS EN MAIN le nourrissage (ADR 0211) ? Posé au
     * premier `definirClasspath` moteur, jamais retiré : les sessions
     * se branchent alors SANS jamais re-définir en synchrone (le
     * supplier de connexion reste gratuit, même après un passage
     * annulé ou partiel).
     */
    @Volatile
    private var nourrissageMoteur: Boolean = false

    /** Le moteur possède-t-il le nourrissage (branchement de session). */
    internal val nourrissageParMoteur: Boolean
        get() = nourrissageMoteur

    /** Issue d'une entrée du passage — miroir de l'état public du travail. */
    internal enum class IssueSegment {
        /** Reprise sans reconstruction (cache mémoire ou segment disque — jamais redécodé). */
        REPRIS,

        /** Décodage à froid + écriture du segment. */
        TERMINE,

        /** Illisible : écartée honnêtement (PARTIEL, jamais d'exception). */
        ECARTE,
    }

    /**
     * Observation du passage (ADR 0213/0214) — UNE méthode pour toute
     * la vie d'une entrée : `issue == null` = l'entrée devient ACTIVE
     * (avant traitement — un décodage à froid peut durer des secondes,
     * l'écran montre l'entrée en cours), sinon l'entrée aboutit à
     * [issue] ; le retour `false` ANNULE le passage (l'entrée courante
     * reste ACQUISE, les suivantes pendent — ADR 0214).
     */
    internal fun interface ObservationPassage {
        fun surUnite(
            entree: EntreeClasspathKotlin,
            issue: IssueSegment?,
        ): Boolean
    }

    /** Résultat d'une définition (le moteur en fait un événement, ADR 0213). */
    internal enum class ResultatDefin {
        /** Snapshot reconstruit (au moins une empreinte lue). */
        RECONSTRUIT,

        /** No-op chaud : le snapshot couvrait déjà ces empreintes. */
        DEJA_COUVERT,

        /** Passé PARTIEL : annulation coopérative en cours de route (ADR 0214). */
        PARTIEL_ANNULE,
    }

    /**
     * (Re)définit l'index depuis les entrées de classpath — chaque
     * empreinte est servie depuis le CACHE MÉMOIRE, sinon son segment
     * disque, sinon décodée à froid puis persistée. Les entrées
     * absentes du disque sont ignorées (jamais d'exception — D3, la
     * vue hôte a déjà filtré l'existence).
     *
     * ADR 0211 : mêmes empreintes dans l'ordre ⇒ NO-OP CHAUD
     * ([ResultatDefin.DEJA_COUVERT], aucune lecture).
     *
     * @param entrees entrées (jars/AAR) dans l'ordre du classpath.
     * @param dossierIndex répertoire de l'index partagé, `null` pour
     *        la mémoire seule.
     * @param observation observateur du passage (`null` = silence —
     *        l'usage session directe sans écran).
     * @return le résultat du passage.
     *
     * Exemption detekt ciblée (règle 16) : LoopWithTooManyJumpStatements
     * — le break de l'ANNULATION (ADR 0214 : les suivantes pendent) et
     * le continue de l'ÉCART (l'entrée n'entre pas dans le snapshot)
     * sont les DEUX bornes du passage.
     */
    @Suppress("LoopWithTooManyJumpStatements")
    internal fun definir(
        entrees: List<EntreeClasspathKotlin>,
        dossierIndex: File?,
        observation: ObservationPassage? = null,
    ): ResultatDefin {
        val empreintes = entrees.map { it.empreinte }
        if (empreintes == empreintesSnapshot) return ResultatDefin.DEJA_COUVERT
        var annule = false
        val parNom = HashMap<String, MutableList<AppelableKotlin>>()
        val extensions = HashMap<String, MutableList<AppelableKotlin>>()
        val empreintesChargees = mutableListOf<String>()
        for (entree in entrees) {
            if (annule) {
                // Annulation déjà reçue : l'entrée reste PENDANTE (non
                // traitée) — le snapshot partiel est la vérité.
                break
            }
            observation?.surUnite(entree, null)
            val lecture = charger(entree, dossierIndex)
            val issue =
                when {
                    lecture.appelables == null -> IssueSegment.ECARTE
                    lecture.reprise -> IssueSegment.REPRIS
                    else -> IssueSegment.TERMINE
                }
            if (observation?.surUnite(entree, issue) == false) {
                annule = true
            }
            val appelables = lecture.appelables ?: continue
            for (appelable in appelables) {
                parNom.getOrPut(appelable.nom) { mutableListOf() }.add(appelable)
                appelable.recepteur?.let { recepteur ->
                    extensions.getOrPut(recepteur) { mutableListOf() }.add(appelable)
                }
            }
            empreintesChargees.add(entree.empreinte)
        }
        snapshot =
            Snapshot(
                parNom = parNom,
                nomsTries = parNom.keys.sorted(),
                extensions = extensions,
                empreintes = empreintesChargees,
            )
        empreintesSnapshot = empreintesChargees
        return if (annule) ResultatDefin.PARTIEL_ANNULE else ResultatDefin.RECONSTRUIT
    }

    /** Le snapshot courant couvre-t-il EXACTEMENT ces entrées (ordre compris) ? */
    internal fun couvre(entrees: List<EntreeClasspathKotlin>): Boolean =
        entrees.map { it.empreinte } == empreintesSnapshot

    /**
     * Le snapshot porte-t-il AU MOINS UNE entrée de classpath ? ADR 0223 :
     * la preuve d'ABSENCE d'extension exige la connaissance d'au moins un
     * jar d'extensions — une session nue (vue seule, aucune entrée) ne
     * prouve RIEN (toute extension du monde réel est hors modèle).
     */
    internal val connaitDesEntrees: Boolean
        get() = snapshot.empreintes.isNotEmpty()

    /** Appelables de premier niveau portant EXACTEMENT ce nom. */
    internal fun topParNom(nom: String): List<AppelableKotlin> = snapshot.parNom[nom] ?: emptyList()

    /**
     * Noms de premier niveau portant un PRÉFIXE (complétion de noms) —
     * recherche binaire + marche bornée sur la liste triée.
     *
     * @param prefixe préfixe sensible à la casse.
     * @param limite nombre maximum de noms.
     */
    internal fun nomsTopParPrefixe(
        prefixe: String,
        limite: Int,
    ): List<String> {
        val noms = snapshot.nomsTries
        var bas = 0
        var haut = noms.size
        while (bas < haut) {
            val milieu = (bas + haut) / 2
            if (noms[milieu] < prefixe) bas = milieu + 1 else haut = milieu
        }
        val resultat = mutableListOf<String>()
        while (bas < noms.size && resultat.size < limite) {
            if (!noms[bas].startsWith(prefixe)) break
            resultat.add(noms[bas])
            bas++
        }
        return resultat
    }

    /** Extensions dont le récepteur EST ce FQCN. */
    internal fun extensionsSur(recepteur: String): List<AppelableKotlin> =
        snapshot.extensions[recepteur] ?: emptyList()

    /** Snapshot immuable publié par [definir]. */
    private class Snapshot(
        val parNom: Map<String, List<AppelableKotlin>>,
        val nomsTries: List<String>,
        val extensions: Map<String, List<AppelableKotlin>>,
        val empreintes: List<String>,
    )

    // ── Segments (cache mémoire → disque → décodage) ──────────────────

    /** Une lecture d'entrée : appelables + provenance (reprise sans reconstruction ?). */
    private class Lecture(
        val appelables: List<AppelableKotlin>?,
        val reprise: Boolean,
    )

    /**
     * Charge les appelables d'une entrée — CACHE MÉMOIRE d'abord
     * (ADR 0211 : jamais deux fois la même empreinte au disque), sinon
     * segment disque valide, sinon DÉCODAGE à froid + écriture
     * atomique (tmp + rename — motif ADR 0111 §4, jamais de
     * demi-segment visible). Une entrée ABSENTE du disque rend `null`
     * (écart honnête — un jar JAVA PUR sans métadonnées Kotlin rend au
     * contraire une liste VIDE légitime, l'écart et le vide ne sont
     * PAS la même chose, ADR 0214) ; un échec n'est JAMAIS mis en
     * cache (la reprise ciblée le retente). `reprise` = servi SANS
     * reconstruction (cache mémoire ou segment disque).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — les issues
     * sont les PROVENANCES (cache mémoire REPRIS, écart null, servie).
     */
    @Suppress("ReturnCount")
    private fun charger(
        entree: EntreeClasspathKotlin,
        dossierIndex: File?,
    ): Lecture {
        cacheSegments[entree.empreinte]?.let { enCache ->
            return Lecture(enCache, reprise = true)
        }
        val (appelables, repriseDisque) = chargerSegment(entree, dossierIndex)
        if (appelables == null) return Lecture(null, reprise = false)
        retenirEnCache(entree.empreinte, appelables)
        return Lecture(appelables, reprise = repriseDisque)
    }

    /** Retient une lecture en cache mémoire — borné (vidange au plafond). */
    private fun retenirEnCache(
        empreinte: String,
        appelables: List<AppelableKotlin>,
    ) {
        if (cacheSegments.size >= PLAFOND_CACHE_MEMOIRE) cacheSegments.clear()
        cacheSegments[empreinte] = appelables
    }

    /**
     * Charge les appelables d'une entrée : segment valide si présent
     * (REPRISE — jamais redécodé), sinon DÉCODAGE à froid + écriture
     * atomique (tmp + rename — motif ADR 0111 §4, jamais de
     * demi-segment visible). Retour (appelables, reprise).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — segment/absence/décodage = ISSUES.
     */
    @Suppress("ReturnCount")
    private fun chargerSegment(
        entree: EntreeClasspathKotlin,
        dossierIndex: File?,
    ): Pair<List<AppelableKotlin>?, Boolean> {
        if (dossierIndex == null) {
            return decoderSiPresent(entree) to false
        }
        val fichier = File(File(dossierIndex, DOSSIER), nomSegment(entree.empreinte))
        // Le SEGMENT d'abord : il survit au jar DISPARU (l'index est un
        // CACHE, jamais une autorité — ADR 0111 §1, le contrat de
        // persistance est le cœur du test historique).
        if (fichier.isFile) {
            lireSegment(fichier, entree.empreinte)?.let { return it to true }
        }
        // Pas de segment valide : l'entrée doit EXISTER pour être
        // décodée — un jar ABSENT est un ÉCART honnête (ADR 0214), un
        // jar JAVA PUR sans métadonnées Kotlin est au contraire une
        // liste VIDE légitime (l'écart et le vide ne sont pas la même
        // chose).
        val appelables = decoderSiPresent(entree) ?: return null to false
        ecrireSegment(fichier, entree.empreinte, appelables)
        return appelables to false
    }

    /**
     * Décodage à froid — `null` si l'entrée est ABSENTE ou illisible
     * (écart honnête) ; une liste ÉVENTUELLEMENT VIDE sinon (un jar
     * java pur ne porte aucun appelable Kotlin — ce n'est pas un échec).
     *
     * SwallowedException ciblé (règle 16) : `null` EST le contrat pour
     * une entrée illisible — l'index est partiel honnête (D3).
     */
    @Suppress("SwallowedException")
    private fun decoderSiPresent(entree: EntreeClasspathKotlin): List<AppelableKotlin>? {
        if (!File(entree.chemin).isFile) return null
        return try {
            DecodeurMetadonnees.decoder(File(entree.chemin), entree.kind)
        } catch (t: Throwable) {
            // Entrée illisible : absente pour l'index (partiel honnête).
            null
        }
    }

    /** Nom de segment : sha256 hexadécimal de l'empreinte + suffixe. */
    internal fun nomSegment(empreinte: String): String = sha256Hex(PREFIXE_CLE + empreinte) + SUFFIXE_SEGMENT

    /**
     * Lit et VALIDE un segment (magie, version, empreinte recoupée —
     * un fichier renommé/réécrit sous un mauvais nom est détecté, pas
     * cru ; motif ClesSegments d':lsp:index).
     *
     * SwallowedException ciblé (règle 16) : corrompu → null (re-décodage,
     * auto-guérison ADR 0111 §4) — l'exception EST la détection.
     */
    @Suppress("SwallowedException")
    private fun lireSegment(
        fichier: File,
        empreinte: String,
    ): List<AppelableKotlin>? =
        try {
            DataInputStream(fichier.inputStream().buffered()).use { flux ->
                if (flux.readInt() != MAGIE) return null
                if (flux.readInt() != VERSION) return null
                if (flux.readUTF() != empreinte) return null
                val nombre = flux.readInt()
                (0 until nombre).map { lireAppelable(flux) }
            }
        } catch (t: Throwable) {
            // Corrompu : re-décodage (auto-guérison ADR 0111 §4) —
            // l'exception EST la détection, le rejet du segment est
            // le contrat (D3 : partiel honnête, jamais d'exception
            // vers l'appelant).
            null
        }

    /**
     * Écriture atomique : temporaire puis renommage.
     *
     * SwallowedException ciblé (règle 16) : échec d'écriture = dégradation
     * en mémoire seule (le disque est un CACHE, jamais une autorité).
     */
    @Suppress("SwallowedException")
    private fun ecrireSegment(
        fichier: File,
        empreinte: String,
        appelables: List<AppelableKotlin>,
    ) {
        try {
            fichier.parentFile.mkdirs()
            val tampon = ByteArrayOutputStream()
            DataOutputStream(tampon).use { flux ->
                flux.writeInt(MAGIE)
                flux.writeInt(VERSION)
                flux.writeUTF(empreinte)
                flux.writeInt(appelables.size)
                appelables.forEach { ecrireAppelable(flux, it) }
            }
            val temporaire = File(fichier.parentFile, fichier.name + SUFFIXE_TEMPORAIRE)
            java.io.FileOutputStream(temporaire).use { it.write(tampon.toByteArray()) }
            if (!temporaire.renameTo(fichier)) {
                temporaire.delete()
            }
        } catch (t: Throwable) {
            // Index disque = CACHE : un échec d'écriture dégrade en
            // mémoire seule, jamais d'exception vers l'appelant —
            // l'exception est le signal de dégradation, pas une perte
            // (SwallowedException ciblé, règle 16).
        }
    }

    /** Un appelable en binaire compact (booléens = présence). */
    private fun ecrireAppelable(
        flux: DataOutputStream,
        appelable: AppelableKotlin,
    ) {
        flux.writeByte(appelable.genre.ordinal)
        flux.writeUTF(appelable.nom)
        flux.writeUTF(appelable.paquet)
        if (appelable.recepteur != null) {
            flux.writeBoolean(true)
            flux.writeUTF(appelable.recepteur)
        } else {
            flux.writeBoolean(false)
        }
        if (appelable.typeRetour != null) {
            flux.writeBoolean(true)
            flux.writeUTF(appelable.typeRetour)
        } else {
            flux.writeBoolean(false)
        }
        flux.writeInt(appelable.parametres.size)
        appelable.parametres.forEach { parametre ->
            if (parametre.nom != null) {
                flux.writeBoolean(true)
                flux.writeUTF(parametre.nom)
            } else {
                flux.writeBoolean(false)
            }
            if (parametre.type != null) {
                flux.writeBoolean(true)
                flux.writeUTF(parametre.type)
            } else {
                flux.writeBoolean(false)
            }
        }
        flux.writeUTF(appelable.conteneur)
    }

    /** Relecture symétrique (toute incohérence lève → segment rejeté). */
    private fun lireAppelable(flux: DataInputStream): AppelableKotlin {
        val genre = GenreAppelable.entries[flux.readByte().toInt()]
        val nom = flux.readUTF()
        val paquet = flux.readUTF()
        val recepteur = if (flux.readBoolean()) flux.readUTF() else null
        val typeRetour = if (flux.readBoolean()) flux.readUTF() else null
        val parametres =
            (0 until flux.readInt()).map {
                ParametreAppelable(
                    nom = if (flux.readBoolean()) flux.readUTF() else null,
                    type = if (flux.readBoolean()) flux.readUTF() else null,
                )
            }
        val conteneur = flux.readUTF()
        return AppelableKotlin(genre, nom, paquet, recepteur, typeRetour, parametres, conteneur)
    }

    private companion object {
        /** Magie des segments (0x4B434944 = « KCID »). */
        internal const val MAGIE: Int = 0x4B434944

        /** Version du format (incrémenter à tout changement de lecture). */
        internal const val VERSION: Int = 1

        /** Sous-répertoire dédié de l'index partagé. */
        internal const val DOSSIER: String = "kotlin-appelables"

        /**
         * Plafond du cache mémoire par empreinte (ADR 0211) — au-delà,
         * vidange honnête : les segments disque restent, la relecture
         * est le prix d'un classpath massivement churné.
         */
        internal const val PLAFOND_CACHE_MEMOIRE: Int = 256

        private const val SUFFIXE_SEGMENT: String = ".kidx"

        private const val SUFFIXE_TEMPORAIRE: String = ".tmp"

        /** Préfixe de dérivation (espace de clés propre au module). */
        private const val PREFIXE_CLE: String = "lspkotlin-appelables|"

        /** sha256 hexadécimal (miroir local d'Empreintes — :lsp:model est
         * interdit ici, entité indépendante D6). */
        private fun sha256Hex(valeur: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(valeur.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }

    /** Sonne le nourrissage moteur (ADR 0211) — posé par la passe moteur. */
    internal fun sonnerNourrissageMoteur() {
        nourrissageMoteur = true
    }
}
