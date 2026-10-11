package jo.lspjava.lsp;

import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.TextDocumentService;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import jo.lspjava.api.DecompiledSourceParams;
import jo.lspjava.api.DecompiledSourceResult;
import jo.lspjava.api.NavigationTarget;

/**
 * Extension LSP PERSONNALISÉE {@code textDocument/superDefinition}
 * (GO TO Super du menu contextuel unifié — il n'existe AUCUNE méthode LSP
 * standard pour la navigation super).
 *
 * <p><strong>Architecture (le piège LSP4J des méthodes custom) :</strong> la
 * carte de DÉSÉRIALISATION des paramètres est construite par
 * {@code ServiceEndpoints.getSupportedMethods(localService.getClass())} qui
 * suit les délégués {@code @JsonDelegate} via le type de retour DÉCLARÉ de
 * {@code LanguageServer.getTextDocumentService()} — c'est-à-dire le
 * {@code TextDocumentService} STANDARD, rien d'autre. Une méthode
 * {@code @JsonRequest} posée sur une interface SUPPLÉMENTAIRE de l'impl
 * serait bien ENREGISTRÉE par le dispatch ({@code GenericEndpoint} invoque
 * le délégué puis traverse TOUTES les interfaces de l'objet retourné), mais
 * ABSENTE de la carte de types → les paramètres arrivent en LinkedTreeMap
 * brut → {@code IllegalArgumentException: argument type mismatch}.</p>
 *
 * <p>La solution : une hiérarchie covariante — {@link NavLanguageServer}
 * redéclare {@code getTextDocumentService()} avec le type
 * {@link NavTextDocumentService} (annoté {@code @JsonDelegate}), si bien que
 * le type de retour DÉCLARÉ porte la méthode custom et les DEUX cartes
 * (dispatch + désérialisation) la connaissent.</p>
 *
 * <p>La capacité est annoncée dans {@code ServerCapabilities.experimental}
 * sous la clé {@code superDefinitionProvider} — les serveurs tiers qui ne
 * la connaissent pas répondent « method not found », que le client avale
 * (l'option Super n'apparaît simplement pas).</p>
 *
 * @author jo@Dev
 */
public interface NavTextDocumentService extends TextDocumentService {

    /**
     * Les cibles Super au caret : pour un membre outrepassant, le
     * même-nommé dans chaque supertype ; sinon les supertypes DIRECTS du
     * type en contexte.
     *
     * @param params le document + la position (forme standard LSP)
     * @return les cibles ({@code NavigationTarget} : chemin absolu, plage
     *         du nom, libellé picker) — vide quand rien ne s'applique
     */
    @JsonRequest("textDocument/superDefinition")
    CompletableFuture<List<NavigationTarget>> superDefinition(
            TextDocumentPositionParams params);

    /**
     * La source (attachée ou stub décompilé) d'un FQN binaire
     * (classpath, JDK). Câble de bout en bout la fonctionnalité « GO TO
     * Definition sur cible binaire » : le serveur renvoie une
     * {@code Location} à URI synthétique
     * {@code jdt://decompiled/<fqcn-path>.java} depuis {@code definition}/
     * {@code typeDefinition} quand la cible est binaire ; le client
     * ({@code :cel-lsp}) appelle alors cette méthode pour récupérer le
     * texte source à afficher dans le buffer read-only.
     *
     * <p>Le {@link DecompiledSourceParams#getFqcn()} porte le FQN (ex.
     * {@code java.util.HashMap}). La réponse {@link DecompiledSourceResult}
     * contient soit la source attachée (src.zip du JDK, -sources.jar, caches
     * Gradle — fidélité maximale), soit le stub décompilé depuis le
     * ClassFileReader, soit {@code null} si indisponible.</p>
     *
     * @param params le FQN cible
     * @return la source (attachée ou stub), ou {@code null} si indisponible
     */
    @JsonRequest("textDocument/decompiledSource")
    CompletableFuture<DecompiledSourceResult> decompiledSource(
            DecompiledSourceParams params);
}
