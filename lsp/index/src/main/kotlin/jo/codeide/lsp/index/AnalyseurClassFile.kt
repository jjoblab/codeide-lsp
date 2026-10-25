package jo.codeide.lsp.index

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException

/**
 * Analyseur minimal de fichiers de classe JVM — extensions
 * `java.members` / `java.membersByOwner` (ADR 0111 §5) : drapeaux de la
 * classe, champs et méthodes déclarés (nom + descripteur + drapeaux).
 *
 * VOLETAIREMENT minimal : ni génériques (Signature), ni annotations, ni
 * corps — la complétion riche reste au moteur ecj (étape 3) qui relit le
 * bytecode complet ; l'index ne porte que le squelette interrogeable
 * sans ouvrir le jar.
 *
 * Un fichier de classe invalide ou tronqué rend `null` — l'entrée reste
 * localisable (l'index est PARTIEL honnête, ADR 0111 §7 : jar illisible
 * ignoré et SIGNALÉ).
 */
internal object AnalyseurClassFile {
    /** Magie d'un fichier de classe JVM. */
    private const val MAGIE_CLASSE: Int = 0xCAFEBABE.toInt()

    /** Tag de constante Utf8 du pool. */
    private const val TAG_UTF8: Int = 1

    /** Tag de constante Long (occupe DEUX slots du pool). */
    private const val TAG_LONG: Int = 5

    /** Tag de constante Double (occupe DEUX slots du pool). */
    private const val TAG_DOUBLE: Int = 6

    /** Tag Integer (4 octets à sauter). */
    private const val TAG_ENTIER: Int = 3

    /** Tag Float (4 octets à sauter). */
    private const val TAG_FLOTTEMENT: Int = 4

    /** Tag Class. */
    private const val TAG_CLASSE: Int = 7

    /** Tag String. */
    private const val TAG_CHAINE: Int = 8

    /** Tag MethodType. */
    private const val TAG_TYPE_METHODE: Int = 16

    /** Tag Module. */
    private const val TAG_MODULE: Int = 19

    /** Tag Package. */
    private const val TAG_PAQUET: Int = 20

    /** Tag Fieldref. */
    private const val TAG_FIELDREF: Int = 9

    /** Tag Methodref. */
    private const val TAG_METHODREF: Int = 10

    /** Tag InterfaceMethodref. */
    private const val TAG_INTERFACE_METHODREF: Int = 11

    /** Tag NameAndType. */
    private const val TAG_NAME_AND_TYPE: Int = 12

    /** Tag Dynamic. */
    private const val TAG_DYNAMIC: Int = 17

    /** Tag InvokeDynamic. */
    private const val TAG_INVOKE_DYNAMIC: Int = 18

    /** Tags à 2 octets : Class, String, MethodType, Module, Package. */
    private val TAGS_U16: Set<Int> = setOf(TAG_CLASSE, TAG_CHAINE, TAG_TYPE_METHODE, TAG_MODULE, TAG_PAQUET)

    /** Tags à 4 octets : Fieldref…NameAndType, Dynamic, InvokeDynamic. */
    private val TAGS_U32: Set<Int> =
        setOf(TAG_FIELDREF, TAG_METHODREF, TAG_INTERFACE_METHODREF, TAG_NAME_AND_TYPE, TAG_DYNAMIC, TAG_INVOKE_DYNAMIC)

    /** Tag MethodHandle (3 octets). */
    private const val TAG_METHODE_HANDLE: Int = 15

    /** Taille d'un Integer/Float (octets). */
    private const val TAILLE_U32: Int = 4

    /** Taille d'un Long/Double (octets). */
    private const val TAILLE_U64: Int = 8

    /** Taille d'un u16 (octets). */
    private const val TAILLE_U16: Int = 2

    /** Taille d'un MethodHandle (u1 + u2). */
    private const val TAILLE_HANDLE_METHODE: Int = 3

    /** Taille fixe des attributs lus-sautés (nom u2 + longueur u4). */
    private const val TAILLE_EN_TETE_ATTRIBUT: Int = 6

    /**
     * Analyse les octets d'un fichier de classe.
     *
     * @return [MembresClasse] ou `null` si les octets ne sont pas un
     *         fichier de classe valide (jamais d'exception).
     */
    @Suppress("SwallowedException")
    internal fun analyser(octets: ByteArray): MembresClasse? =
        try {
            analyserInterne(octets)
        } catch (e: IOException) {
            // SwallowedException justifié (règle 8) : une classe tronquée
            // ou brouillée n'a simplement pas de membres indexés.
            null
        }

    @Suppress("ReturnCount")
    @Throws(IOException::class)
    private fun analyserInterne(octets: ByteArray): MembresClasse? {
        val entree = DataInputStream(ByteArrayInputStream(octets))
        if (entree.readInt() != MAGIE_CLASSE) {
            return null
        }
        entree.readUnsignedShort() // version mineure
        entree.readUnsignedShort() // version majeure
        val utf8 = lirePoolConstantes(entree) ?: return null
        val drapeauxClasse = entree.readUnsignedShort()
        entree.readUnsignedShort() // this_class
        entree.readUnsignedShort() // super_class
        sauterInterfaces(entree)
        val champs = lireMembres(entree, utf8)
        val methodes = lireMembres(entree, utf8)
        return MembresClasse(drapeauxClasse, champs, methodes)
    }

    /** Lit le pool de constantes — seuls les Utf8 sont conservés. */
    @Throws(IOException::class)
    private fun lirePoolConstantes(entree: DataInputStream): Array<String?>? {
        val nombre = entree.readUnsignedShort()
        val utf8: Array<String?> = arrayOfNulls(nombre)
        var index = 1
        while (index < nombre) {
            when (val tag = entree.readUnsignedByte()) {
                TAG_UTF8 -> {
                    val longueur = entree.readUnsignedShort()
                    val octets = ByteArray(longueur)
                    entree.readFully(octets)
                    utf8[index] = String(octets, Charsets.UTF_8)
                }

                TAG_LONG, TAG_DOUBLE -> {
                    // ADR 0219 : la VALEUR (8 octets) doit être SAUTÉE
                    // AVANT le saut de slot — sans lui, la constante
                    // suivante se lit DANS la valeur du long, le tag est
                    // illisible et TOUTE la classe perd ses membres
                    // (android.content.Context : la seule classe de la
                    // chaîne AppCompat portant un `long` — `getString`
                    // était un faux `kt.unresolved` à cause de ces
                    // 8 octets).
                    entree.skipBytes(TAILLE_U64)
                    index++
                }

                // deux slots — le suivant est fantôme
                else -> {
                    sauterConstante(entree, tag) ?: return null
                }
            }
            index++
        }
        return utf8
    }

    /** Saute une constante de tag inintéressant — `false` si tag inconnu. */
    @Throws(IOException::class)
    private fun sauterConstante(
        entree: DataInputStream,
        tag: Int,
    ): Boolean? =
        when (tag) {
            TAG_ENTIER, TAG_FLOTTEMENT -> {
                entree.skipBytes(TAILLE_U32)
                true
            }

            TAG_LONG, TAG_DOUBLE -> {
                entree.skipBytes(TAILLE_U64)
                true
            }

            in TAGS_U16 -> {
                entree.skipBytes(TAILLE_U16)
                true
            }

            in TAGS_U32 -> {
                entree.skipBytes(TAILLE_U32)
                true
            }

            TAG_METHODE_HANDLE -> {
                entree.skipBytes(TAILLE_HANDLE_METHODE)
                true
            }

            else -> {
                null
            }
        }

    /** Saute la table des interfaces. */
    @Throws(IOException::class)
    private fun sauterInterfaces(entree: DataInputStream) {
        val nombre = entree.readUnsignedShort()
        repeat(nombre) { entree.skipBytes(2) }
    }

    /** Champs ou méthodes (même layout : drapeaux, nom, descripteur, attributs). */
    @Throws(IOException::class)
    private fun lireMembres(
        entree: DataInputStream,
        utf8: Array<String?>,
    ): List<Membre> {
        val nombre = entree.readUnsignedShort()
        val membres = ArrayList<Membre>(nombre)
        repeat(nombre) {
            val drapeaux = entree.readUnsignedShort()
            val nom = utf8(entree.readUnsignedShort(), utf8)
            val descripteur = utf8(entree.readUnsignedShort(), utf8)
            sauterAttributs(entree)
            membres.add(Membre(drapeaux, nom, descripteur))
        }
        return membres
    }

    /** Saute les attributs d'un membre. */
    @Throws(IOException::class)
    private fun sauterAttributs(entree: DataInputStream) {
        val nombre = entree.readUnsignedShort()
        repeat(nombre) {
            entree.skipBytes(2) // nom de l'attribut
            val longueur = entree.readInt()
            entree.skipBytes(longueur)
        }
    }

    /** Utf8 du pool — chaîne vide si l'index est hors bornes (défensif). */
    private fun utf8(
        index: Int,
        utf8: Array<String?>,
    ): String = utf8.getOrNull(index) ?: ""

    /** Taille de l'en-tête d'attribut (pré-allocation, lisibilité). */
    @Suppress("unused")
    private const val TAILLE_EN_TETE = TAILLE_EN_TETE_ATTRIBUT
}
