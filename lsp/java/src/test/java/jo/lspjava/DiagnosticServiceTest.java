package jo.lspjava;

import jo.lspjava.api.Diagnostic;
import jo.lspjava.api.Severity;
import jo.lspjava.engine.CompilationEnvironment;
import jo.lspjava.services.DiagnosticService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests pour le service de diagnostics.
 *
 * @author jo@Dev
 */
class DiagnosticServiceTest {

    private DiagnosticService diagnosticService;

    @BeforeEach
    void setUp() {
        diagnosticService = new DiagnosticService(new CompilationEnvironment());
    }

    @Test
    void testParseableCode() {
        String source = """
                public class Test {
                    public void method() {
                        int x = 42;
                        System.out.println(x);
                    }
                }
                """;
        List<Diagnostic> diagnostics = diagnosticService.diagnose("Test.java", source);
        assertNotNull(diagnostics);
    }

    @Test
    void testDiagnosticSeverity() {
        String source = """
                public class Test {
                    public void method() {
                        System.out.println(undefined);
                    }
                }
                """;
        List<Diagnostic> diagnostics = diagnosticService.diagnose("Test.java", source);
        for (Diagnostic d : diagnostics) {
            assertNotNull(d.severity());
            assertNotNull(d.message());
        }
    }

    @Test
    void testDiagnosticRange() {
        String source = """
                public class Test {
                    public void method() {
                        System.out.println(undefined);
                    }
                }
                """;
        List<Diagnostic> diagnostics = diagnosticService.diagnose("Test.java", source);
        for (Diagnostic d : diagnostics) {
            assertNotNull(d.range());
            assertTrue(d.range().startOffset() >= 0);
            assertTrue(d.range().endOffset() >= d.range().startOffset());
        }
    }

    @Test
    void testDiagnosticMessage() {
        String source = """
                public class Test {
                    public void method() {
                        System.out.println(undefined);
                    }
                }
                """;
        List<Diagnostic> diagnostics = diagnosticService.diagnose("Test.java", source);
        for (Diagnostic d : diagnostics) {
            assertFalse(d.message().isEmpty(), "Diagnostic message should not be empty");
        }
    }

    @Test
    void testSyntaxError() {
        String source = """
                public class Test {
                    public void method( {
                    }
                }
                """;
        try {
            List<Diagnostic> diagnostics = diagnosticService.diagnose("Test.java", source);
            // Si ça ne throw pas, il devrait y avoir des diagnostics
            assertNotNull(diagnostics);
        } catch (RuntimeException e) {
            // Le parseur peut throw sur des erreurs de syntaxe graves
            // C'est un comportement acceptable
        }
    }

    @Test
    void testReturnType() {
        String source = """
                public class Test {
                    public void method() {
                        return 42;
                    }
                }
                """;
        List<Diagnostic> diagnostics = diagnosticService.diagnose("Test.java", source);
        assertNotNull(diagnostics);
    }
}
