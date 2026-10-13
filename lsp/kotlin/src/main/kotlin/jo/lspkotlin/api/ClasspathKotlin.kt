package jo.lspkotlin.api

import java.io.File

/**
 * Vue NEUTRE du classpath (K3, LSP_KOTLIN.md §4.1 « classpath », §4.2) —
 * LE contrat par lequel le moteur Kotlin voit les types, paquets et
 * membres binaires du classpath SANS connaître l'index disque de l'hôte
 * (ADR 0111) ni ses types (décision D4/D6 : réutilisation maximale de
 * l'infra Java MAIS traversée par une couture NEUTRE — le module reste
 * découplable en « pack langue » téléchargeable, l'adaptateur de
 * production vit dans `:lsp:host` sur la vue d'index fusion).
 *
 * Philosophie D3 : toute méthode rend son verdict HONNÊTE — liste vide
 * ou `null` quand l'information manque (index en construction, classe
 * introuvable, jar absent) ; JAMAIS d'exception vers l'appelant (la
 * complétion vit sur le chemin des frappes).
 *
 * La vue reflète l'UNION des classpaths des modules du projet (vue
 * fusion ADR 0111 §3) — les requêtes sous-lisent tant que la
 * construction n'est pas PRÊTE (comportement de repli de l'index,
 * contractuel) : une proposition manquante n'est jamais un faux
 * positif, c'est du sous-rapport.
 */
public interface VueClasspathKotlin {
    /**
     * Noms simples de types portant un préfixe (complétion de types).
     *
     * @param prefixe préfixe SENSIBLE à la casse (`TextVi`).
     * @param limite nombre maximum de noms retournés.
     * @return les noms triés, chacun avec ses FQCN dans l'ordre du
     *         classpath (le premier a la préséance) ; liste vide si
     *         l'index n'a rien (encore) sous ce préfixe.
     */
    public fun nomsParPrefixe(prefixe: String, limite: Int): List<NomTypeKotlin>

    /**
     * FQCN des types DIRECTEMENT dans un paquet (« ni plus ni moins » —
     * `java.util` ne rend pas `java.util.function.Function`).
     *
     * @param paquet nom du paquet (`java.util`).
     * @return les FQCN (dédoublonnés, ordre du classpath) ; vide sinon.
     */
    public fun typesDansPaquet(paquet: String): List<String>

    /** Le paquet existe-t-il dans l'index (préfixes intermédiaires compris) ? */
    public fun estPaquet(nom: String): Boolean

    /** Sous-paquets DIRECTS d'un paquet (noms COMPLETS, un seul niveau). */
    public fun sousPaquetsDirects(nom: String): List<String>

    /**
     * Membres DÉCLARÉS d'une classe — vue JVM (champs + méthodes du
     * bytecode, constructeurs inclus au nom `<init>`), SANS les membres
     * hérités (la chaîne de supertypes est résolue par le moteur, cf.
     * [octetsClasse]).
     *
     * @param fqcn nom pleinement qualifié (`android.widget.TextView`,
     *        imbriqués par `$`).
     * @return les membres du premier porteur du classpath, `null` si la
     *         classe est introuvable ou son bloc illisible (partiel
     *         honnête — ADR 0111 §5 `java.members`).
     */
    public fun membres(fqcn: String): MembresTypeKotlin?

    /**
     * Octets BRUTS du fichier de classe (résolution de la chaîne de
     * supertypes et de l'annotation `@Metadata` par le moteur — le
     * parseur class-file est UN dans :lsp:kotlin, les jars sont ouverts
     * côté hôte qui possède la localisation ADR 0111 §6).
     *
     * @param fqcn nom pleinement qualifié.
     * @return les octets du `.class`, `null` si introuvable/illisible.
     */
    public fun octetsClasse(fqcn: String): ByteArray?
}

/**
 * Un nom simple de type et ses FQCN porteurs (miroir neutre de
 * `NomSimpleTypes` de l'index — D4 : mêmes sémantiques, aucun type
 * CodeIDE exposé).
 *
 * @property nomSimple nom simple (`TextView`).
 * @property fqcns FQCN porteurs dans l'ordre du classpath (préséance du
 *           premier — résolution d'import à la volée).
 */
public data class NomTypeKotlin(
    public val nomSimple: String,
    public val fqcns: List<String>,
)

/**
 * Membres déclarés d'un type — vue JVM conservatrice (K3 : les types
 * Kotlin riches — nullabilité, paramètres par défaut, extensions —
 * viennent de l'index des appelables, pas d'ici).
 *
 * @property drapeauxClasse drapeaux d'accès JVM de la classe.
 * @property champs champs déclarés.
 * @property methodes méthodes déclarées (constructeurs inclus, nom
 *           `<init>`).
 */
public data class MembresTypeKotlin(
    public val drapeauxClasse: Int,
    public val champs: List<MembreJvmKotlin>,
    public val methodes: List<MembreJvmKotlin>,
)

/**
 * Un membre (champ ou méthode) lu dans un fichier de classe.
 *
 * @property drapeaux drapeaux d'accès JVM (0x0001 public, 0x0008
 *           static, 0x0010 final…), bruts du bytecode.
 * @property nom nom du membre (`text`, `setText`).
 * @property descripteur descripteur de type JVM (`(Ljava/lang/CharSequence;)V`)
 *           — traduit par le moteur pour l'affichage.
 */
public data class MembreJvmKotlin(
    public val drapeaux: Int,
    public val nom: String,
    public val descripteur: String,
)

/** Drapeau JVM `static` (membres statiques). */
public const val DRAPEAU_STATIQUE_KOTLIN: Int = 0x0008

/** Drapeau JVM `public`. */
public const val DRAPEAU_PUBLIC_KOTLIN: Int = 0x0001

/**
 * Une entrée de classpath indexable par le moteur Kotlin — miroir neutre
 * des entrées W6 de l'hôte (kind JAR/AAR, chemin FUSE, empreinte opaque
 * du contenu : la STABILITÉ fait foi, le moteur n'en dérive RIEN
 * lui-même — c'est la clé de son cache disque).
 *
 * @property kind nature du conteneur.
 * @property chemin chemin absolu du jar ou AAR (FUSE côté production).
 * @property empreinte identité du contenu fournie par l'hôte (W6 —
 *           `maven:…`, `transform:…`, `partiel:…`, `aar:…`).
 */
public data class EntreeClasspathKotlin(
    public val kind: KindEntreeKotlin,
    public val chemin: String,
    public val empreinte: String,
)

/** Nature d'une entrée de classpath côté moteur Kotlin. */
public enum class KindEntreeKotlin {
    /** Jar de bytecode (y compris jar extrait d'un AAR normalisé v8). */
    JAR,

    /** AAR brut (le moteur ouvre classes.jar et les jars internes de libs). */
    AAR,
}

/**
 * Classpath complet d'une session Kotlin (K3) — poussé par la façade
 * hôte ([SessionLspKotlin][jo.codeide.lsp.host.SessionLspKotlin] via
 * [SessionLangageKotlin.definirClasspath]) après chaque sync utile et à
 * l'ouverture depuis le modèle persisté.
 *
 * REMPLACEMENT atomique : un nouvel appel remplace TOUT l'état
 * précédent (l'index des appelables garde ses segments par empreinte —
 * un jar inchangé n'est jamais redécodé, ADR 0111 §3 pour l'union).
 *
 * @property entrees entrées indexables (jars/AAR), ordre du classpath.
 * @property vue vue NEUTRE des types/paquets/membres binaires, `null`
 *           tant qu'aucune sync n'a nourri l'index hôte (régime
 *           dégradé : symboles projet seuls — comme le « mode
 *           instantané » v0.101.0 du LSP Java, jamais de mensonge).
 * @property racinesSources racines de sources du projet (FUSE —
 *           déclarations de fichiers NON ouverts : définition au sein
 *           du projet + complétion des noms du projet).
 * @property dossierIndex répertoire persistant des segments de l'index
 *           d'appelables Kotlin (`null` : index en mémoire seule —
 *           tests et environnements éphémères).
 */
public data class ClasspathKotlin(
    public val entrees: List<EntreeClasspathKotlin>,
    public val vue: VueClasspathKotlin?,
    public val racinesSources: List<File>,
    public val dossierIndex: File?,
)
