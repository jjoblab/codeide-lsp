package jo.lspjava.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * SPI d'index de noms — le point de branchement du moteur vendored
 * {@code :lsp:java} sur l'index disque (mission « LSP Java » étape 3,
 * lot C ; ADR 0111 §8 ; docs/LSP_JAVA.md §15.4).
 *
 * <p><strong>Architecture :</strong> le cœur Java vendored reste une
 * entité indépendante (ADR 0108 — aucune dépendance Gradle vers
 * {@code :lsp:index} ou CodeIDE). Il consomme l'index à travers CETTE
 * interface publique ; l'implémentation de production (adaptateur
 * Kotlin sur la {@code VueIndex} de {@code :lsp:index} + le
 * {@code ClasspathInstantane} de {@code :lsp:model}, les entrées MODULE
 * résolues par les sources du frère) vit côté hôte — l'étape 4
 * ({@code :lsp:host}). Les tests d'intégration du module fournissent
 * leur propre adaptateur.</p>
 *
 * <p><strong>Sémantique PRÊT (ADR 0111 §8) :</strong> une instance est
 * liée à UN classpath ; tant que {@link #estPret()} est faux, les
 * requêtes servent les segments DÉJÀ construits (repli par sondage :
 * résultats partiels honnêtes, jamais de faux pleins) et les négatifs
 * de {@link #jarDe} ne sont PAS autoritatifs. À la transition PRÊT,
 * les observateurs enregistrés par {@link #ajouterObservateurPret} sont
 * notifiés : le consommateur y vide ses caches de localisation en
 * mémoire (P7/P8 — un négatif obtenu pendant la fenêtre de construction
 * doit se guérir). Après PRÊT, {@link #jarDe} est AUTORITATIF : un
 * négatif dispense de sondage.</p>
 *
 * <p><strong>Contrat de thread-safety :</strong> toutes les méthodes
 * sont appelables depuis les fils du moteur (verrou de diagnose,
 * complétion) ; les implémentations doivent être thread-safe.
 * {@link #ajouterObservateurPret} peut être appelé APRÈS la transition
 * (l'observateur est alors notifié immédiatement, une fois).</p>
 *
 * @author jo@Dev
 */
public interface IndexNoms {

    /**
     * @return true si TOUS les segments du classpath lié sont construits
     *         et valides (phase PRÊT — {@code jarDe} devient autoritatif,
     *         les requêtes rendent leur plein).
     */
    boolean estPret();

    /**
     * Enregistre un observateur de la transition PRÊT (P7/P8 : vidage
     * des caches de localisation mémoire du consommateur).
     *
     * <p>Si l'index est DÉJÀ prêt, l'observateur est notifié
     * immédiatement (et une seule fois). Un observateur fautif ne doit
     * jamais casser la transition — l'implémentation l'attrape et le
     * trace.</p>
     */
    void ajouterObservateurPret(Runnable observateur);

    /**
     * Le jar porteur du FQCN — extension {@code java.classLocator}.
     *
     * <p>Le résultat n'est AUTORITATIF (négatif sans sondage) qu'à
     * l'état PRÊT ; en construction, un négatif signifie seulement
     * « pas dans les segments DÉJÀ prêts ».</p>
     *
     * @param fqcn nom pleinement qualifié (points, {@code $} pour les
     *             imbriquées)
     * @return le chemin du JAR porteur (lisible par le lecteur brut du
     *         consommateur), ou {@code null} si absent — y compris quand
     *         la classe vit dans un conteneur que le moteur ne sait pas
     *         lire directement (AAR non normalisé : le consommateur doit
     *         résoudre l'entrée par ailleurs)
     */
    Path jarDe(String fqcn);

    /**
     * Noms simples portant le PRÉFIXE (comparaison lexicographique
     * SENSIBLE à la casse — extension {@code java.classNames}) — la
     * complétion de types par préfixe.
     *
     * <p>Le consommateur post-filtre par son propre matcher gradué
     * (camel-hump, insensible…) : l'index ne garantit que le préfixe
     * exact. Pour une requête insensible à la casse, le consommateur
     * interroge les variantes de casse du premier caractère.</p>
     *
     * @param prefixe préfixe du nom simple (vide = tout, borné par
     *                {@code limite})
     * @param limite  nombre maximum de NOMS rendus (les porteurs suivent)
     * @return nom simple → FQCN porteurs dans l'ordre du classpath
     *         (le premier a la préséance) ; liste jamais null
     */
    Map<String, List<String>> nomsParPrefixe(String prefixe, int limite);

    /**
     * Le paquet existe dans au moins un segment prêt — extension
     * {@code java.packages}. Côté JARS uniquement : les sources du
     * projet et l'image jrt restent au consommateur.
     */
    boolean estPaquet(String nom);

    /**
     * Sous-paquets DIRECTS de {@code nom} (un segment de nom, pas plus
     * profond) — extension {@code java.packages}, non filtrés par
     * préfixe (le matcher gradué du consommateur fait foi).
     */
    List<String> sousPaquetsDirects(String nom);

    /**
     * FQN des types de PREMIER niveau dont le paquet est EXACTEMENT
     * {@code nom} — extension {@code java.packageTypes} (règle « ni
     * plus ni moins » : ni sous-paquet, ni classe imbriquée). Côté
     * jars ; sources et jrt restent au consommateur.
     */
    List<String> typesDansPaquet(String nom);

    /**
     * Noms des membres STATIQUES publics de la classe — extension
     * {@code java.membersByOwner} (imports statiques : champs et
     * méthodes). Réservé à la complétion d'imports statiques.
     *
     * @param fqcn nom pleinement qualifié de la classe propriétaire
     * @return les noms de membres statiques (dédoublonnés), jamais null
     */
    List<String> membresStatiques(String fqcn);
}
