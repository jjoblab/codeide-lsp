package jo.codeide.lsp.index

/**
 * Conteneur d'origine des classes d'un segment — la localisation rendue
 * par les requêtes de l'index (ADR 0111 §6 : lecture sans
 * `java.util.zip.ZipFile`, le consommateur ouvre le conteneur par son
 * propre lecteur brut).
 *
 * Un segment JAR couvre UN jar ; un segment AAR couvre les jars internes
 * normalisés (`classes.jar` + `jars de `libs/``, ADR 0111 §10) — l'entrée
 * qualifie alors le jar interne ; un segment DOSSIER couvre les fichiers
 * `.class` d'une arborescence (sorties de build).
 */
public sealed interface Conteneur {
    /** Chemin du conteneur racine (jar, AAR ou dossier). */
    public val chemin: String

    /** Jar unique du classpath (y compris jar extrait d'un AAR normalisé v8). */
    public data class Jar(
        override val chemin: String,
    ) : Conteneur

    /**
     * AAR NON normalisé par le serveur (périphérique v8) : le segment
     * porte le contenu des jars internes extraits à l'indexation.
     *
     * @property jarsInternes jars internes indexés, dans l'ordre du segment
     *           (`classes.jar` puis `jars de `libs/`` triés).
     */
    public data class Arr(
        override val chemin: String,
        public val jarsInternes: List<String>,
    ) : Conteneur

    /** Dossier de classes (sortie compilée d'un module). */
    public data class Dossier(
        override val chemin: String,
    ) : Conteneur
}

/**
 * Un membre (champ ou méthode) lu dans un fichier de classe — extension
 * `java.members` / `java.membersByOwner` (ADR 0111 §5).
 *
 * @property drapeaux drapeaux d'accès JVM (0x0008 static, 0x0009
 *           public+static…), bruts du fichier de classe.
 * @property nom nom du membre (`size`, `of`).
 * @property descripteur descripteur de type JVM (`(Ljava/lang/Object;)Z`).
 */
public data class Membre(
    public val drapeaux: Int,
    public val nom: String,
    public val descripteur: String,
)

/**
 * Membres d'UNE classe — extension `java.members`.
 *
 * @property drapeauxClasse drapeaux d'accès de la classe.
 * @property champs champs déclarés.
 * @property methodes méthodes déclarées (constructeurs inclus, nom
 *           `<init>`).
 */
public data class MembresClasse(
    public val drapeauxClasse: Int,
    public val champs: List<Membre>,
    public val methodes: List<Membre>,
)

/** Drapeau JVM `static` (membres statiques des imports statiques). */
public const val DRAPEAU_STATIC: Int = 0x0008

/**
 * Localisation d'une classe — extension `java.classLocator`
 * (FQCN → conteneur + entrée).
 *
 * @property conteneur conteneur d'origine (jar, AAR+jar interne, dossier).
 * @property entree nom d'entrée ZIP (`java/util/Map.class`) — dans le jar
 *           interne désigné par [conteneur] pour un AAR.
 * @property portee portée du classpath d'où la classe provient
 *           (`compile` / `test` / `null`), portée par la VUE, pas par le
 *           segment (les segments sont partagés entre modules).
 */
public data class LocalisationClasse(
    public val conteneur: Conteneur,
    public val entree: String,
    public val portee: String?,
)

/**
 * Un nom simple et ses FQCN — extension `java.classNames`
 * (complétion de types par préfixe).
 *
 * @property nomSimple nom simple (`HashMap`, `Entry` pour `Map$Entry`).
 * @property fqcns FQCN porteurs de ce nom simple, dans l'ordre du
 *           classpath (le premier a la préséance).
 */
public data class NomSimpleTypes(
    public val nomSimple: String,
    public val fqcns: List<String>,
)
