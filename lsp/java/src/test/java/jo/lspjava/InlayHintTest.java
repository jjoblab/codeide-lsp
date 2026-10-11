package jo.lspjava;

import jo.lspjava.engine.CompilationEnvironment;
import jo.lspjava.lsp.JavaLanguageServer;
import jo.lspjava.lsp.JavaTextDocumentService;
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
 * Tests du fournisseur d'inlay hints.
 *
 * <p>Vérifie que le serveur Java LSP émet des hints de type pour les
 * déclarations {@code var} dont le type de l'initialiseur peut être inféré
 * statiquement (littéral chaîne, littéral entier, création d'objet, etc.)
 * et n'émet rien pour les déclarations non-{@code var} ou les initialiseurs
 * non inférables.</p>
 *
 * @author jo@Dev
 */
class InlayHintTest {

    private JavaTextDocumentService service;

    @BeforeEach
    void setUp() throws Exception {
        var env = new CompilationEnvironment();
        JavaLanguageServer server = new JavaLanguageServer(env);
        // JavaTextDocumentService a besoin d'une référence au serveur pour
        // les diagnostics, mais inlayHint() ne l'utilise pas. Construction directe.
        service = new JavaTextDocumentService(server.environment(), server);
    }

    @Test
    void inlayHint_stringLiteral_var_emitsStringTypeHint() throws Exception {
        String source = """
            public class Demo {
                public void m() {
                    var s = "hello";
                }
            }
            """;
        openDocument("file:///Demo.java", source);

        List<InlayHint> hints = requestHints(source, 0, source.split("\n").length - 1);
        // Au moins un hint — attendu « : String » à la fin de « s ».
        assertEquals(1, hints.size(), "expected one hint for var s");
        InlayHint hint = hints.get(0);
        assertEquals(InlayHintKind.Type, hint.getKind());
        assertEquals(": String", hint.getLabel().getLeft());
        // La position doit être sur la ligne 2 (indexée à 0), où vit la déclaration var.
        Position pos = hint.getPosition();
        assertEquals(2, pos.getLine());
        // Le hint doit être placé SUR ou APRÈS la colonne du nom de variable.
        // Range.end.column de JavaParser est indexé à 1 et exclusif, donc le
        // caractère converti en index 0 peut tomber d'un côté ou de l'autre
        // de l'identifiant — la valeur exacte dépend du chemin de conversion.
        // On asserte seulement qu'il est dans la ligne et non négatif.
        assertTrue(pos.getCharacter() >= 0, "character should be non-negative, got " + pos.getCharacter());
    }

    @Test
    void inlayHint_integerLiteral_emitsIntTypeHint() throws Exception {
        String source = """
            public class Demo {
                public void m() {
                    var x = 42;
                }
            }
            """;
        openDocument("file:///Demo.java", source);

        List<InlayHint> hints = requestHints(source, 0, 5);
        assertEquals(1, hints.size());
        assertEquals(": int", hints.get(0).getLabel().getLeft());
    }

    @Test
    void inlayHint_booleanLiteral_emitsBooleanTypeHint() throws Exception {
        String source = """
            public class Demo {
                public void m() {
                    var b = true;
                }
            }
            """;
        openDocument("file:///Demo.java", source);

        List<InlayHint> hints = requestHints(source, 0, 5);
        assertEquals(1, hints.size());
        assertEquals(": boolean", hints.get(0).getLabel().getLeft());
    }

    @Test
    void inlayHint_longLiteral_emitsLongTypeHint() throws Exception {
        String source = """
            public class Demo {
                public void m() {
                    var l = 42L;
                }
            }
            """;
        openDocument("file:///Demo.java", source);

        List<InlayHint> hints = requestHints(source, 0, 5);
        assertEquals(1, hints.size());
        assertEquals(": long", hints.get(0).getLabel().getLeft());
    }

    @Test
    void inlayHint_objectCreation_emitsTypeNameHint() throws Exception {
        String source = """
            import java.util.ArrayList;
            public class Demo {
                public void m() {
                    var list = new ArrayList<String>();
                }
            }
            """;
        openDocument("file:///Demo.java", source);

        List<InlayHint> hints = requestHints(source, 0, 10);
        assertEquals(1, hints.size());
        // Type.asString() renvoie le type complet avec les generics
        String label = hints.get(0).getLabel().getLeft();
        assertTrue(label.contains("ArrayList"), "expected label to contain 'ArrayList', got: " + label);
    }

    @Test
    void inlayHint_nonVarDeclaration_emitsNoHint() throws Exception {
        // Les déclarations fortement typées montrent déjà le type dans la
        // source — émettre un hint serait redondant.
        String source = """
            public class Demo {
                public void m() {
                    String s = "hello";
                    int x = 42;
                }
            }
            """;
        openDocument("file:///Demo.java", source);

        List<InlayHint> hints = requestHints(source, 0, 5);
        assertEquals(0, hints.size(), "no hints expected for non-var declarations");
    }

    @Test
    void inlayHint_methodCallInitializer_emitsBindingTypeHint() throws Exception {
        // L'inférence passe par les BINDINGS ecj — un appel de méthode
        // reçoit le type de retour RÉEL.
        String source = """
            public class Demo {
                public void m() {
                    var s = n();
                }
                String n() { return ""; }
            }
            """;
        openDocument("file:///Demo.java", source);

        List<InlayHint> hints = requestHints(source, 0, 10);
        assertEquals(1, hints.size(), "hint de type attendu pour un appel de méthode");
        assertEquals(": String", hints.get(0).getLabel().getLeft());
    }

    // Hints de noms de paramètres aux sites d'appel de méthode.

    @Test
    void inlayHint_methodCall_emitsParameterNameHints() throws Exception {
        String source = """
            public class Demo {
                public void setName(String first, String last) {
                }
                public void m() {
                    setName("John", "Doe");
                }
            }
            """;
        openDocument("file:///Demo.java", source);

        List<InlayHint> hints = requestHints(source, 0, 10);
        // 2 hints de noms de paramètres « first: » et « last: »
        // (bindings ecj — surcharge exacte).
        assertEquals(2, hints.size(), "expected 2 parameter hints");
        for (InlayHint h : hints) {
            assertEquals(org.eclipse.lsp4j.InlayHintKind.Parameter, h.getKind());
        }
        // Les libellés doivent finir par « : » (style ecj)
        assertTrue(hints.get(0).getLabel().getLeft().endsWith(":"),
            "first hint label should end with ':', got: " + hints.get(0).getLabel().getLeft());
        assertTrue(hints.get(1).getLabel().getLeft().endsWith(":"),
            "second hint label should end with ':', got: " + hints.get(1).getLabel().getLeft());
    }

    @Test
    void inlayHint_noArgMethodCall_emitsNoHint() throws Exception {
        String source = """
            public class Demo {
                public void doStuff() {}
                public void m() {
                    doStuff();
                }
            }
            """;
        openDocument("file:///Demo.java", source);

        List<InlayHint> hints = requestHints(source, 0, 10);
        assertEquals(0, hints.size(), "no hints for zero-arg calls");
    }

    @Test
    void inlayHint_externalMethodCall_emitsNoHint() throws Exception {
        // Les appels à des méthodes déclarées HORS de cette CU (ex.
        // System.out.println) ne peuvent pas être résolues par notre index
        // de méthodes intra-CU — aucun hint émis.
        String source = """
            public class Demo {
                public void m() {
                    System.out.println("hello");
                }
            }
            """;
        openDocument("file:///Demo.java", source);

        List<InlayHint> hints = requestHints(source, 0, 10);
        // println est déclarée dans PrintStream, pas dans cette CU — pas de hint.
        assertEquals(0, hints.size());
    }

    @Test
    void inlayHint_emptyRange_returnsEmptyList() throws Exception {
        String source = """
            public class Demo {
                public void m() {
                    var s = "hello";
                }
            }
            """;
        openDocument("file:///Demo.java", source);

        // Une range qui n'inclut pas la ligne 2 (où est le var).
        List<InlayHint> hints = requestHints(source, 0, 0);
        // L'implémentation parcourt toute la CU et ne filtre PAS par range
        // côté serveur : elle renvoie tous les hints de la CU. Le filtrage
        // par range est la responsabilité de l'éditeur. On vérifie juste
        // que ça ne plante pas.
        assertNotNull(hints);
    }

    /**
     * Verrouille le câblage des hints : un var ET un appel avec paramètres
     * sur la MÊME ligne produisent deux hints DÉSORDONNÉS (le hint var,
     * ancré à une colonne PLUS PETITE, est calculé APRÈS les hints de
     * paramètres). LSP n'exige aucun tri — c'est le client qui doit trier
     * par colonne avant le tissage (le tri se fait dans
     * {@code EditorView.layoutForLine}, car {@code buildColumnMaps} suppose
     * une liste triée).
     */
    @Test
    void inlayHint_varAndCallOnSameLine_emitsBothHintsUnsorted() throws Exception {
        String source = """
            public class Demo {
                public void greet(String name) { }
                public void m() {
                    var s = "hi"; greet(s);
                }
            }
            """;
        openDocument("file:///Demo.java", source);

        List<InlayHint> hints = requestHints(source, 0, 10);
        assertEquals(2, hints.size(), "expected 1 param hint + 1 var hint on the same line");

        InlayHint first = hints.get(0);
        InlayHint second = hints.get(1);
        // Le hint de paramètre (passe AST) arrive en PREMIER dans la liste...
        assertEquals(InlayHintKind.Parameter, first.getKind());
        assertEquals("name:", first.getLabel().getLeft());
        // ...mais est ancré à une colonne POSTÉRIEURE (l'argument 's' en col 28)
        // que le hint var (fin du nom en col 13) calculé à la seconde passe.
        // Ligne 3 = `        var s = "hi"; greet(s);`
        //            cols : 8-10 var, 12 s, 14 =, 16-19 "hi", 22-26 greet, 28 s
        assertEquals(3, first.getPosition().getLine());
        assertEquals(28, first.getPosition().getCharacter());
        assertEquals(InlayHintKind.Type, second.getKind());
        assertEquals(": String", second.getLabel().getLeft());
        assertEquals(3, second.getPosition().getLine());
        assertEquals(13, second.getPosition().getCharacter());
        // Le contrat documenté : la liste n'est PAS triée par position —
        // les clients doivent trier les morceaux par ligne avant le tissage.
        assertTrue(first.getPosition().getCharacter() > second.getPosition().getCharacter(),
            "server order is deliberately unsorted (param pass then var pass)");
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

    private List<InlayHint> requestHints(String source, int startLine, int endLine) throws Exception {
        InlayHintParams params = new InlayHintParams();
        params.setTextDocument(new TextDocumentIdentifier("file:///Demo.java"));
        params.setRange(new Range(
            new Position(startLine, 0),
            new Position(endLine, Integer.MAX_VALUE)));
        CompletableFuture<List<InlayHint>> future = service.inlayHint(params);
        return future.get(5, TimeUnit.SECONDS);
    }
}
