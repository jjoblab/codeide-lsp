package jo.lspjava.lsp;

import org.eclipse.lsp4j.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests du lifecycle LSP.
 *
 * <p>Valide les transitions d'état de {@link JavaLanguageServer.LifecycleState}
 * et le comportement des méthodes initialize/initialized/shutdown/exit.</p>
 *
 * @author jo@Dev
 */
class JavaLanguageServerLifecycleTest {

    @Test
    void initialStateIsUninitialized() {
        JavaLanguageServer server = new JavaLanguageServer();
        assertEquals(JavaLanguageServer.LifecycleState.UNINITIALIZED,
                server.lifecycleState(),
                "Le serveur doit démarrer dans l'état UNINITIALIZED");
    }

    @Test
    void initializeTransitionsToInitializing() {
        JavaLanguageServer server = new JavaLanguageServer();
        InitializeParams params = new InitializeParams();
        params.setRootUri("file:///test");
        params.setProcessId(12345);

        InitializeResult result = server.initialize(params).join();

        assertNotNull(result, "initialize doit retourner un InitializeResult non-null");
        assertNotNull(result.getCapabilities(), "Le résultat doit contenir des ServerCapabilities");
        // serverInfo doit être présent.
        assertNotNull(result.getServerInfo(), "serverInfo doit être présent");
        assertEquals("lspjava", result.getServerInfo().getName(),
                "serverInfo.name doit être 'lspjava'");
        assertNotNull(result.getServerInfo().getVersion(),
                "serverInfo.version doit être non-null");
        // L'état passe à INITIALIZING après initialize (avant initialized).
        assertEquals(JavaLanguageServer.LifecycleState.INITIALIZING,
                server.lifecycleState());
    }

    @Test
    void initializedTransitionsToInitialized() {
        JavaLanguageServer server = new JavaLanguageServer();
        server.initialize(new InitializeParams()).join();
        server.initialized(new InitializedParams());

        assertEquals(JavaLanguageServer.LifecycleState.INITIALIZED,
                server.lifecycleState(),
                "Après initialized, l'état doit être INITIALIZED");
    }

    @Test
    void shutdownTransitionsToShutdown() {
        JavaLanguageServer server = new JavaLanguageServer();
        server.initialize(new InitializeParams()).join();
        server.initialized(new InitializedParams());

        server.shutdown().join();

        assertEquals(JavaLanguageServer.LifecycleState.SHUTDOWN,
                server.lifecycleState(),
                "Après shutdown, l'état doit être SHUTDOWN");
    }

    @Test
    void exitTransitionsToExited() {
        JavaLanguageServer server = new JavaLanguageServer();
        server.initialize(new InitializeParams()).join();
        server.initialized(new InitializedParams());
        server.shutdown().join();

        server.exit();

        assertEquals(JavaLanguageServer.LifecycleState.EXITED,
                server.lifecycleState(),
                "Après exit, l'état doit être EXITED");
    }

    @Test
    void standaloneModeDoesNotExitProcess() {
        // En mode in-process (défaut), exit() ne doit PAS appeler System.exit.
        // On vérifie juste que l'état passe à EXITED sans crasher.
        JavaLanguageServer server = new JavaLanguageServer();
        server.setStandaloneMode(false);
        server.initialize(new InitializeParams()).join();
        server.initialized(new InitializedParams());
        server.shutdown().join();
        server.exit();

        assertEquals(JavaLanguageServer.LifecycleState.EXITED,
                server.lifecycleState());
    }

    @Test
    void capabilitiesIncludeCodeActionKinds() {
        // codeActionKinds doit être annoncé.
        JavaLanguageServer server = new JavaLanguageServer();
        InitializeResult result = server.initialize(new InitializeParams()).join();

        ServerCapabilities caps = result.getCapabilities();
        assertNotNull(caps.getCodeActionProvider(),
                "codeActionProvider doit être annoncé");
        // CodeActionOptions ou Boolean — si c'est CodeActionOptions, vérifier les kinds.
        if (caps.getCodeActionProvider().isRight()) {
            CodeActionOptions opts = caps.getCodeActionProvider().getRight();
            assertNotNull(opts.getCodeActionKinds(),
                    "codeActionKinds doit être non-null");
            assertFalse(opts.getCodeActionKinds().isEmpty(),
                    "codeActionKinds doit être non-vide");
            assertTrue(opts.getCodeActionKinds().contains("quickfix"),
                    "codeActionKinds doit contenir 'quickfix'");
        }
    }

    @Test
    void capabilitiesIncludePositionEncoding() {
        // positionEncoding doit être annoncé.
        JavaLanguageServer server = new JavaLanguageServer();
        InitializeResult result = server.initialize(new InitializeParams()).join();

        ServerCapabilities caps = result.getCapabilities();
        assertEquals(PositionEncodingKind.UTF16, caps.getPositionEncoding(),
                "positionEncoding doit être UTF-16");
    }
}
