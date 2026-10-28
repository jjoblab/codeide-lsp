package jo.lspkotlin.classpath

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import kotlin.metadata.jvm.Metadata

/**
 * Lecteur de fichiers de classe BRUT (K3, LSP_KOTLIN.md §4.1
 * « classpath ») — exactement le périmètre dont le moteur Kotlin a
 * besoin, sans ASM (dépendance tenue hors du chemin de l'édition,
 * ADR 0108 côté Java — même discipline) :
 *
 *  - identité de la classe, SUPERCLASSE et INTERFACES (la chaîne de
 *    supertypes pour la complétion membre — les membres DÉCLARÉS
 *    viennent de la vue de l'hôte, l'héritage est résolu ici) ;
 *  - l'annotation `kotlin.Metadata` (RuntimeVisibleAnnotations) —
 *    reconstruite en instance [kotlin.Metadata] via la fabrique
 *    `JvmMetadataUtil.Metadata` pour `KotlinClassMetadata.readLenient`
 *    (le décodage des DÉCLARATIONS vit dans [DecodeurMetadonnees]).
 *
 * Mirole minimal d'`AnalyseurClassFile` de :lsp:index (ADR 0111 —
 * lecteur maison) : même discipline (octet par octet, aucune
 * dépendance), périmètre différent ; ce lecteur est INTERNE au moteur
 * Kotlin, aucun format disque ne dépend de lui.
 *
 * Tolérant (D3) : tout écart de format (classe tronquée, tag de pool
 * inconnu) rend `null` — le consommateur sous-rapporte, jamais
 * d'exception vers l'appelant.
 */
internal object LecteurClassBrut {
    /** Magie d'un fichier de classe JVM. */
    private const val MAGIE_CLASSE: Int = 0xCAFEBABE.toInt()

    /** Tags du pool : Utf8, Integer, Float. */
    private const val TAG_UTF8: Int = 1

    private const val TAG_ENTIER: Int = 3

    private const val TAG_FLOTTEMENT: Int = 4

    /** Tags Long/Double : OCCUPENT DEUX index du pool (JLS §4.4.5). */
    private const val TAG_LONG: Int = 5

    private const val TAG_DOUBLE: Int = 6

    /** Tags à référence u2 : Class, String, MethodType, Module, Package. */
    private const val TAG_CLASSE: Int = 7

    private const val TAG_CHAINE: Int = 8

    private const val TAG_TYPE_METHODE: Int = 16

    private const val TAG_MODULE: Int = 19

    private const val TAG_PAQUET: Int = 20

    /** Tags à double référence u2+u2 : refs, NameAndType, Dynamic, InvokeDynamic. */
    private const val TAG_FIELDREF: Int = 9

    private const val TAG_METHODREF: Int = 10

    private const val TAG_INTERFACE_METHODREF: Int = 11

    private const val TAG_NAME_AND_TYPE: Int = 12

    private const val TAG_DYNAMIC: Int = 17

    private const val TAG_INVOKE_DYNAMIC: Int = 18

    /** MethodHandle : u1 (kind) + u2 (index) = 3 octets. */
    private const val TAG_METHODE_HANDLE: Int = 15

    /** Tailles des entrées du pool (JLS §4.4 : u4 Float/Integer, u8 Long/Double, u3 MethodHandle, u4 double-réf). */
    private const val TAILLE_U32: Int = 4

    private const val TAILLE_U64: Int = 8

    private const val TAILLE_METHODE_HANDLE: Int = 3

    /** En-tête de champ/méthode : access u2 + name u2 + descriptor u2. */
    private const val TAILLE_ENTETE_MEMBRE: Int = 6

    /** Valeur d'énumération d'annotation : type u2 + const u2. */
    private const val TAILLE_VALEUR_ENUM: Int = 4
    /**
     * Informations lues d'un fichier de classe.
     *
     * @property fqcn nom pleinement qualifié de la classe (`a.b.C`).
     * @property superFqcn FQCN de la superclasse, `null` pour
     *           java.lang.Object (super_class = 0).
     * @property interfaces FQCN des interfaces directes.
     * @property metadonnees instance `kotlin.Metadata` reconstruite, ou
     *           `null` (classe Java, ou annotation absente/illisible).
     */
    internal data class InfosClasse(
        val fqcn: String,
        val superFqcn: String?,
        val interfaces: List<String>,
        val metadonnees: kotlin.Metadata?,
    )

    /**
     * Lit un fichier de classe complet.
     *
     * @param octets octets du `.class` (servis par
     *        [jo.lspkotlin.api.VueClasspathKotlin.octetsClasse] ou par
     *        l'ouverture directe d'un jar).
     * @return les informations, `null` si les octets ne sont pas un
     *         fichier de classe lisible (garde silencieuse D3).
 *
     * SwallowedException ciblé (règle 16) : `null` EST le contrat pour
     * une classe illisible — le consommateur sous-rapporte (D3), jamais
     * d'exception vers l'appelant (même patron que
     * LecteurStatistiquesFichierDisque).
     */
    @Suppress("SwallowedException", "ReturnCount", "LoopWithTooManyJumpStatements")
    internal fun lire(octets: ByteArray): InfosClasse? {
        return try {
            val flux = DataInputStream(ByteArrayInputStream(octets))
            if (flux.readInt() != MAGIE_CLASSE) return null
            flux.skipBytes(TAILLE_U32) // versions minor/major
            val pool = Pool(flux)
            if (!pool.initialise) return null
            flux.skipBytes(2) // access_flags
            val thisIdx = flux.readUnsignedShort()
            val superIdx = flux.readUnsignedShort()
            val fqcn = pool.nomDeClasse(thisIdx) ?: return null
            val superFqcn = if (superIdx == 0) null else pool.nomDeClasse(superIdx)
            val interfaces =
                (0 until flux.readUnsignedShort()).mapNotNull { pool.nomDeClasse(flux.readUnsignedShort()) }
            sauterMembres(flux)
            val metadonnees = lireAnnotationMetadata(flux, pool)
            InfosClasse(fqcn, superFqcn, interfaces, metadonnees)
        } catch (t: Throwable) {
            null
        }
    }

    // ── Pool de constantes ─────────────────────────────────────────────

    /**
     * Méthodes GÉNÉRIQUES d'un fichier de classe (chantier P1-C —
     * « implémenter les membres manquants ») : l'ensemble des clés
     * `nom:descripteur` des méthodes portant un attribut `Signature`
     * (JLS §4.7.9 — présent dès que la déclaration touche une variable
     * de type OU un type paramétré). C'est la garde d'honnêteté des
     * stubs rendus depuis la VUE JVM ÉRASÉE : un membre portant
     * `Signature` (paramètre de type, `List<T>`, `Comparable<X>`…) ne
     * peut PAS être rendu en Kotlin valide depuis son descripteur seul —
     * un stub faux est pire que pas de stub (D3), le membre est écarté.
     *
     * @param octets octets du `.class`.
     * @return les clés des méthodes génériques (VOIDE si aucune — un
     *         ensemble vide est un verdict : TOUT est concret) ;
     *         `null` si les octets sont illisibles (le consommateur
     *         sous-rapporte le lien entier).
     * Exemption detekt ciblée (règle 16) : SwallowedException — `null`
     * EST le contrat pour une classe illisible ; ReturnCount — les
     * gardes (magie, pool, noms) sont les issues du contrat ; Nested-
     * BlockDepth — la table des MÉTHODES imbriquée dans le `try` du
     * contrat null est la structure même du format de classe (JLS
     * §4.6), miroir de [lire].
     */
    @Suppress("SwallowedException", "ReturnCount", "NestedBlockDepth")
    internal fun methodesGeneriques(octets: ByteArray): Set<String>? {
        return try {
            val flux = DataInputStream(ByteArrayInputStream(octets))
            if (flux.readInt() != MAGIE_CLASSE) return null
            flux.skipBytes(TAILLE_U32) // versions minor/major
            val pool = Pool(flux)
            if (!pool.initialise) return null
            flux.skipBytes(2) // access_flags
            flux.skipBytes(2 + 2) // this_class + super_class
            repeat(flux.readUnsignedShort()) { flux.skipBytes(2) } // interfaces
            // Champs : sautés (la sonde ne lit que la table des méthodes).
            repeat(flux.readUnsignedShort()) {
                flux.skipBytes(TAILLE_ENTETE_MEMBRE)
                sauterAttributs(flux)
            }
            val generiques = HashSet<String>()
            repeat(flux.readUnsignedShort()) {
                flux.skipBytes(2) // access_flags de la méthode
                val nom = pool.utf8[flux.readUnsignedShort()] ?: return null
                val descripteur = pool.utf8[flux.readUnsignedShort()] ?: return null
                repeat(flux.readUnsignedShort()) {
                    val nomAttribut = pool.utf8[flux.readUnsignedShort()] ?: "?"
                    val longueur = flux.readInt()
                    if (nomAttribut == "Signature") generiques.add("$nom:$descripteur")
                    flux.sauter(longueur)
                }
            }
            generiques
        } catch (t: Throwable) {
            null // classe illisible : verdict impossible (D3)
        }
    }

    /**
     * Pool de constantes — noms (Utf8), entiers (élément_values des
     * annotations), noms de classes (Class→Utf8). Les autres entrées
     * sont sautées octet-exact (JLS §4.4.5 : Long/Double occupent DEUX
     * index).
     */
    private class Pool(flux: DataInputStream) {
        /** Utf8 par index (la plupart des index sont libres). */
        val utf8 = HashMap<Int, String>()

        /** Entiers signés par index (CONSTANT_Integer). */
        val entiers = HashMap<Int, Int>()

        /** Utf8 visé par une entrée Class par index. */
        val classes = HashMap<Int, Int>()

        /** Lecture OK (sinon toute requête rend null). */
        val initialise: Boolean = initialiser(flux)

        /** SwallowedException ciblé (règle 16) : pool invalide = classe invalide = null (D3). */
        @Suppress("SwallowedException")
        private fun initialiser(flux: DataInputStream): Boolean {
            return try {
                val taille = flux.readUnsignedShort()
                var index = 1
                while (index < taille) {
                    when (val tag = flux.readUnsignedByte()) {
                        TAG_UTF8 -> utf8[index] = flux.readUTF()
                        TAG_ENTIER -> entiers[index] = flux.readInt()
                        TAG_FLOTTEMENT -> flux.skipBytes(TAILLE_U32)
                        TAG_LONG, TAG_DOUBLE -> {
                            flux.skipBytes(TAILLE_U64)
                            index++ // DEUX index de pool
                        }
                        TAG_CLASSE, TAG_CHAINE, TAG_TYPE_METHODE, TAG_MODULE, TAG_PAQUET ->
                            classes[index] = flux.readUnsignedShort()
                        TAG_FIELDREF, TAG_METHODREF, TAG_INTERFACE_METHODREF,
                        TAG_NAME_AND_TYPE, TAG_DYNAMIC, TAG_INVOKE_DYNAMIC,
                        -> flux.skipBytes(TAILLE_U32)
                        TAG_METHODE_HANDLE -> flux.skipBytes(TAILLE_METHODE_HANDLE)
                        else -> return false // tag inconnu : classe invalide
                    }
                    index++
                }
                true
            } catch (t: Throwable) {
                false
            }
        }

        /** FQCN d'une entrée Class (`java/lang/String` → `java.lang.String`). */
        fun nomDeClasse(index: Int): String? =
            classes[index]?.let { utf8Ref -> utf8[utf8Ref] }?.replace('/', '.')
    }

    // ── Structures sautées ─────────────────────────────────────────────

    /** Saute champs et méthodes (le moteur ne lit que la structure de CLASSE). */
    private fun sauterMembres(flux: DataInputStream) {
        repeat(flux.readUnsignedShort()) { // champs
            flux.skipBytes(TAILLE_ENTETE_MEMBRE) // access + name + descriptor
            sauterAttributs(flux)
        }
        repeat(flux.readUnsignedShort()) { // méthodes
            flux.skipBytes(TAILLE_ENTETE_MEMBRE)
            sauterAttributs(flux)
        }
    }

    /** Saute une table d'attributs (longueur exacte par attribut). */
    private fun sauterAttributs(flux: DataInputStream) {
        repeat(flux.readUnsignedShort()) {
            flux.skipBytes(2) // attribute_name_index
            flux.sauter(flux.readInt())
        }
    }

    /** Saute [n] octets de façon ROBUSTE (skipBytes peut sous-sauter). */
    private fun DataInputStream.sauter(n: Int) {
        var reste = n
        while (reste > 0) {
            val saute = skipBytes(reste)
            if (saute <= 0) throw EOFException("saut impossible")
            reste -= saute
        }
    }

    // ── Annotation kotlin.Metadata ─────────────────────────────────────

    /**
     * Lit les ATTRIBUTS de classe et reconstruit `kotlin.Metadata`.
     *
     * La table d'attributs (JLS §4.7) : nombre, puis chaque attribut
     * (nom u2 + longueur u4 + contenu) — l'attribut
     * `RuntimeVisibleAnnotations` porte les annotations de CLASSE. Chaque
     * annotation y est PARSE structurellement (une annotation inconnue
     * doit être traversée octet-exact), seules les paires de
     * `Lkotlin/Metadata;` sont EXTRAITES (noms d'éléments JVM : k, mv,
     * bv, d1, d2, xs, pn, xi).
     */
    private fun lireAnnotationMetadata(
        flux: DataInputStream,
        pool: Pool,
    ): kotlin.Metadata? {
        var resultat: kotlin.Metadata? = null
        val nbAttributs = flux.readUnsignedShort()
        repeat(nbAttributs) {
            val nom = pool.utf8[flux.readUnsignedShort()] ?: "?"
            val longueur = flux.readInt()
            if (nom == "RuntimeVisibleAnnotations") {
                // Le contenu suit (exactement longueur octets) : parse
                // structurel inline — les annotations inconnues sont
                // traversées octet-exact pour atteindre la suivante.
                val nbAnnotations = flux.readUnsignedShort()
                repeat(nbAnnotations) {
                    val type = pool.utf8[flux.readUnsignedShort()] ?: return@repeat
                    val paires = HashMap<String, Any?>()
                    repeat(flux.readUnsignedShort()) {
                        val nomElement = pool.utf8[flux.readUnsignedShort()] ?: "?"
                        paires[nomElement] = lireValeurElement(flux, pool)
                    }
                    if (type == "Lkotlin/Metadata;") {
                        resultat = construireMetadata(paires)
                    }
                }
            } else {
                flux.sauter(longueur)
            }
        }
        return resultat
    }

    /**
     * Élément d'annotation (JLS §4.7.16.1) — les constantes pointent le
     * pool par u2, les tableaux portent leur compte, les annotations
     * imbriquées et énumérations sont traversées sans extraction.
     */
    private fun lireValeurElement(
        flux: DataInputStream,
        pool: Pool,
    ): Any? =
        when (val tag = flux.readUnsignedByte().toChar()) {
            'B', 'C', 'I', 'S', 'Z', 'F', 'D', 'J' -> pool.entiers[flux.readUnsignedShort()]
            's' -> pool.utf8[flux.readUnsignedShort()]
            'e' -> {
                flux.skipBytes(TAILLE_VALEUR_ENUM) // type_name + const_name
                null
            }
            'c' -> {
                flux.skipBytes(2) // class_info
                null
            }
            '@' -> {
                // Annotation imbriquée : traversée structurelle.
                flux.skipBytes(2) // type_index
                repeat(flux.readUnsignedShort()) {
                    flux.skipBytes(2) // element_name_index
                    lireValeurElement(flux, pool)
                }
                null
            }
            '[' -> (0 until flux.readUnsignedShort()).map { lireValeurElement(flux, pool) }
            else -> throw EOFException("tag d'élément inconnu : $tag")
        }

    /**
     * Construit l'instance `kotlin.Metadata` depuis les paires lues
     * (k/mv/d1/d2/xs/pn/xi — bv n'est pas requis par la fabrique).
     * Null si `k` (kind) manque : ce n'est pas une Metadata exploitable.
     */
    @Suppress("UNCHECKED_CAST")
    private fun construireMetadata(paires: Map<String, Any?>): kotlin.Metadata? {
        val kind = paires["k"] as? Int ?: return null
        val mv = (paires["mv"] as? List<Int>)?.toIntArray() ?: IntArray(0)
        val d1 = (paires["d1"] as? List<String>)?.toTypedArray() ?: emptyArray()
        val d2 = (paires["d2"] as? List<String>)?.toTypedArray() ?: emptyArray()
        val xs = paires["xs"] as? String
        val pn = paires["pn"] as? String
        val xi = paires["xi"] as? Int
        return Metadata(kind, mv, d1, d2, xs, pn, xi)
    }
}
