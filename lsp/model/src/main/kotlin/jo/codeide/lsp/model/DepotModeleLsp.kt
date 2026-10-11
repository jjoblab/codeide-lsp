package jo.codeide.lsp.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dépôt du modèle LSP : publication par INSTANTANÉS SUCCESSIFS (§7.1) —
 * chaque synchronisation de tooling publie un NOUVEAU [ProjetLsp]
 * immuable ; les consommateurs observent le flux et gardent l'instantané
 * sous lequel ils ont commencé (isolation par snapshot, jamais de mutation
 * sous les pieds d'un lecteur).
 *
 * Le câblage applicatif (sync → conversion → publication) est livré à
 * l'étape 4 (hôte/cycle de vie) ; l'étape 1 fournit le contrat.
 */
public class DepotModeleLsp {
    private val flux = MutableStateFlow<ProjetLsp?>(null)

    /** Dernier instantané publié, `null` avant la première conversion. */
    public val instantane: StateFlow<ProjetLsp?> = flux.asStateFlow()

    /** Publie un NOUVEL instantané (remplace le précédent). */
    public fun remplacer(projet: ProjetLsp) {
        flux.value = projet
    }

    /** Vide le dépôt (fermeture du projet — la couche hôte décide quand). */
    public fun vider() {
        flux.value = null
    }
}
