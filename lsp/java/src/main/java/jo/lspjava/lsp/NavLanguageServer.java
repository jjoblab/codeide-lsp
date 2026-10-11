package jo.lspjava.lsp;

import org.eclipse.lsp4j.jsonrpc.services.JsonDelegate;
import org.eclipse.lsp4j.services.LanguageServer;

/**
 * {@link LanguageServer} étendu par le serveur Java de CodeIDE :
 * redéclare {@code getTextDocumentService()} avec le type COVARIANT
 * {@link NavTextDocumentService} (annoté {@code @JsonDelegate}).
 *
 * <p><strong>Pourquoi le retour covariant est INDISPENSABLE :</strong> la
 * carte de désérialisation LSP4J suit les délégués via le type de retour
 * DÉCLARÉ de la méthode. Sans cette redéclaration, le type vu serait le
 * {@code TextDocumentService} standard et la méthode personnalisée
 * {@code textDocument/superDefinition} (cf. {@link NavTextDocumentService})
 * recevrait ses paramètres sous forme de {@code LinkedTreeMap} brut —
 * {@code argument type mismatch}. Avec elle, le type déclaré PORTE la
 * méthode et tout fonctionne (voir NavTextDocumentService).</p>
 *
 * <p>Le client ({@code :cel-lsp}) n'a PAS besoin de cette interface : il
 * appelle la méthode PAR SON NOM via
 * {@code Launcher.getRemoteEndpoint().request(...)} et son miroir DTO
 * {@code SuperNavTarget} porte les mêmes noms de champs Gson.</p>
 *
 * @author jo@Dev
 */
public interface NavLanguageServer extends LanguageServer {

    /** Le service de documents étendu (méthode custom superDefinition). */
    @Override
    @JsonDelegate
    NavTextDocumentService getTextDocumentService();
}
