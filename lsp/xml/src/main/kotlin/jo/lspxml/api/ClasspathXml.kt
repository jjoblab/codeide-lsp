package jo.lspxml.api

import java.io.File

/**
 * Classpath du moteur XML — ce dont la langue Android a besoin, POUSSÉ
 * par la façade hôte ([SessionLspXml] de `:lsp:host`) après chaque sync
 * utile et depuis le modèle persisté à la réouverture (miroir de
 * `ClasspathKotlin`, X3/X5 du cadrage) :
 *
 * - la PLATEFORME du projet (dossier `platforms/android-*` installé) :
 *   le catalogue X3 vit dans ses données (`data/widgets.txt`,
 *   `data/res/values/attrs.xml`, `data/res/values/attrs_manifest.xml`) —
 *   versionné par plateforme, JAMAIS par constante (risque §7 du
 *   cadrage) ;
 * - les RACINES SOURCES (racines des modules actifs) : `res/` et
 *   `AndroidManifest.xml` y vivent (convention AGP `src/main/…`) ;
 * - la VUE DE NOMS du classpath (couture neutre [VueNomsXml]) : la
 *   garde « balise inconnue » X4 exige que le classpath soit d'accord —
 *   sans vue, le moteur ne signale JAMAIS de classe inconnue.
 *
 * Tolérance (X4) : composants `null`/absents = régime dégradé honnête
 * (syntaxe + règles structurelles seules) ; répéter un appel identique
 * est un NO-OP chaud (l'index ressources ne re-scanne pas les fichiers
 * inchangés, le catalogue se charge UNE fois par empreinte).
 *
 * @property dossierPlateforme dossier de la plateforme du projet
 *           (`platforms/android-37.2` — le PARENT de `android.jar`,
 *           injecté par la façade qui lit le modèle v8) ; `null` ou
 *           absent = sans catalogue (complétion sans le contributeur
 *           plateforme).
 * @property racinesSources racines sources de l'union des modules
 *           actifs (principal + générés) — les racines `res/` y sont
 *           découvertes (une racine SANS `res/` est ignorée).
 * @property vueNoms vue de noms du classpath (FQCN du classpath Java,
 *           adaptée depuis l'index fusion par la façade) ; `null`
 *           avant le premier index prêt = garde X4 active (aucun
 *           diagnostic de balise inconnue).
 */
public data class ClasspathXml(
    public val dossierPlateforme: File?,
    public val racinesSources: List<File>,
    public val vueNoms: VueNomsXml?,
)

/**
 * Couture neutre de noms du classpath (XE4, garde « balise inconnue ») :
 * le moteur interroge les noms de classes disponibles SANS connaître
 * l'index fusion, :lsp:index ou :lsp:java — la façade adapte (miroir
 * minimal de `VueClasspathKotlin` réduit à la question qui fonde la
 * couture X4 : « une classe portant ce nom simple existe-t-elle ? »).
 */
public interface VueNomsXml {
    /**
     * FQCN des classes dont le nom simple commence par [prefixe]
     * (limite bornée, ordre de l'index) ; liste VIDE sans candidat ou
     * index non prêt — JAMAIS d'exception vers le moteur (D3).
     */
    public fun nomsCommencantPar(prefixe: String, limite: Int): List<String>
}
