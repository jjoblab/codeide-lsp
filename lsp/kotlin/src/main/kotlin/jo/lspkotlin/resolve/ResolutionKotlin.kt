package jo.lspkotlin.resolve

import jo.lspkotlin.api.MembreJvmKotlin
import jo.lspkotlin.api.MembresTypeKotlin
import jo.lspkotlin.api.VueClasspathKotlin
import jo.lspkotlin.index.AppelableKotlin

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
     *
     * ADR 0223 : le nom écrit est NORMALISÉ avant résolution — les
     * conventions nullables et génériques du TYPE (`String?`,
     * `List<Int>`) ne changent PAS l'identité de la classe cible
     * (miroir `TypageExpression.traduireTypeEcrit`, qui normalise
     * déjà les retours écrits des membres du projet) : sans cette
     * normalisation, TOUT récepteur local de type nullable ou
     * générique (`binding?.root`, `items.size`) restait muet — le
     * typage, la complétion membre ET la sélection par types complets.
     */
    internal fun fqcnDuNomEcrit(
        nomEcrit: String,
        contexte: ContexteNom,
    ): String? {
        val nom = nomEcrit.substringBefore('<').trim().trimEnd('?')
        if (nom.isEmpty()) return null
        contexte.importsParNom[nom]?.let { return it }
        contexte.paquet?.let { paquet ->
            contexte.classesProjet["$paquet.$nom"]?.let { return it }
        }
        IMPORTS_DEFAUT[nom]?.let { return it }
        // Import joker : le paquet importé porte-t-il CE nom ? La vue
        // répond (typesDansPaquet — ni plus ni moins).
        for (paquetJoker in contexte.importsJoker) {
            if (vue?.typesDansPaquet(paquetJoker)?.any { it == "$paquetJoker.$nom" } == true) {
                return "$paquetJoker.$nom"
            }
            if (contexte.classesProjet["$paquetJoker.$nom"] != null) {
                return "$paquetJoker.$nom"
            }
        }
        // Nom unique au projet (tous paquets) : résolu sans import
        // écrit — c'est le cas de figure Kotlin le plus courant hors
        // bibliothèques ; AMBIGU = null (deux classes homonymes).
        // Un fqcn SANS PAQUET (« Base ») porte l'égalité stricte ; un
        // fqcn qualifié porte le suffixe « .nom ».
        val porteurs = contexte.classesProjet.values.filter { it == nom || it.endsWith(".$nom") }
        if (porteurs.size == 1) return porteurs.single()
        return null
    }

    /**
     * Chaîne de supertypes de [fqcn] (LE fqcn inclus, superclasse et
     * interfaces récursivement) — profondeur bornée (BORN_PROFONDEUR),
     * budget total de maillons (BORN_MAILLONS), cycles coupés, cache
     * plat. Vide (fqcn seul) si la classe ne se lit pas — la complétion
     * membre retombe sur les membres DÉCLARÉS (sous-rapport D3).
     *
     * ADR 0205 : les octets d'un maillon sont lus via la variante
     * aliasée JVM au premier échec (`kotlin.String` est une FICTION du
     * langage — son bytecode vit sous `java.lang.String`) ; le maillon
     * de chaîne reste le FQCN DEMANDÉ (rendus et clés de cache
     * inchangés), seul l'accès aux octets passe par l'alias. Sans ce
     * sondage, la chaîne s'arrêtait au premier maillon pour String,
     * Int, Long, Boolean, Comparable — aucune méthode DÉCLARÉE de ces
     * types ne résolvait (les extensions, elles, passent par
     * `variantesRecepteur` qui sonde déjà les deux clés).
     *
     * ADR 0219 : la borne de PROFONDEUR 6 coupait la hiérarchie Android
     * RÉELLE avant `android.content.Context` — la chaîne
     * AppCompatActivity → FragmentActivity → activity.ComponentActivity
     * → core.ComponentActivity → Activity → ContextThemeWrapper →
     * ContextWrapper → Context exige PROFONDEUR 7 (et les bibliothèques
     * futures peuvent empiler plus haut) : `getString`/`getColor`/
     * `getDrawable` vivaient au maillon 7 et restaient des faux
     * `kt.unresolved` alors que le classpath portait tout. Miroir
     * Android Studio : la chaîne parcourue est la fermeture COMPLÈTE
     * (aucune borne arbitraire en dessous des hiérarchies réelles).
     */
    internal fun chaineSupertypes(fqcn: String): List<String> {
        cacheSupertypes[fqcn]?.let { return it }
        val chaine = mutableListOf<String>()
        val vus = HashSet<String>()
        fun visiter(courant: String?, profondeur: Int) {
            if (courant == null || courant in vus || profondeur > BORN_PROFONDEUR) return
            if (chaine.size >= BORN_MAILLONS) return
            vus.add(courant)
            chaine.add(courant)
            val octets = octetsClasseAvecAlias(courant) ?: return
            val infos = jo.lspkotlin.classpath.LecteurClassBrut.lire(octets) ?: return
            visiter(infos.superFqcn, profondeur + 1)
            infos.interfaces.forEach { visiter(it, profondeur + 1) }
        }
        visiter(fqcn, 0)
        cacheSupertypes[fqcn] = chaine
        return chaine
    }

    /**
     * Octets de classe via la vue — variante aliasée JVM sondée au
     * premier échec (ADR 0205) : `octetsClasse("kotlin.String")` ne
     * peut JAMAIS répondre (aucun `.class` ne porte ce nom), la
     * variante `java.lang.String` oui.
     */
    private fun octetsClasseAvecAlias(fqcn: String): ByteArray? =
        vue?.octetsClasse(fqcn) ?: vue?.octetsClasse(aliasJvmDe(fqcn))

    /** Variante aliasée JVM d'un FQCN (lui-même sans alias connu). */
    private fun aliasJvmDe(fqcn: String): String =
        ALIAS_KOTLIN_JVM[fqcn] ?: ALIAS_JVM_KOTLIN[fqcn] ?: fqcn

    /**
     * Appelable indexé EN PORTÉE du fichier (ADR 0203) — paquet
     * courant, import EXACT du FQCN (`kotlin.math.PI`), JOKER du
     * paquet, ou paquet PAR DÉFAUT de Kotlin (JVM). Prédicat
     * PARTAGÉ : le survol d'appelable en portée
     * (`InterrogationsSymboles.estEnPortee`) et la voie 6 de
     * `ResolutionReferences.estResolu` (diagnostic `kt.unresolved` +
     * quick-fix d'import) portent la MÊME sémantique de visibilité —
     * sans lui, `PI` sans import échappait au diagnostic alors que
     * la complétion, elle, exigeait l'import.
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque
     * règle de portée est une ISSUE (paquet courant, import exact,
     * joker, défaut Kotlin).
     */
    @Suppress("ReturnCount")
    internal fun appelableEnPortee(
        appelable: AppelableKotlin,
        contexteNom: ContexteNom,
    ): Boolean {
        if (appelable.paquet == contexteNom.paquet) return true
        if (contexteNom.importsParNom.values.any { it == "${appelable.paquet}.${appelable.nom}" }) return true
        if (appelable.paquet in contexteNom.importsJoker) return true
        if (appelable.paquet in PAQUETS_DEFAUT_KOTLIN) return true
        return false
    }

    /**
     * Membres DÉCLARÉS d'un FQCN (premier porteur du classpath) —
     * null si introuvable (la vue est la source de vérité, ADR 0111
     * §5 `java.members`). Variante aliasée JVM sondée au premier
     * échec (ADR 0205) : les membres de `kotlin.String` vivent sous
     * `java.lang.String`.
     */
    internal fun membresDe(fqcn: String): MembresTypeKotlin? =
        vue?.membres(fqcn) ?: vue?.membres(aliasJvmDe(fqcn))

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

    // ── Propriétés synthétiques Java (ADR 0219) ─────────────────────────
    //
    // Interop cœur du langage (miroir Android Studio / kotlinc) : un
    // getter Java `getFoo()` est lisible comme propriété `foo`. Dans le
    // monde non-Compose c'est LA majorité des accès — `layoutInflater`,
    // `packageManager`, `classLoader`, `contentResolver` (Context),
    // `binding.root`-adjacents, `title = …` via setTitle… Sans cette
    // synthèse, TOUT nom nu de propriété-getter dans une Activity était
    // un faux `kt.unresolved`.

    /**
     * GETTER synthétique du nom de propriété [nom] sur la chaîne de
     * [fqcn] — méthode `getFoo()`/`isFoo()` publique ou protégée, NON
     * statique, SANS paramètre et au retour NON void, premier porteur
     * de la chaîne (surcharges par nom seule — K3).
     *
     * @return la méthode portante et son propriétaire, `null` sinon.
     */
    internal fun getterSynthetique(
        fqcn: String,
        nom: String,
    ): MemeJvmNomme? {
        for (candidat in listOf("get${nom.majusculePremier()}", "is${nom.majusculePremier()}")) {
            for (courant in chaineSupertypes(fqcn)) {
                val membres = membresDe(courant) ?: continue
                val methode =
                    membres.methodes.firstOrNull { m ->
                        m.nom == candidat && estAccesseurValide(m, unParametre = false)
                    }
                if (methode != null) return MemeJvmNomme(methode, proprietaire = courant)
            }
        }
        return null
    }

    /**
     * SETTER synthétique du nom de propriété [nom] sur la chaîne de
     * [fqcn] — méthode `setFoo(v)` publique ou protégée, NON statique,
     * à UN paramètre ; une AFFECTATION nu `title = …` prouve sa
     * résolution par son existence (miroir kotlinc).
     */
    internal fun setterSynthetique(
        fqcn: String,
        nom: String,
    ): MemeJvmNomme? {
        val candidat = "set${nom.majusculePremier()}"
        for (courant in chaineSupertypes(fqcn)) {
            val membres = membresDe(courant) ?: continue
            val methode =
                membres.methodes.firstOrNull { m ->
                    m.nom == candidat && estAccesseurValide(m, unParametre = true)
                }
            if (methode != null) return MemeJvmNomme(methode, proprietaire = courant)
        }
        return null
    }

    /**
     * ACCESSEUR (getter OU setter) synthétique — le prédicat du
     * diagnostic `kt.unresolved` (voie 2-ter) : l'un OU l'autre prouve
     * la résolution d'un nom nu de propriété Java.
     */
    internal fun accesseurSynthetique(
        fqcn: String,
        nom: String,
    ): MemeJvmNomme? =
        getterSynthetique(fqcn, nom) ?: setterSynthetique(fqcn, nom)

    /** Première lettre en MAJUSCULE (`layoutInflater` → `LayoutInflater`). */
    private fun String.majusculePremier(): String =
        if (isEmpty() || this[0].isUpperCase()) this else replaceFirstChar { it.uppercaseChar() }

    /**
     * Un membre JVM est-il un ACCESSEUR offrable : public ou protégé
     * (le contexte d'appel est TOUJOURS une sous-classe ou une
     * instance), NON statique (les propriétés synthétiques statiques
     * n'existent pas en Kotlin), et de l'arité demandée — getter :
     * ZÉRO paramètre et retour NON void (`()Ljava/lang/String;`) ;
     * setter : UN paramètre exactement (`(Ljava/lang/CharSequence;)V`).
     */
    private fun estAccesseurValide(
        methode: MembreJvmKotlin,
        unParametre: Boolean,
    ): Boolean {
        if (methode.drapeaux and DRAPEAU_ACCES_PUBLIC == 0 &&
            methode.drapeaux and DRAPEAU_ACCES_PROTEGE == 0
        ) {
            return false
        }
        if (methode.drapeaux and DRAPEAU_ACCES_STATIQUE != 0) return false
        if (methode.nom == "getClass") return false // kotlinc n'en fait JAMAIS une propriété
        if (methode.nom.startsWith("<") || methode.nom.contains('$')) return false
        val traduit = traduireDescripteur(methode.descripteur)
        return if (unParametre) {
            traduit.parametres.size == 1
        } else {
            traduit.parametres.isEmpty() && traduit.retour != "void"
        }
    }

    /**
     * NOM de propriété synthétique d'une méthode accesseur — direction
     * MIROIR (complétion membre) : `getLayoutInflater` →
     * `layoutInflater`, `getURL` → `URL` (deux majuscules de suite
     * restent — patron JavaBeans/kotlinc), `isFocused` → `focused`.
     * `null` si la méthode n'est pas un accesseur.
     */
    internal fun proprieteSynthetiqueDe(methode: MembreJvmKotlin): String? {
        if (methode.drapeaux and DRAPEAU_ACCES_STATIQUE != 0) return null
        if (methode.nom == "getClass") return null
        val nomMethode = methode.nom
        val apresGet = nomMethode.removePrefix("get")
        val apresIs = nomMethode.removePrefix("is")
        val prefixe =
            when {
                apresGet.length < nomMethode.length && apresGet.isNotEmpty() &&
                    apresGet.first().isUpperCase() -> apresGet

                apresIs.length < nomMethode.length && apresIs.isNotEmpty() &&
                    apresIs.first().isUpperCase() -> apresIs

                else -> return null
            }
        if (prefixe.isEmpty() || prefixe.any { it == '$' }) return null
        // Deux majuscules de tête restent (getURL → URL) ; sinon la
        // première passe en minuscule (getLayoutInflater → layoutInflater).
        return if (prefixe.length > 1 &&
            prefixe[0].isUpperCase() && prefixe[1].isUpperCase()
        ) {
            prefixe
        } else {
            prefixe.replaceFirstChar { it.lowercaseChar() }
        }
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
        /** Drapeau d'accès JVM `public` (0x0001). */
        private const val DRAPEAU_ACCES_PUBLIC: Int = 0x0001

        /** Drapeau d'accès JVM `protected` (0x0004). */
        private const val DRAPEAU_ACCES_PROTEGE: Int = 0x0004

        /** Drapeau d'accès JVM `static` (0x0008). */
        private const val DRAPEAU_ACCES_STATIQUE: Int = 0x0008

        /**
         * Profondeur maximale de la chaîne de supertypes — ADR 0219 :
         * 12 couvre les hiérarchies Android réelles (AppCompatActivity
         * → … → Context = profondeur 7) avec marge pour les arbres
         * d'interfaces (l'ordre visite la superclasse AVANT les
         * interfaces : la chaîne de classes reste prioritaire dans la
         * liste aplatie, miroir de la résolution Java).
         */
        private const val BORN_PROFONDEUR: Int = 12

        /**
         * Budget TOTAL de maillons par chaîne (ADR 0219) — défense des
         * hiérarchies pathologiques (interfaces en losange) : la fermeture
         * reste bornée même quand la profondeur seule ne suffit pas.
         */
        private const val BORN_MAILLONS: Int = 256

        /** Paquets importés PAR DÉFAUT par Kotlin sur JVM (ADR 0203 —
         *  https://kotlinlang.org/docs/packages.html#default-imports) :
         *  un appelable top-level de ces paquets résout un nom NU sans
         *  import écrit (println, emptyList, trim…). Partagé par le
         *  survol en portée et la voie 6 de `kt.unresolved` (prédicat
         *  `appelableEnPortee`). */
        private val PAQUETS_DEFAUT_KOTLIN: Set<String> =
            setOf(
                "kotlin",
                "kotlin.annotation",
                "kotlin.collections",
                "kotlin.comparisons",
                "kotlin.io",
                "kotlin.ranges",
                "kotlin.sequences",
                "kotlin.text",
                "kotlin.jvm",
                "java.lang",
            )

        /** Importants par défaut du Kotlin (JVM) — noms usuels.
         *  Source : https://kotlinlang.org/docs/packages.html#default-imports
         *  KP8 : ajout des types manquants (ArrayList, StringBuilder, etc.)
         *  qui causaient de faux `kt.unresolved` sur du code parfaitement valide. */
        private val IMPORTS_DEFAUT: Map<String, String> =
            mapOf(
                // kotlin.*
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
                "Pair" to "kotlin.Pair",
                "Triple" to "kotlin.Triple",
                "Array" to "kotlin.Array",
                "ByteArray" to "kotlin.ByteArray",
                "IntArray" to "kotlin.IntArray",
                "LongArray" to "kotlin.LongArray",
                "Result" to "kotlin.Result",
                "UByte" to "kotlin.UByte",
                "UShort" to "kotlin.UShort",
                "UInt" to "kotlin.UInt",
                "ULong" to "kotlin.ULong",
                // kotlin.collections.*
                "List" to "kotlin.collections.List",
                "MutableList" to "kotlin.collections.MutableList",
                "Map" to "kotlin.collections.Map",
                "MutableMap" to "kotlin.collections.MutableMap",
                "Set" to "kotlin.collections.Set",
                "MutableSet" to "kotlin.collections.MutableSet",
                "Collection" to "kotlin.collections.Collection",
                "MutableCollection" to "kotlin.collections.MutableCollection",
                "Iterable" to "kotlin.collections.Iterable",
                "MutableIterable" to "kotlin.collections.MutableIterable",
                "ArrayList" to "kotlin.collections.ArrayList",
                "HashMap" to "kotlin.collections.HashMap",
                "HashSet" to "kotlin.collections.HashSet",
                "LinkedHashMap" to "kotlin.collections.LinkedHashMap",
                "LinkedHashSet" to "kotlin.collections.LinkedHashSet",
                "Sequence" to "kotlin.sequences.Sequence",
                // kotlin.ranges.*
                "IntRange" to "kotlin.ranges.IntRange",
                "LongRange" to "kotlin.ranges.LongRange",
                "CharRange" to "kotlin.ranges.CharRange",
                // kotlin.text.*
                "StringBuilder" to "kotlin.text.StringBuilder",
                "Regex" to "kotlin.text.Regex",
                "MatchResult" to "kotlin.text.MatchResult",
                // kotlin.comparisons.*
                "Comparator" to "kotlin.comparisons.Comparator",
                // kotlin.exceptions.*
                "RuntimeException" to "kotlin.RuntimeException",
                "Exception" to "kotlin.Exception",
                "Throwable" to "kotlin.Throwable",
                "IllegalArgumentException" to "kotlin.IllegalArgumentException",
                "IllegalStateException" to "kotlin.IllegalStateException",
                "NullPointerException" to "kotlin.NullPointerException",
                "IndexOutOfBoundsException" to "kotlin.IndexOutOfBoundsException",
                "ClassCastException" to "kotlin.ClassCastException",
                "ArithmeticException" to "kotlin.ArithmeticException",
                "NumberFormatException" to "kotlin.NumberFormatException",
                "UnsupportedOperationException" to "kotlin.UnsupportedOperationException",
                "NoSuchElementException" to "kotlin.NoSuchElementException",
                // kotlin.io.*
                "PrintStream" to "kotlin.io.PrintStream",
                // java.lang.* (default import JVM)
                "Object" to "java.lang.Object",
                "Class" to "java.lang.Class",
                "System" to "java.lang.System",
                "Math" to "java.lang.Math",
                "Comparable" to "java.lang.Comparable",
                "Runnable" to "java.lang.Runnable",
                "Thread" to "java.lang.Thread",
                "Override" to "java.lang.Override",
                "Deprecated" to "java.lang.Deprecated",
                "SuppressWarnings" to "java.lang.SuppressWarnings",
                // KP8 (complément 19/10/2026) — types à usage fréquent
                // encore absents : faux `kt.unresolved` résiduels constatés
                // sur CharSequence/Number/Iterator/ArrayDeque/… (code sans
                // aucun import écrit, parfaitement valide).
                // kotlin.* — racine
                "Number" to "kotlin.Number",
                "CharSequence" to "kotlin.CharSequence",
                "Enum" to "kotlin.Enum",
                "Function" to "kotlin.Function",
                "Lazy" to "kotlin.Lazy",
                "AutoCloseable" to "kotlin.AutoCloseable",
                "Cloneable" to "kotlin.Cloneable",
                "Annotation" to "kotlin.annotation.Annotation",
                // tableaux primitifs restants
                "BooleanArray" to "kotlin.BooleanArray",
                "CharArray" to "kotlin.CharArray",
                "ShortArray" to "kotlin.ShortArray",
                "FloatArray" to "kotlin.FloatArray",
                "DoubleArray" to "kotlin.DoubleArray",
                "UByteArray" to "kotlin.UByteArray",
                "UShortArray" to "kotlin.UShortArray",
                "UIntArray" to "kotlin.UIntArray",
                "ULongArray" to "kotlin.ULongArray",
                // kotlin.collections.*
                "Iterator" to "kotlin.collections.Iterator",
                "ListIterator" to "kotlin.collections.ListIterator",
                "MutableListIterator" to "kotlin.collections.MutableListIterator",
                "ArrayDeque" to "kotlin.collections.ArrayDeque",
                // kotlin.text.*
                "StringBuffer" to "kotlin.text.StringBuffer",
                "Appendable" to "kotlin.text.Appendable",
                "MatchGroup" to "kotlin.text.MatchGroup",
                // kotlin.io.*
                "Closeable" to "kotlin.io.Closeable",
                // java.lang.* — compléments
                "Iterable" to "java.lang.Iterable",
                "Void" to "java.lang.Void",
                // hiérarchie d'erreurs/exceptions usuelles (kotlin.* =
                // typealias JVM des java.lang correspondants)
                "Error" to "kotlin.Error",
                "StackOverflowError" to "kotlin.StackOverflowError",
                "OutOfMemoryError" to "kotlin.OutOfMemoryError",
                "AssertionError" to "kotlin.AssertionError",
                "ConcurrentModificationException" to "kotlin.ConcurrentModificationException",
                "InterruptedException" to "kotlin.InterruptedException",
                "CloneNotSupportedException" to "kotlin.CloneNotSupportedException",
                "NegativeArraySizeException" to "kotlin.NegativeArraySizeException",
                "ArrayIndexOutOfBoundsException" to "kotlin.ArrayIndexOutOfBoundsException",
                "StringIndexOutOfBoundsException" to "kotlin.StringIndexOutOfBoundsException",
                // kotlin.jvm.* (default import JVM) — annotations usuelles
                "JvmName" to "kotlin.jvm.JvmName",
                "JvmStatic" to "kotlin.jvm.JvmStatic",
                "JvmField" to "kotlin.jvm.JvmField",
                "JvmOverloads" to "kotlin.jvm.JvmOverloads",
                "JvmMultifileClass" to "kotlin.jvm.JvmMultifileClass",
                "Throws" to "kotlin.jvm.Throws",
                "Transient" to "kotlin.jvm.Transient",
                "Volatile" to "kotlin.jvm.Volatile",
                "Synchronized" to "kotlin.jvm.Synchronized",
                "Strictfp" to "kotlin.jvm.Strictfp",
                "JvmWildcard" to "kotlin.jvm.JvmWildcard",
                "JvmSuppressWildcards" to "kotlin.jvm.JvmSuppressWildcards",
                // kotlin.jvm.functions.* — types de fonctions typés
                "Function0" to "kotlin.jvm.functions.Function0",
                "Function1" to "kotlin.jvm.functions.Function1",
                "Function2" to "kotlin.jvm.functions.Function2",
                "Function3" to "kotlin.jvm.functions.Function3",
                "Function4" to "kotlin.jvm.functions.Function4",
                "Function5" to "kotlin.jvm.functions.Function5",
            )

        /** Alias JVM ↔ Kotlin (récepteurs d'extensions). */
        private val ALIAS_JVM_KOTLIN: Map<String, String> =
            mapOf(
                "java.lang.String" to "kotlin.String",
                "java.lang.Integer" to "kotlin.Int",
                "java.lang.Long" to "kotlin.Long",
                "java.lang.Boolean" to "kotlin.Boolean",
                "java.lang.Comparable" to "kotlin.Comparable",
                // ADR 0218 — hiérarchie des COLLECTIONS : la chaîne des
                // supertypes lue dans les OCTETS JVM produit des noms JAVA
                // (java.util.Collection), mais les clés d'extension de
                // l'index portent les FQCN KOTLIN (kotlin.collections.
                // Iterable — le récepteur ÉCRIT dans les métadonnées).
                // Sans la traduction, `filter`/`map`/`forEach` (extensions
                // sur Iterable) étaient INTROUVABLES depuis List/Set/Map —
                // miroir Android Studio : le survol/complétion de tout
                // appel de collection standard doit répondre.
                "java.lang.Iterable" to "kotlin.collections.Iterable",
                "java.util.Collection" to "kotlin.collections.Collection",
                "java.util.List" to "kotlin.collections.List",
                "java.util.Set" to "kotlin.collections.Set",
                "java.util.Map" to "kotlin.collections.Map",
                "java.util.Iterator" to "kotlin.collections.Iterator",
                "java.util.ListIterator" to "kotlin.collections.ListIterator",
                "java.lang.CharSequence" to "kotlin.CharSequence",
                "java.lang.Number" to "kotlin.Number",
                "java.lang.Enum" to "kotlin.Enum",
                "java.lang.Throwable" to "kotlin.Throwable",
                "java.util.Comparator" to "kotlin.Comparator",
                "java.util.Map.Entry" to "kotlin.collections.Map.Entry",
            )

        private val ALIAS_KOTLIN_JVM: Map<String, String> =
            mapOf(
                "kotlin.String" to "java.lang.String",
                "kotlin.Int" to "java.lang.Integer",
                "kotlin.Long" to "java.lang.Long",
                "kotlin.Boolean" to "java.lang.Boolean",
                // ADR 0218 — direction MIROIR : `chaineSupertypes` lit les
                // octets d'un maillon via CETTE carte (kotlin.collections.
                // List n'a AUCUN .class — son bytecode vit sous java.util.
                // List) ; sans elle, la chaîne s'arrêtait au premier
                // maillon pour TOUTE la hiérarchie des collections.
                "kotlin.collections.Iterable" to "java.lang.Iterable",
                "kotlin.collections.Collection" to "java.util.Collection",
                "kotlin.collections.List" to "java.util.List",
                "kotlin.collections.Set" to "java.util.Set",
                "kotlin.collections.Map" to "java.util.Map",
                "kotlin.collections.Iterator" to "java.util.Iterator",
                "kotlin.collections.ListIterator" to "java.util.ListIterator",
                "kotlin.CharSequence" to "java.lang.CharSequence",
                "kotlin.Number" to "java.lang.Number",
                "kotlin.Enum" to "java.lang.Enum",
                "kotlin.Throwable" to "java.lang.Throwable",
                "kotlin.Comparable" to "java.lang.Comparable",
                "kotlin.Comparator" to "java.util.Comparator",
                "kotlin.collections.Map.Entry" to "java.util.Map.Entry",
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
