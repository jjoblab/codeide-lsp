package jo.lspjava.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repro du constat production (v0.107.0, retour propriétaire) : « The
 * import com.example.monip.databinding cannot be resolved » en ERROR
 * sec sur un projet Data Binding sans build préalable.
 *
 * <p><strong>Constat</strong> : les classes {@code XxxBinding} ne sont
 * produites par AGP ({@code dataBindingGenBaseClasses}, puis
 * compilation) qu'AU BUILD — la sync ne les génère PAS. Avant tout
 * build, l'import est STRUCTURELLEMENT non résoluble : l'ERROR sec
 * est exact mais incurable côté utilisateur. Le correctif applique au
 * package Data Binding le MÊME downgrade honnête que la classe R
 * ({@code maybeDowngradeUnresolvedR}, philosophie « zéro devinette ») :
 * ERROR→HINT + remède (« lance un Build »), reconnaissance par les
 * ARGUMENTS STRUCTURÉS du problème ecj (arguments()[0] = nom importé,
 * stables entre locales) et non par le message localisé.</p>
 *
 * <p><strong>Coupure honnête</strong> : dès que la première classe
 * générée existe (sources générées ou binaires du module, sonde du
 * moteur), le downgrade se COUPE — une faute de frappe vers
 * {@code *.databinding.*} redevient une ERROR intégrale.</p>
 *
 * <p>Vrai flux LSP4J : serveur in-process + {@link LanguageClient}
 * espion collectant les {@code publishDiagnostics} (le downgrade vit
 * dans la publication, pas dans le moteur). Skip sans android.jar sur
 * la machine (motif {@code ImportCompletionIntegrationTest}).</p>
 *
 * @author jo@Dev
 */
class DatabindingImportDowngradeTest {

    @TempDir
    Path tmp;

    File androidJar;
    Path srcRoot;
    JavaLanguageServer server;
    EspionClient espion;

    @BeforeEach
    void setUp() throws Exception {
        androidJar = findAndroidJar();
        Assumptions.assumeTrue(androidJar != null, "android.jar absent — skip");
        srcRoot = Files.createDirectories(
                tmp.resolve("app/src/main/java"));
        jo.lspjava.engine.CompilationEnvironment env =
                new jo.lspjava.engine.CompilationEnvironment(
                        List.of(srcRoot.toFile()),
                        List.of(androidJar),
                        "17");
        server = new JavaLanguageServer(env);
        espion = new EspionClient();
        server.connect(espion);
        InitializeParams params = new InitializeParams();
        params.setRootUri(srcRoot.toUri().toString());
        params.setProcessId(0);
        List<WorkspaceFolder> folders = new ArrayList<>();
        folders.add(new WorkspaceFolder(srcRoot.toUri().toString()));
        params.setWorkspaceFolders(folders);
        server.initialize(params).join();
        server.initialized(new InitializedParams());
    }

    @AfterEach
    void tearDown() {
        try {
            server.shutdown().join();
            server.exit();
        } catch (Throwable ignored) {
            // Nettoyage best-effort.
        }
    }

    /**
     * REPO + CORRECTIF : l'import {@code *.databinding.*} non résolu sur
     * un projet SANS classes générées → ERROR déclassée en HINT avec le
     * remède (« généré par AGP lors de la construction »).
     */
    @Test
    void importDatabindingSansBuildEstHintAvecRemede() throws Exception {
        String uri = srcRoot.resolve("com/example/e/MainActivity.java")
                .toUri().toString();
        String source =
                "package com.example.e;\n"
                + "\n"
                + "import com.example.e.databinding.MainActivityBinding;\n"
                + "\n"
                + "public class MainActivity {\n"
                + "}\n";
        ouvrir(uri, source);

        Diagnostic importCasse = attendreDiagnostic(uri, "databinding");
        assertEquals(DiagnosticSeverity.Hint, importCasse.getSeverity(),
                "l'import databinding non générée doit être déclassé en HINT"
                + " : " + formater(importCasse));
        assertTrue(importCasse.getMessage().contains("Data Binding"),
                "le HINT doit expliquer le remède : "
                + formater(importCasse));
        assertTrue(importCasse.getMessage().contains("Build"),
                "le HINT doit nommer l'action : " + formater(importCasse));
    }

    /**
     * COUPURE honnête : le dossier databinding PEUPLÉ (classes générées
     * présentes — situation post-build) → l'import d'une classe
     * databinding INEXISTANTE (faute de frappe) reste une ERROR
     * intégrale : le downgrade ne masque jamais une vraie erreur.
     */
    @Test
    void fauteDeFrappeDatabindingAvecGeneresResteError() throws Exception {
        Path dossier = Files.createDirectories(
                srcRoot.resolve("com/example/e/databinding"));
        Files.writeString(dossier.resolve("MainActivityBinding.java"),
                "package com.example.e.databinding;\n"
                + "public class MainActivityBinding {}\n");

        String uri = srcRoot.resolve("com/example/e/MainActivity.java")
                .toUri().toString();
        String source =
                "package com.example.e;\n"
                + "\n"
                + "import com.example.e.databinding.PasLaBinding;\n"
                + "\n"
                + "public class MainActivity {\n"
                + "}\n";
        ouvrir(uri, source);

        Diagnostic importCasse = attendreDiagnostic(uri, "PasLaBinding");
        assertEquals(DiagnosticSeverity.Error, importCasse.getSeverity(),
                "générées présentes : la faute de frappe reste ERROR : "
                + formater(importCasse));
    }

    /**
     * GARDE du segment : un paquet homonyme (« mydatabinding ») ne
     * déclenche PAS le downgrade — seul un VRAI segment
     * {@code .databinding} (points des deux côtés) compte.
     */
    @Test
    void paquetHomonymeNeDeclenchePasLeDowngrade() throws Exception {
        String uri = srcRoot.resolve("com/example/e/MainActivity.java")
                .toUri().toString();
        String source =
                "package com.example.e;\n"
                + "\n"
                + "import com.example.e.mydatabinding.Machin;\n"
                + "\n"
                + "public class MainActivity {\n"
                + "}\n";
        ouvrir(uri, source);

        Diagnostic importCasse = attendreDiagnostic(uri, "mydatabinding");
        assertEquals(DiagnosticSeverity.Error, importCasse.getSeverity(),
                "« mydatabinding » n'est pas un segment .databinding :"
                + " ERROR intacte : " + formater(importCasse));
    }

    // ─── Fixtures ─────────────────────────────────────────────────────

    private void ouvrir(String uri, String source) {
        DidOpenTextDocumentParams openParams = new DidOpenTextDocumentParams();
        openParams.setTextDocument(new TextDocumentItem(uri, "java", 1, source));
        server.getTextDocumentService().didOpen(openParams);
    }

    /**
     * Attend (polling, 15 s max) la publication d'un diagnostic contenant
     * le fragment pour l'URI — le flux est debounced (50 ms) + asynchrone
     * (scheduler), plusieurs publications peuvent arriver.
     */
    private Diagnostic attendreDiagnostic(String uri, String fragment)
            throws InterruptedException {
        long fin = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < fin) {
            for (int i = espion.recus.size() - 1; i >= 0; i--) {
                PublishDiagnosticsParams params = espion.recus.get(i);
                if (!uri.equals(params.getUri())) continue;
                for (Diagnostic d : params.getDiagnostics()) {
                    if (d.getMessage() != null
                            && d.getMessage().contains(fragment)) {
                        return d;
                    }
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("diagnostic contenant « " + fragment
                + " » jamais publié pour " + uri + " (reçu : "
                + formaterTous() + ")");
    }

    private String formater(Diagnostic d) {
        return d.getSeverity() + " : " + d.getMessage();
    }

    private String formaterTous() {
        StringBuilder sb = new StringBuilder();
        for (PublishDiagnosticsParams p : espion.recus) {
            sb.append("\n  ").append(p.getUri());
            for (Diagnostic d : p.getDiagnostics()) {
                sb.append("\n    ").append(formater(d));
            }
        }
        return sb.toString();
    }

    private static File findAndroidJar() {
        String[] candidats = {
                "/home/z/android-sdk/platforms/android-31-bis/android.jar",
                "/home/z/android-sdk/platforms/android-12/android.jar",
                "/home/z/android-sdk/platforms/android-36/android.jar",
                "/home/z/my-project/android-sdk/platforms/android-31-bis/android.jar",
                "/home/z/my-project/android-sdk/platforms/android-12/android.jar",
                "/home/z/my-project/android-sdk/platforms/android-36/android.jar",
                "/home/z/tools/android-sdk/platforms/android-31/android.jar",
                "/home/z/tools/android-sdk/platforms/android-36/android.jar",
        };
        for (String c : candidats) {
            File f = new File(c);
            if (f.isFile()) return f;
        }
        return null;
    }

    /** Client LSP espion : collecte les publishDiagnostics, ignore le reste. */
    private static final class EspionClient implements LanguageClient {
        final List<PublishDiagnosticsParams> recus =
                new CopyOnWriteArrayList<>();

        @Override
        public void telemetryEvent(Object object) {
        }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
            recus.add(diagnostics);
        }

        @Override
        public void showMessage(MessageParams messageParams) {
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(
                ShowMessageRequestParams requestParams) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams messageParams) {
        }
    }
}
