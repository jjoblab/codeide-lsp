package jo.codeide.lsp.host

import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.TimeUnit

/**
 * Sondes d'attente du PACK Kotlin (bascule ADR 0136) — le pack n'expose
 * pas la progression de sa passe d'index par son SPI : l'état public de
 * la façade est une FENÊTRE (CONSTRUCTION → PRET au verdict du guichet,
 * [SessionLspKotlin.etatIndexation]) qui ne dit RIEN de la couverture
 * réelle des entrées. Les scénarios qui exigent des PREUVES
 * (`kt.unresolved` par preuve d'absence — supprimé tant que le guichet
 * est muet —, complétion du classpath, survol de bibliothèque) attendent
 * la couverture par une SONDE LSP honnête : la complétion NOM sur le
 * document de sonde ne propose « println » (une fonction de premier
 * niveau du kotlin-stdlib DU CLASSPATH du test) que lorsque l'index
 * couvre ses entrées — avant, le guichet est muet (sous-lecture honnête).
 */
public object SondesIndexPack {
    /** L'URI du document de sonde (hors projet — fermé après usage). */
    private const val URI_SONDE: String = "file:///sonde-index-pack.kt"

    /**
     * Attend la COUVERTURE d'index du pack (classpath découpé) par le
     * protocole : la complétion du préfixe « printl » propose « println »
     * (kotlin-stdlib) une fois l'entrée couverte.
     *
     * @param serveur le serveur du pack (INITIALIZÉ et INITIALIZED — la
     *        garde I2 du protocole rejette toute requête avant).
     * @param delaiMs la borne d'attente (défaut 240 s — miroir des
     *        attentes E2E historiques).
     * @throws AssertionError si la sonde reste muette au-delà de la borne
     *         (passe du moteur en échec — jamais un test valable).
     */
    @JvmStatic
    public fun attendreClasspath(
        serveur: LanguageServer,
        delaiMs: Long = 240_000,
        texteSonde: String = "fun sonde() { printl }\n",
        colonneSonde: Int = 20,
        libelleAttendu: String = "println",
    ) {
        val documents = serveur.textDocumentService
        documents.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(URI_SONDE, "kotlin", 1, texteSonde)),
        )
        val fin = System.currentTimeMillis() + delaiMs
        try {
            while (System.currentTimeMillis() < fin) {
                val reponse =
                    documents
                        .completion(
                            CompletionParams(
                                TextDocumentIdentifier(URI_SONDE),
                                Position(0, colonneSonde),
                            ),
                        ).get(10, TimeUnit.SECONDS)
                // Le serveur du pack répond en forme CompletionList (bras
                // droit) — le bras gauche (liste nue) reste admis.
                val propositions = reponse?.right?.items ?: reponse?.left ?: emptyList()
                if (propositions.any { it.label.startsWith(libelleAttendu) }) {
                    return
                }
                Thread.sleep(200)
            }
        } finally {
            documents.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(URI_SONDE)))
        }
        throw AssertionError(
            "index du pack Kotlin non couvert après $delaiMs ms (sonde « $libelleAttendu » muette) — " +
                "la passe du moteur n'a pas abouti",
        )
    }
}
