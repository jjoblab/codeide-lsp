package jo.lspjava.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests du lot S0-a « hygiène protocolaire » (plan de stabilisation §3.1) :
 * <ul>
 *   <li><b>I2</b> — garde lifecycle centralisée : requête avant
 *       {@code initialized} rejetée {@code ServerNotInitialized}, après
 *       {@code shutdown} rejetée {@code InvalidRequest} ; notifications
 *       ignorées hors fenêtre ;</li>
 *   <li><b>I3</b> — {@code initialize} réentrant rejeté
 *       {@code InvalidRequest} ;</li>
 *   <li><b>I5</b> — {@code didSave} SANS {@code includeText} republie les
 *       diagnostics depuis le buffer vivant ;</li>
 *   <li><b>I7</b> — {@code serverInfo.version} = version du dépôt
 *       (ressource {@code jo/lspjava/pack/version.properties}) ;</li>
 *   <li><b>I9</b> — convention null/vide : capacité indisponible →
 *       {@code null} pour hover/foldingRange (le client a un repli),
 *       liste/struct vide pour inlayHint/definition/documentSymbol/
 *       semanticTokens (pas de repli client).</li>
 * </ul>
 *
 * <p>Tous ces tests tournent SANS classpath (moteur en mode « pas prêt ») —
 * ils verrouillent le PROTOCOLE, pas l'analyse.</p>
 *
 * @author jo@Dev
 */
class LifecycleHygieneTest {

    /** Client espion — enregistre les publications de diagnostics. */
    private static final class ClientEspion implements LanguageClient {
        final List<PublishDiagnosticsParams> publications = new CopyOnWriteArrayList<>();

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams params) {
            publications.add(params);
        }

        @Override
        public void telemetryEvent(Object object) {
            // ignoré
        }

        @Override
        public void showMessage(MessageParams messageParams) {
            // ignoré
        }

        @Override
        public java.util.concurrent.CompletableFuture<MessageActionItem> showMessageRequest(
                ShowMessageRequestParams requestParams) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams message) {
            // ignoré
        }
    }

    // ── I3 — initialize réentrant ──────────────────────────────────────

    @Test
    void initializeDeuxFoisRejeteInvalidRequest() throws Exception {
        JavaLanguageServer server = new JavaLanguageServer();
        server.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);

        var refuse = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> server.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS),
                "un second initialize doit être rejeté (spec LSP : erreur, plus un warning)");
        var cause = (org.eclipse.lsp4j.jsonrpc.ResponseErrorException) refuse.getCause();
        assertEquals(org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.InvalidRequest.getValue(),
                cause.getResponseError().getCode(),
                "code attendu InvalidRequest (-32600) : " + cause.getResponseError());
        assertTrue(cause.getResponseError().getMessage().contains("initialize"),
                "message explicite : " + cause.getResponseError().getMessage());
    }

    // ── I2 — garde lifecycle requêtes / notifications ──────────────────

    @Test
    void requeteAvantInitializedRejeteeServerNotInitialized() {
        JavaLanguageServer server = new JavaLanguageServer();
        var service = (JavaTextDocumentService) server.getTextDocumentService();

        HoverParams params = new HoverParams(new TextDocumentIdentifier("file:///x/A.java"),
                new Position(0, 0));
        var refuse = assertThrows(
                org.eclipse.lsp4j.jsonrpc.ResponseErrorException.class,
                () -> service.hover(params),
                "une requête avant initialized doit être rejetée SYNCHRONEMENT");
        assertEquals(org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.ServerNotInitialized.getValue(),
                refuse.getResponseError().getCode(),
                "code attendu ServerNotInitialized (-32002) : " + refuse.getResponseError());
    }

    @Test
    void requeteApresShutdownRejeteeInvalidRequest() throws Exception {
        JavaLanguageServer server = new JavaLanguageServer();
        server.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);
        server.initialized(new InitializedParams());
        server.shutdown().get(5, TimeUnit.SECONDS);

        var service = (JavaTextDocumentService) server.getTextDocumentService();
        CompletionParams params = new CompletionParams(
                new TextDocumentIdentifier("file:///x/A.java"), new Position(0, 0));
        var refuse = assertThrows(
                org.eclipse.lsp4j.jsonrpc.ResponseErrorException.class,
                () -> service.completion(params),
                "une requête après shutdown doit être rejetée — l'ancien code la servait sur des moteurs disposés");
        assertEquals(org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.InvalidRequest.getValue(),
                refuse.getResponseError().getCode(),
                "code attendu InvalidRequest (-32600) : " + refuse.getResponseError());
    }

    @Test
    void notificationAvantInitializedIgnoree() throws Exception {
        JavaLanguageServer server = new JavaLanguageServer();
        ClientEspion espion = new ClientEspion();
        server.connect(espion);
        var service = (JavaTextDocumentService) server.getTextDocumentService();

        // didOpen AVANT initialize — la spec demande d'IGNORER (pas d'erreur :
        // une notification n'a pas de réponse).
        assertDoesNotThrow(() -> service.didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem("file:///x/A.java", "java", 1, "class A {}"))));
        Thread.sleep(200);
        assertTrue(espion.publications.isEmpty(),
                "aucune publication ne doit suivre une notification ignorée");

        // Contre-épreuve : une fois INITIALIZED, le même didOpen publie.
        server.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);
        server.initialized(new InitializedParams());
        service.didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem("file:///x/A.java", "java", 1, "class A {}")));
        assertTrue(attendrePublication(espion, 3000),
                "après initialized, didOpen doit publier des diagnostics");
    }

    @Test
    void notificationApresShutdownIgnoree() throws Exception {
        JavaLanguageServer server = new JavaLanguageServer();
        ClientEspion espion = new ClientEspion();
        server.connect(espion);
        server.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);
        server.initialized(new InitializedParams());
        server.shutdown().get(5, TimeUnit.SECONDS);

        var service = (JavaTextDocumentService) server.getTextDocumentService();
        assertDoesNotThrow(() -> service.didClose(new DidCloseTextDocumentParams(
                new TextDocumentIdentifier("file:///x/A.java"))),
                "une notification après shutdown est ignorée, pas rejetée");
    }

    // ── I5 — didSave sans includeText ──────────────────────────────────

    @Test
    void didSaveSansIncludeTextRepublieDepuisLeBuffer() throws Exception {
        JavaLanguageServer server = new JavaLanguageServer();
        ClientEspion espion = new ClientEspion();
        server.connect(espion);
        server.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);
        server.initialized(new InitializedParams());
        var service = (JavaTextDocumentService) server.getTextDocumentService();

        String uri = "file:///x/Save.java";
        service.didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "java", 0, "class Save {}")));
        assertTrue(attendrePublication(espion, 3000),
                "didOpen publie (pré-condition du test)");

        // didChange FULL (version 1) — publication debouncée 50 ms.
        DidChangeTextDocumentParams change = new DidChangeTextDocumentParams();
        change.setTextDocument(new VersionedTextDocumentIdentifier(uri, 1));
        change.setContentChanges(List.of(new TextDocumentContentChangeEvent(
                "class Save {\n  void maisAlorsIlYAvaitUneErreurDeSyntaxeTresLongue {\n")));
        service.didChange(change);
        Thread.sleep(400); // drain du debounce (50 ms) + passe de diagnostics
        int apresChange = espion.publications.size();
        assertTrue(apresChange >= 2, "didChange doit avoir publié : " + apresChange);

        // didSave SANS includeText — l'ancien code ne publiait RIEN ici.
        int avant = espion.publications.size();
        DidSaveTextDocumentParams save = new DidSaveTextDocumentParams(
                new TextDocumentIdentifier(uri));
        save.setText(null);
        service.didSave(save);
        assertTrue(attendreNouvellesPublications(espion, avant, 3000),
                "didSave sans includeText DOIT republier depuis le buffer (I5 — "
                        + avant + " publications avant, " + espion.publications.size() + " après)");

        // La publication didSave porte la version COURANTE du buffer (1).
        PublishDiagnosticsParams derniere = espion.publications.get(espion.publications.size() - 1);
        assertEquals(uri, derniere.getUri());
        assertNotNull(derniere.getVersion(), "version transportée");
        assertEquals(Integer.valueOf(1), derniere.getVersion());
    }

    @Test
    void didSaveSurDocumentJamaisOuvertNeRepubliePas() throws Exception {
        JavaLanguageServer server = new JavaLanguageServer();
        ClientEspion espion = new ClientEspion();
        server.connect(espion);
        server.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);
        server.initialized(new InitializedParams());
        var service = (JavaTextDocumentService) server.getTextDocumentService();

        // didSave d'un URI jamais ouvert : invalider le cache suffit —
        // republier sur un buffer VIDE serait faux.
        DidSaveTextDocumentParams save = new DidSaveTextDocumentParams(
                new TextDocumentIdentifier("file:///x/JamaisOuvert.java"));
        service.didSave(save);
        Thread.sleep(200);
        assertTrue(espion.publications.isEmpty(),
                "aucune publication pour un document sans buffer : " + espion.publications);
    }

    // ── I7 — serverInfo.version injectée ───────────────────────────────

    @Test
    void serverInfoVersionEstCelleDuDepot() throws Exception {
        JavaLanguageServer server = new JavaLanguageServer();
        InitializeResult result = server.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);

        assertNotNull(result.getServerInfo());
        assertEquals("lspjava", result.getServerInfo().getName());
        String version = result.getServerInfo().getVersion();
        assertNotNull(version);
        assertNotEquals("1.2.0", version,
                "la version fantôme codée en dur (I7) ne doit plus revenir");
        assertEquals(lireVersionRessource(), version,
                "serverInfo.version doit être exactement la version du dépôt");
    }

    private static String lireVersionRessource() throws Exception {
        try (InputStream flux = LifecycleHygieneTest.class.getClassLoader()
                .getResourceAsStream("jo/lspjava/pack/version.properties")) {
            assertNotNull(flux, "la ressource de version doit voyager dans le jar");
            Properties props = new Properties();
            props.load(flux);
            String v = props.getProperty("version");
            assertNotNull(v, "clé version attendue");
            return v.trim();
        }
    }

    // ── I9 — convention null/vide ──────────────────────────────────────

    /**
     * La convention écrite en tête de {@link JavaTextDocumentService} :
     * capacité DISPONIBLE sans données → liste/struct VIDE (le client n'a
     * pas de repli pour ces requêtes) ; pas de données de survol → null.
     * NB : sur un JDK de bureau le moteur est toujours « utilisable » (image
     * jrt = source binaire) — le cas « moteur indisponible → null » n'est
     * pas reproductible en test unitaire ; il est couvert par la branche
     * commentée du handler (moteur absent/occupé/dépassement).
     */
    @Test
    void conventionNullVideSelonRepliClient() throws Exception {
        JavaLanguageServer server = new JavaLanguageServer(); // env vide — moteur jrt de bureau
        ClientEspion espion = new ClientEspion();
        server.connect(espion);
        server.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);
        server.initialized(new InitializedParams());
        JavaTextDocumentService service = (JavaTextDocumentService) server.getTextDocumentService();

        String uri = "file:///x/Conv.java";
        service.didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "java", 1, "class Conv {}\n")));

        // hover sans symbole sous le caret → null (repli client « pas de survol »).
        Hover hover = service.hover(new HoverParams(
                new TextDocumentIdentifier(uri), new Position(0, 12))).get(5, TimeUnit.SECONDS);
        assertNull(hover, "hover sans contenu → null (I9) : " + hover);

        // foldingRange : moteur UTILISABLE + fichier sans construction
        // multi-ligne → liste VIDE (et PAS null : « indisponible » est une
        // information différente d'« aucun pli »).
        List<FoldingRange> plis = service.foldingRange(new FoldingRangeRequestParams(
                new TextDocumentIdentifier(uri))).get(30, TimeUnit.SECONDS);
        assertNotNull(plis, "foldingRange avec moteur utilisable ne rend JAMAIS null");
        assertTrue(plis.isEmpty(), "fichier trivial → aucun pli : " + plis);

        // inlayHint → liste VIDE (le repli textuel des var reste calculé).
        InlayHintParams hintParams = new InlayHintParams();
        hintParams.setTextDocument(new TextDocumentIdentifier(uri));
        hintParams.setRange(new Range(new Position(0, 0), new Position(1, 0)));
        List<InlayHint> hints = service.inlayHint(hintParams).get(30, TimeUnit.SECONDS);
        assertNotNull(hints, "inlayHint ne rend JAMAIS null (I9)");
        assertTrue(hints.isEmpty(), "inlayHint sans hints → vide : " + hints);

        // Document JAMAIS ouvert (buffer vide) : les requêtes « sans repli
        // client » rendent une structure vide, jamais null.
        String jamaisOuvert = "file:///x/JamaisOuvert.java";
        var cibles = service.definition(new DefinitionParams(
                new TextDocumentIdentifier(jamaisOuvert), new Position(0, 0))).get(5, TimeUnit.SECONDS);
        assertNotNull(cibles, "definition ne rend JAMAIS null (I9)");
        assertTrue(cibles.getLeft().isEmpty(), "definition sur buffer vide → vide");

        var symboles = service.documentSymbol(
                new DocumentSymbolParams(new TextDocumentIdentifier(jamaisOuvert))).get(5, TimeUnit.SECONDS);
        assertNotNull(symboles, "documentSymbol ne rend JAMAIS null (I9)");
        assertTrue(symboles.isEmpty(), "documentSymbol sur buffer vide → vide");

        SemanticTokens jetons = service.semanticTokensFull(
                new SemanticTokensParams(new TextDocumentIdentifier(jamaisOuvert))).get(5, TimeUnit.SECONDS);
        assertNotNull(jetons, "semanticTokens ne rend JAMAIS null (I9)");
        assertTrue(jetons.getData() == null || jetons.getData().isEmpty(),
                "semanticTokens sur buffer vide → vide");
    }

    // ── I8 — erreur typée même SANS classpath Android ──────────────────

    @Test
    void renameSansSymboleErreurTypeeSansClasspath() throws Exception {
        // Env vide : sur bureau le moteur reste utilisable via l'image jrt —
        // le caret sur le mot-clé « class » ne porte AUCUN symbole renommable
        // → erreur typée RequestFailed (l'ancien code rendait une édition vide).
        JavaLanguageServer server = new JavaLanguageServer();
        server.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);
        server.initialized(new InitializedParams());
        var service = (JavaTextDocumentService) server.getTextDocumentService();

        String uri = "file:///x/R.java";
        service.didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "java", 1, "class R {}\n")));

        RenameParams params = new RenameParams(
                new TextDocumentIdentifier(uri), new Position(0, 3), "nouveauNom");
        var refuse = assertThrows(
                org.eclipse.lsp4j.jsonrpc.ResponseErrorException.class,
                () -> service.rename(params),
                "rename sans cible doit être une ERREUR TYPOÉE, pas un WorkspaceEdit vide (I8)");
        assertEquals(org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.RequestFailed.getValue(),
                refuse.getResponseError().getCode(),
                "code attendu RequestFailed (-32803) : " + refuse.getResponseError());
        assertTrue(refuse.getResponseError().getMessage().contains("Aucun symbole renommable"),
                "message FR explicite : " + refuse.getResponseError().getMessage());
    }

    // ── helpers d'attente (publications asynchrones) ───────────────────

    private static boolean attendrePublication(ClientEspion espion, long ms) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (espion.publications.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        return !espion.publications.isEmpty();
    }

    private static boolean attendreNouvellesPublications(ClientEspion espion, int avant, long ms)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (espion.publications.size() <= avant && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        return espion.publications.size() > avant;
    }
}
