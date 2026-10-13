package jo.lspkotlin.resolve

import jo.lspkotlin.api.MembreJvmKotlin
import jo.lspkotlin.api.MembresTypeKotlin
import jo.lspkotlin.api.VueClasspathKotlin

/**
 * Résolveur CLASSPATH (K3, LSP_KOTLIN.md §4.1 « resolve ») — le pont
 * conservateur entre les symboles ÉCRITS (textes de types, noms) et
 * les types BINAIRES de la vue de l'hôte :
 *
 *  - FQCN d'un nom de type écrit (imports exacts/jokers, paquet
 *    courant, imports PAR DÉFAUT de Kotlin, alias JVM/Kotlin) ;
 *  - chaîne de SUPERTYPES d'un FQCN (lecture brute des fichiers de
 *    classe via [VueClasspathKotlin.octetsClasse] + LecteurClassBrut —
 *    mémoire bornée : cache plat, profondeur bornée, cycles coupés) ;
 *  - membres déclarés + retour d'un membre par nom (descripteur JVM
 *    traduit en noms Kotlin).
 *
 * Aucune résolution par devinette (D3) : ce qui n'est ni importé, ni
 * du paquet courant, ni un import par défaut de Kotlin, reste SANS
 * FQCN — la complétion membre correspondante est omise, jamais fausse.
 *
 * @param vue la vue de classpath de l'hôte, `null` en régime dégradé
 *        (toutes les méthodes rendent alors leur verdict vide).
 *
 * Exemption detekt ciblée (règle 16) : CyclomaticComplexMethod
 * (traduireDescripteur — chaque lettre de descripteur JVM est une
 * ISSUE du when, V/Z/B/C/S/I/J/F/D/L/[) et TooManyFunctions (le
 * catalogue supertypes/membres/descripteurs/nommage — un service par
 * extension, patron VueIndex).
 */
@Suppress("CyclomaticComplexMethod", "TooManyFunctions", "ReturnCount")
internal class ResolutionKotlin(
    private val vue: VueClasspathKotlin?,
) {
    /** Cache des chaînes de supertypes (plat, FQCN → chaîne complète). */
    private val cacheSupertypes = HashMap<String, List<String>>()

    /**
     * Contexte de nommage d'un document — ce que la session sait des
     * imports et du projet pour transformer un nom écrit en FQCN.
     *
     * @property paquet package du document (null si vide).
     * @property importsParNom import non joker : nom effectif → FQCN
     *           (`a.b.C` ou `a.b.C as D` → D → a.b.C).
     * @property importsJoker paquets des imports joker (`a.b.*`).
     * @property classesProjet classes de PREMIER niveau du projet :
     *           nom simple → FQCN (paquet + nom — mêmes paquets et
     *           autres, le scan projet est complet).
     */
    internal data class ContexteNom(
        val paquet: String?,
        val importsParNom: Map<String, String>,
        val importsJoker: List<String>,
        val classesProjet: Map<String, String>,
    )

    /**
     * FQCN d'un nom de type ÉCRIT — imports exacts d'abord (l'alias
     * gagne), paquet courant (classes du projet), imports par DÉFAUT
     * de Kotlin (kotlin.*, kotlin.collections.*, kotlin.text…), puis
     * les classes du projet (n'importe quel paquet : la résolution en
     * dehors des imports est possible si le nom est UNIQUE au projet).
     * `null` sinon — conservateur.
     */
    internal fun fqcnDuNomEcrit(
        nomEcrit: String,
        contexte: ContexteNom,
    ): String? {
        contexte.importsParNom[nomEcrit]?.let { return it }
        contexte.paquet?.let { paquet ->
            contexte.classesProjet["$paquet.$nomEcrit"]?.let { return it }
        }
        IMPORTS_DEFAUT[nomEcrit]?.let { return it }
        // Import joker : le paquet importé porte-t-il CE nom ? La vue
        // répond (typesDansPaquet — ni plus ni moins).
        for (paquetJoker in contexte.importsJoker) {
            if (vue?.typesDansPaquet(paquetJoker)?.any { it == "$paquetJoker.$nomEcrit" } == true) {
                return "$paquetJoker.$nomEcrit"
            }
            if (contexte.classesProjet["$paquetJoker.$nomEcrit"] != null) {
                return "$paquetJoker.$nomEcrit"
            }
        }
        // Nom unique au projet (tous paquets) : résolu sans import
        // écrit — c'est le cas de figure Kotlin le plus courant hors
        // bibliothèques ; AMBIGU = null (deux classes homonymes).
        val porteurs = contexte.classesProjet.values.filter { it.endsWith(".$nomEcrit") }
        if (porteurs.size == 1) return porteurs.single()
        return null
    }

    /**
     * Chaîne de supertypes de [fqcn] (LE fqcn inclus, superclasse et
     * interfaces récursivement) — profondeur bornée (BORN_PROFONDEUR),
     * cycles coupés, cache plat. Vide (fqcn seul) si la classe ne se
     * lit pas — la complétion membre retombe sur les membres DÉCLARÉS
     * (sous-rapport D3).
     */
    internal fun chaineSupertypes(fqcn: String): List<String> {
        cacheSupertypes[fqcn]?.let { return it }
        val chaine = mutableListOf<String>()
        val vus = HashSet<String>()
        fun visiter(courant: String?, profondeur: Int) {
            if (courant == null || courant in vus || profondeur > BORN_PROFONDEUR) return
            vus.add(courant)
            chaine.add(courant)
            val octets = vue?.octetsClasse(courant) ?: return
            val infos = jo.lspkotlin.classpath.LecteurClassBrut.lire(octets) ?: return
            visiter(infos.superFqcn, profondeur + 1)
            infos.interfaces.forEach { visiter(it, profondeur + 1) }
        }
        visiter(fqcn, 0)
        cacheSupertypes[fqcn] = chaine
        return chaine
    }

    /**
     * Membres DÉCLARÉS d'un FQCN (premier porteur du classpath) —
     * null si introuvable (la vue est la source de vérité, ADR 0111
     * §5 `java.members`).
     */
    internal fun membresDe(fqcn: String): MembresTypeKotlin? = vue?.membres(fqcn)

    /**
     * Membres d'un membre par NOM dans la chaîne de supertypes —
     * surcharges par nom seule (LSP_KOTLIN §4.3 : pas de sélection par
     * types complets en K3) ; le PREMIER porteur de la chaîne gagne.
     *
     * @return champs et méthotes homonymes (le sélecteur arité est à
     *         venir K4), listes vides si introuvable.
     */
    internal fun membresNommes(
        fqcn: String,
        nom: String,
    ): Pair<List<MembreJvmKotlin>, List<MemeJvmNomme>> =
        Pair(champsNommes(fqcn, nom), methodesNommees(fqcn, nom))

    /** Champs homonymes dans la chaîne (premier porteur). */
    internal fun champsNommes(
        fqcn: String,
        nom: String,
    ): List<MembreJvmKotlin> =
        chaineSupertypes(fqcn).asSequence()
            .mapNotNull { membresDe(it) }
            .firstOrNull { membres -> membres.champs.any { it.nom == nom } }
            ?.champs
            ?.filter { it.nom == nom }
            ?: emptyList()

    /**
     * Méthodes homonymes dans la chaîne (TOUTES les surcharges du
     * premier porteur qui en porte — le classement arité arrive K4).
     */
    internal fun methodesNommees(
        fqcn: String,
        nom: String,
    ): List<MemeJvmNomme> {
        for (courant in chaineSupertypes(fqcn)) {
            val membres = membresDe(courant) ?: continue
            val methodes = membres.methodes.filter { it.nom == nom }
            if (methodes.isNotEmpty()) {
                return methodes.map { MemeJvmNomme(it, proprietaire = courant) }
            }
        }
        return emptyList()
    }

    /**
     * Méthode homonyme avec son propriétaire (hover : « —
     * android.widget.TextView »).
     */
    internal data class MemeJvmNomme(
        val methode: MembreJvmKotlin,
        val proprietaire: String,
    )

    /**
     * Variantes de récepteur d'un FQCN pour les EXTENSIONS : les
     * métadonnées Kotlin déclarent `kotlin.String` là où le bytecode
     * dit `java.lang.String` — les deux clés sont sondées.
     */
    internal fun variantesRecepteur(fqcn: String): List<String> {
        val variantes = mutableListOf(fqcn)
        ALIAS_JVM_KOTLIN[fqcn]?.let { variantes.add(it) }
        ALIAS_KOTLIN_JVM[fqcn]?.let { variantes.add(it) }
        return variantes
    }

    // ── Descripteurs JVM ───────────────────────────────────────────────

    /**
     * Descripteur de méthode JVM traduit — paramètres et retour en
     * FQCN/noms JVM (`android.widget.TextView`, `int`, `void`).
     *
     * @property parametres types des paramètres dans l'ordre.
     * @property retour type de retour (`void` → `void`).
     */
    internal data class DescripteurTraduit(
        val parametres: List<String>,
        val retour: String,
    )

    /**
     * Traduit un descripteur de méthode (`(Ljava/lang/CharSequence;)V`).
     * Tolérant : un descripteur incohérent rend (vide, `void`).
     */
    internal fun traduireDescripteur(descripteur: String): DescripteurTraduit {
        val parametres = mutableListOf<String>()
        var index = 0
        fun typeCourant(): String? {
            if (index >= descripteur.length) return null
            return when (val car = descripteur[index]) {
                'L' -> {
                    val fin = descripteur.indexOf(';', index)
                    if (fin < 0) return null
                    val nom = descripteur.substring(index + 1, fin).replace('/', '.')
                    index = fin + 1
                    nom
                }

                '[' -> {
                    index++
                    val compose = typeCourant() ?: return null
                    "$compose[]"
                }

                else -> {
                    index++
                    when (car) {
                        'V' -> "void"
                        'Z' -> "boolean"
                        'B' -> "byte"
                        'C' -> "char"
                        'S' -> "short"
                        'I' -> "int"
                        'J' -> "long"
                        'F' -> "float"
                        'D' -> "double"
                        else -> null
                    }
                }
            }
        }
        if (index < descripteur.length && descripteur[index] == '(') {
            index++
            while (index < descripteur.length && descripteur[index] != ')') {
                val type = typeCourant() ?: return DescripteurTraduit(emptyList(), "void")
                parametres.add(type)
            }
            index++ // ')'
        }
        val retour = typeCourant() ?: "void"
        return DescripteurTraduit(parametres, retour)
    }

    /**
     * Rendu COURT d'un nom JVM/Kotlin (`android.widget.TextView` →
     * `TextView`, `java.lang.String` → `String`, `int` → `Int`,
     * `void` → `Unit`).
     */
    internal fun renduCourt(nomJvm: String): String {
        RENDU_COURT[nomJvm]?.let { return it }
        return nomJvm.substringAfterLast('.')
    }

    private companion object {
        /** Profondeur maximale de la chaîne de supertypes. */
        private const val BORN_PROFONDEUR: Int = 6

        /** Importants par défaut du Kotlin (JVM) — noms usuels. */
        private val IMPORTS_DEFAUT: Map<String, String> =
            mapOf(
                "String" to "kotlin.String",
                "Int" to "kotlin.Int",
                "Long" to "kotlin.Long",
                "Short" to "kotlin.Short",
                "Byte" to "kotlin.Byte",
                "Float" to "kotlin.Float",
                "Double" to "kotlin.Double",
                "Boolean" to "kotlin.Boolean",
                "Char" to "kotlin.Char",
                "Any" to "kotlin.Any",
                "Unit" to "kotlin.Unit",
                "Nothing" to "kotlin.Nothing",
                "List" to "kotlin.collections.List",
                "MutableList" to "kotlin.collections.MutableList",
                "Map" to "kotlin.collections.Map",
                "MutableMap" to "kotlin.collections.MutableMap",
                "Set" to "kotlin.collections.Set",
                "MutableSet" to "kotlin.collections.MutableSet",
                "Collection" to "kotlin.collections.Collection",
                "Iterable" to "kotlin.collections.Iterable",
                "Sequence" to "kotlin.sequences.Sequence",
                "Pair" to "kotlin.Pair",
                "Triple" to "kotlin.Triple",
                "RuntimeException" to "kotlin.RuntimeException",
                "Exception" to "kotlin.Exception",
                "Throwable" to "kotlin.Throwable",
                "IllegalArgumentException" to "kotlin.IllegalArgumentException",
                "IllegalStateException" to "kotlin.IllegalStateException",
            )

        /** Alias JVM ↔ Kotlin (récepteurs d'extensions). */
        private val ALIAS_JVM_KOTLIN: Map<String, String> =
            mapOf(
                "java.lang.String" to "kotlin.String",
                "java.lang.Integer" to "kotlin.Int",
                "java.lang.Long" to "kotlin.Long",
                "java.lang.Boolean" to "kotlin.Boolean",
                "java.lang.Comparable" to "kotlin.Comparable",
            )

        private val ALIAS_KOTLIN_JVM: Map<String, String> =
            mapOf(
                "kotlin.String" to "java.lang.String",
                "kotlin.Int" to "java.lang.Integer",
                "kotlin.Long" to "java.lang.Long",
                "kotlin.Boolean" to "java.lang.Boolean",
            )

        /** Rendus courts fixes (primitives, java.lang usuels). */
        private val RENDU_COURT: Map<String, String> =
            mapOf(
                "void" to "Unit",
                "int" to "Int",
                "long" to "Long",
                "short" to "Short",
                "byte" to "Byte",
                "float" to "Float",
                "double" to "Double",
                "boolean" to "Boolean",
                "char" to "Char",
                "java.lang.String" to "String",
                "java.lang.Object" to "Any",
                "java.lang.Integer" to "Int",
                "java.lang.Long" to "Long",
                "java.lang.Boolean" to "Boolean",
                "java.lang.CharSequence" to "CharSequence",
                "java.lang.Iterable" to "Iterable",
            )
    }
}
