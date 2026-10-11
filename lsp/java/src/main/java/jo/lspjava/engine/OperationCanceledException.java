package jo.lspjava.engine;

/**
 * Levée aux checkpoints d'annulation du moteur ecj (inspirée du pattern
 * {@code CancelChecker} de java-language-server et du {@code CancelToken}
 * du projet de référence).
 *
 * <p><strong>Motivation :</strong> un diagnose ecj en cours retient le verrou
 * {@code resolveUnit} jusqu'à sa fin — une complétion interactive arrive
 * derrière et gèle la frappe pendant toute la durée du parse restant.
 * La supersession du {@link EcjEngineScheduler} annule les tâches encore
 * en file ; rien ne coupe la tâche déjà démarrée — d'où cette exception.</p>
 *
 * <p><strong>Mécanisme (annulation COOPÉRATIVE, pas de chirurgie ECJ) :</strong>
 * le compilateur interne appelle l'{@code INameEnvironment} des MILLIERS de
 * fois par résolution ({@code findType} pour chaque symbole contre les jars
 * et le jrt). Ces appels sont nos checkpoints gratuits : quand un
 * {@link CancelToken} actif est annulé (supersession par un didChange plus
 * récent ou timeout), chaque checkpoint jette cette exception qui remonte
 * hors de {@code Compiler.resolve} → l'unité est abandonnée SANS passer par
 * le parse complet, le verrou est libéré et la complétion repart.</p>
 *
 * <p><strong>Conséquence volontaire :</strong> aucun arbre n'est mis en cache
 * après annulation ({@code resolveUnit} retourne null sans toucher au
 * {@code EcjParseCache}) — la frappe suivante reparse proprement sur le
 * texte à jour.</p>
 *
 * <p>C'est exactement le pattern {@code CancelChecker} injecté dans
 * l'environnement de nommage par java-language-server, et le pendant Java du
 * {@code CancelToken.checkCancelled()} du projet de référence entre les nœuds AST.
 * Les traversées AST POST-résolution (complétion, hover) disposent déjà de
 * leur try/catch global « traverse partielle » : elles tolèrent naturellement
 * cette exception si elle apparaît là.</p>
 *
 * @author jo@Dev
 */
public final class OperationCanceledException extends RuntimeException {

    static final long serialVersionUID = 1L;

    public OperationCanceledException(String message) {
        super(message);
    }
}
