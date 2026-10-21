package jo.lspjava;

import jo.lspjava.engine.CompilationEnvironment;
import jo.lspjava.lsp.JavaLanguageServer;
import jo.lspjava.lsp.JavaTextDocumentService;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintKind;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verrouille le portage des deux familles de hints manquantes du
 * {@code JdtInlayHintService} du projet tiers de référence :
 *
 * <ul>
 *   <li><b>Chaining hints</b> — sur un chaînage fluent MULTI-LIGNE, le type
 *       du récepteur (l'appel de la ligne précédente) est affiché à la fin de
 *       SA ligne, libellé = nom court SANS deux-points, padding gauche. Même
 *       ligne ⇒ rien. Le premier maillon (récepteur = constructeur) ne
 *       reçoit PAS de hint (parité : le récepteur doit être un MethodInvocation).</li>
 *   <li><b>Hints de type des paramètres lambda implicites</b> — « : Type »
 *       à la fin du nom du paramètre, collé (pas de padding). Un paramètre
 *       typé explicitement ne reçoit rien.</li>
 * </ul>
 *
 * @author jo@Dev
 */
class JdtChainedLambdaHintsTest {

    private JavaTextDocumentService service;

    @BeforeEach
    void setUp() throws Exception {
        var env = new CompilationEnvironment();
        JavaLanguageServer server = new JavaLanguageServer(env);
        // Garde lifecycle (I2, v0.134.11) : les requêtes sont rejetées hors
        // fenêtre INITIALIZED — le serveur passe par initialize/initialized.
        server.initialize(new InitializeParams()).get(10, TimeUnit.SECONDS);
        server.initialized(new InitializedParams());
        service = new JavaTextDocumentService(server.environment(), server);
    }

    /**
     * Chaînage multi-ligne : « new StringBuilder("a") » en ligne 2, puis
     * .append("b") / .append("c") / .toString() chacun sur sa ligne.
     *
     * <p>Attendu (parité chainingHint du projet tiers de référence) :</p>
     * <ul>
     *   <li>« .append("b") » → récepteur = constructeur (ClassInstanceCreation)
     *       ⇒ PAS de hint ;</li>
     *   <li>« .append("c") » → récepteur = MessageSend .append("b") finissant
     *       ligne 3 ⇒ hint « StringBuilder » en (3, fin de ligne) padLeft ;</li>
     *   <li>« .toString() » → récepteur = MessageSend .append("c") finissant
     *       ligne 4 ⇒ hint « StringBuilder » en (4, fin de ligne) padLeft.</li>
     * </ul>
     */
    @Test
    void multiLineChain_emitsReceiverTypeAtEachBoundary() throws Exception {
        String source = """
            public class Demo {
                public void m() {
                    String r = new StringBuilder("a")
                        .append("b")
                        .append("c")
                        .toString();
                }
            }
            """;
        openDocument("file:///Demo.java", source);

        List<InlayHint> hints = requestHints();
        List<InlayHint> chaining = hints.stream()
                .filter(h -> "StringBuilder".equals(h.getLabel().getLeft()))
                .toList();
        assertEquals(2, chaining.size(),
                "expected 2 chaining hints (append(c) and toString boundaries), got: " + labels(hints));

        // Les deux hints sont de kind Type, avec padding à GAUCHE et sans padding à droite.
        for (InlayHint h : chaining) {
            assertEquals(InlayHintKind.Type, h.getKind());
            assertEquals(Boolean.TRUE, h.getPaddingLeft(), "chaining hint needs left padding");
            assertNotEquals(Boolean.TRUE, h.getPaddingRight());
        }

        // Ancres : en fin de la ligne du récepteur — après `.append("b")`
        // (ligne 3) et après `.append("c")` (ligne 4). Les deux lignes
        // finissent à la colonne 24 (20 d'indentation + `.append("b")`
        // = 12 caractères).
        // NOTE : le parcours ecj est PRÉ-ORDRE (appel le plus externe en
        // premier), donc le hint de la frontière `.toString()` arrive AVANT
        // celui de la frontière `.append("c")` — on asserte l'ENSEMBLE,
        // pas l'ordre.
        List<Position> anchors = chaining.stream()
                .map(InlayHint::getPosition)
                .toList();
        assertTrue(anchors.contains(new Position(3, 24)),
                "expected an anchor at end of the .append(\"b\") line (3,24), got: " + anchors);
        assertTrue(anchors.contains(new Position(4, 24)),
                "expected an anchor at end of the .append(\"c\") line (4,24), got: " + anchors);
    }

    /** Tout sur UNE ligne ⇒ aucun chaining hint (sinon ce n'est que du bruit visuel). */
    @Test
    void sameLineChain_emitsNoChainingHint() throws Exception {
        String source = """
            public class Demo {
                public void m() {
                    String r = new StringBuilder("a").append("b").append("c").toString();
                }
            }
            """;
        openDocument("file:///Demo.java", source);

        List<InlayHint> hints = requestHints();
        assertTrue(hints.stream().noneMatch(h ->
                        "StringBuilder".equals(h.getLabel().getLeft())),
                "same-line chain must NOT produce chaining hints, got: " + labels(hints));
    }

    /**
     * Paramètre lambda implicite : « x -> … » sur un List&lt;String&gt;.forEach
     * reçoit « : String » collé à la fin du nom (pas de padding), type kind.
     */
    @Test
    void implicitLambdaParam_emitsTypeHint() throws Exception {
        String source = """
            import java.util.List;
            public class Demo {
                public void m() {
                    List<String> list = List.of("a");
                    list.forEach(x -> System.out.println(x));
                }
            }
            """;
        openDocument("file:///Demo.java", source);

        List<InlayHint> hints = requestHints();
        List<InlayHint> lambda = hints.stream()
                .filter(h -> ": String".equals(h.getLabel().getLeft()))
                .toList();
        assertEquals(1, lambda.size(),
                "expected one ': String' lambda-param hint, got: " + labels(hints));
        InlayHint h = lambda.get(0);
        assertEquals(InlayHintKind.Type, h.getKind());
        // Collé au nom (parité typeHint : pas de padding).
        assertNotEquals(Boolean.TRUE, h.getPaddingLeft());
        assertNotEquals(Boolean.TRUE, h.getPaddingRight());
        // Ligne 4 : `        list.forEach(x -> System.out.println(x));`
        //            x finit à la colonne 22 (8 d'indent + « list.forEach( » = 13).
        assertEquals(4, h.getPosition().getLine());
        assertEquals(22, h.getPosition().getCharacter());
    }

    /** Paramètre lambda typé explicitement « (String x) -> » ⇒ PAS de hint. */
    @Test
    void typedLambdaParam_emitsNoHint() throws Exception {
        String source = """
            import java.util.List;
            public class Demo {
                public void m() {
                    List<String> list = List.of("a");
                    list.forEach((String x) -> System.out.println(x));
                }
            }
            """;
        openDocument("file:///Demo.java", source);

        List<InlayHint> hints = requestHints();
        assertTrue(hints.stream().noneMatch(h -> ": String".equals(h.getLabel().getLeft())),
                "explicitly-typed lambda param must NOT be hinted, got: " + labels(hints));
    }

    /** Lambda à deux paramètres implicites : les DEUX sont hintés. */
    @Test
    void twoImplicitLambdaParams_bothHinted() throws Exception {
        String source = """
            import java.util.Map;
            import java.util.function.BiConsumer;
            public class Demo {
                public void m() {
                    BiConsumer<String, Integer> c = (a, b) -> System.out.println(a + b);
                }
            }
            """;
        openDocument("file:///Demo.java", source);

        List<InlayHint> hints = requestHints();
        assertTrue(hints.stream().anyMatch(h -> ": String".equals(h.getLabel().getLeft())),
                "param 'a' should be hinted ': String', got: " + labels(hints));
        assertTrue(hints.stream().anyMatch(h -> ": Integer".equals(h.getLabel().getLeft())),
                "param 'b' should be hinted ': Integer', got: " + labels(hints));
    }

    // ── Helpers ────────────────────────────────────────────────────

    private void openDocument(String uri, String text) {
        org.eclipse.lsp4j.DidOpenTextDocumentParams params = new org.eclipse.lsp4j.DidOpenTextDocumentParams();
        org.eclipse.lsp4j.TextDocumentItem item = new org.eclipse.lsp4j.TextDocumentItem();
        item.setUri(uri);
        item.setLanguageId("java");
        item.setVersion(1);
        item.setText(text);
        params.setTextDocument(item);
        service.didOpen(params);
    }

    private List<InlayHint> requestHints() throws Exception {
        InlayHintParams params = new InlayHintParams();
        params.setTextDocument(new TextDocumentIdentifier("file:///Demo.java"));
        params.setRange(new Range(new Position(0, 0), new Position(50, Integer.MAX_VALUE)));
        CompletableFuture<List<InlayHint>> future = service.inlayHint(params);
        return future.get(10, TimeUnit.SECONDS);
    }

    private static String labels(List<InlayHint> hints) {
        StringBuilder sb = new StringBuilder("[");
        for (InlayHint h : hints) {
            if (sb.length() > 1) sb.append(", ");
            sb.append('"').append(h.getLabel().getLeft()).append("\"@")
              .append(h.getPosition().getLine()).append(':')
              .append(h.getPosition().getCharacter());
        }
        return sb.append(']').toString();
    }
}
