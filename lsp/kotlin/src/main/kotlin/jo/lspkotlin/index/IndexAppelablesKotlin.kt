package jo.lspkotlin.index

import jo.lspkotlin.api.EntreeClasspathKotlin
import jo.lspkotlin.classpath.DecodeurMetadonnees
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Index des APPELABLES Kotlin du classpath (K3, LSP_KOTLIN.md §4.1
 * « index » — homologue Kotlin du `KotlinCallableIndex` du projet tiers de référence,
 * clés taggées `top:`/`ext:`).
 *
 * PARTAGÉ PAR LE MOTEUR (toutes les sessions d'un projet) : les
 * définitions de classpath arrivent répétées (une par connexion + une
 * par sync) — les SEGMENTS DISQUE par empreinte font qu'un jar
 * inchangé n'est JAMAIS redécodé (motif ADR 0111 §1-§3 : l'identité du
 * contenu EST la clé ; ici les clés viennent de l'hôte W6, le moteur
 * ne re-hache que le NOM de segment).
 *
 * Stockage : `<dossierIndex>/kotlin-appelables/<sha256(empreinte)>.kidx`
 * — sous-répertoire DÉDIÉ de l'index partagé (jamais de collision avec
 * les segments `:lsp:index`) ; `dossierIndex` null = index en mémoire
 * seule (tests, environnements éphémères).
 *
 * Auto-guérison ADR 0111 §4 : un segment corrompu/moisi est
 * re-décodé et réécrit — l'index est un CACHE, jamais une autorité.
 *
 * Concurrence : snapshot IMMUBLE publié volatil — les requêtes lisent
 * l'état cohéré d'une définition, les redéfinitions remplacent TOUT.
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — le catalogue
 * de requêtes (3) + le cache de segments (charge/lecture/écriture/
 * sérialisation) : chaque fonction est UNE section du format KCIDX,
 * même patron que VueIndex/ServiceIndexDisque d':lsp:index.
 */
@Suppress("TooManyFunctions")
internal class IndexAppelablesKotlin {
    /** Snapshot courant (vide avant la première définition). */
    @Volatile
    private var snapshot: Snapshot = Snapshot(emptyMap(), emptyList(), emptyMap())

    /**
     * (Re)définit l'index depuis les entrées de classpath — chaque
     * empreinte est chargée depuis son segment ou décodée à froid puis
     * persistée. Les entrées absentes du disque sont ignorées (jamais
     * d'exception — D3, la vue hôte a déjà filtré l'existence).
     *
     * @param entrees entrées (jars/AAR) dans l'ordre du classpath.
     * @param dossierIndex répertoire de l'index partagé, `null` pour
     *        la mémoire seule.
     */
    internal fun definir(
        entrees: List<EntreeClasspathKotlin>,
        dossierIndex: File?,
    ) {
        val parNom = HashMap<String, MutableList<AppelableKotlin>>()
        val extensions = HashMap<String, MutableList<AppelableKotlin>>()
        for (entree in entrees) {
            val appelables = chargerSegment(entree, dossierIndex) ?: continue
            for (appelable in appelables) {
                parNom.getOrPut(appelable.nom) { mutableListOf() }.add(appelable)
                appelable.recepteur?.let { recepteur ->
                    extensions.getOrPut(recepteur) { mutableListOf() }.add(appelable)
                }
            }
        }
        snapshot =
            Snapshot(
                parNom = parNom,
                nomsTries = parNom.keys.sorted(),
                extensions = extensions,
            )
    }

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
    )

    // ── Segments disque ────────────────────────────────────────────────

    /**
     * Charge les appelables d'une entrée : segment valide si présent,
     * sinon DÉCODAGE à froid + écriture atomique (tmp + rename —
     * motif ADR 0111 §4, jamais de demi-segment visible).
 *
Exemption detekt ciblée (règle 16) : ReturnCount — segment/absence/décodage = ISSUES. */
    @Suppress("ReturnCount")
    private fun chargerSegment(
        entree: EntreeClasspathKotlin,
        dossierIndex: File?,
    ): List<AppelableKotlin>? {
        if (dossierIndex == null) {
            return decoder(entree)
        }
        val fichier = File(File(dossierIndex, DOSSIER), nomSegment(entree.empreinte))
        if (fichier.isFile) {
            lireSegment(fichier, entree.empreinte)?.let { return it }
        }
        val appelables = decoder(entree) ?: return emptyList()
        ecrireSegment(fichier, entree.empreinte, appelables)
        return appelables
    }

    /**
     * Décodage à froid, `null` si l'entrée ne se lit pas (absente).
     *
     * SwallowedException ciblé (règle 16) : `null` EST le contrat pour
     * une entrée illisible — l'index est partiel honnête (D3).
     */
    @Suppress("SwallowedException")
    private fun decoder(entree: EntreeClasspathKotlin): List<AppelableKotlin>? =
        try {
            DecodeurMetadonnees.decoder(File(entree.chemin), entree.kind)
        } catch (t: Throwable) {
            // Entrée illisible : absente pour l'index (partiel honnête).
            null
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
}
