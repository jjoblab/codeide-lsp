package jo.codeide.lsp.index

/**
 * Primitives de lecture d'entiers encodés — gros-boutien (format de
 * segment CIDX, `DataOutputStream`) et petit-boutien (format ZIP, APPNOTE
 * §4.1.1) — plus les masques et décalages partagés.
 *
 * Centralisées ici : les constantes nommées évitent les nombres magiques
 * disséminés (detekt) et documentent UNE fois les conventions de boutisme.
 */
internal object OctetsIndex {
    /** Masque d'un octet non signé. */
    internal const val MASQUE_OCTET: Int = 0xFF

    /** Décalage d'un octet vers u16/u32. */
    internal const val DECALAGE_8: Int = 8

    /** Décalage de deux octets vers u32. */
    internal const val DECALAGE_16: Int = 16

    /** Décalage de trois octets vers u32. */
    internal const val DECALAGE_24: Int = 24

    /** Taille d'un u16 encodé. */
    internal const val TAILLE_U16: Int = 2

    /** Taille d'un u32 encodé. */
    internal const val TAILLE_U32: Int = 4

    /** Index du 4e octet (dernier d'un u32). */
    private const val DERNIER_OCTET_U32: Int = 3

    /** u16 GROS-BOUTIEN à [position]. */
    internal fun u16(
        octets: ByteArray,
        position: Int,
    ): Int =
        ((octets[position].toInt() and MASQUE_OCTET) shl DECALAGE_8) or
            (octets[position + 1].toInt() and MASQUE_OCTET)

    /** u32 GROS-BOUTIEN à [position]. */
    internal fun u32(
        octets: ByteArray,
        position: Int,
    ): Int =
        ((octets[position].toInt() and MASQUE_OCTET) shl DECALAGE_24) or
            ((octets[position + 1].toInt() and MASQUE_OCTET) shl DECALAGE_16) or
            ((octets[position + 2].toInt() and MASQUE_OCTET) shl DECALAGE_8) or
            (octets[position + DERNIER_OCTET_U32].toInt() and MASQUE_OCTET)

    /** u16 PETIT-BOUTIEN à [position] (convention ZIP). */
    internal fun u16Petit(
        octets: ByteArray,
        position: Int,
    ): Int =
        (octets[position].toInt() and MASQUE_OCTET) or
            ((octets[position + 1].toInt() and MASQUE_OCTET) shl DECALAGE_8)

    /** u32 PETIT-BOUTIEN à [position] (convention ZIP). */
    internal fun u32Petit(
        octets: ByteArray,
        position: Int,
    ): Int =
        (octets[position].toInt() and MASQUE_OCTET) or
            ((octets[position + 1].toInt() and MASQUE_OCTET) shl DECALAGE_8) or
            ((octets[position + 2].toInt() and MASQUE_OCTET) shl DECALAGE_16) or
            ((octets[position + DERNIER_OCTET_U32].toInt() and MASQUE_OCTET) shl DECALAGE_24)
}
