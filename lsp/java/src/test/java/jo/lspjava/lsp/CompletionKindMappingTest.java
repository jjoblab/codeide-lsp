package jo.lspjava.lsp;

import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import jo.lspjava.engine.CompilationEnvironment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Vérification BOUT-EN-BOUT des kinds LSP côté serveur : la
 * chaîne complète {@code EcjCompletionEngine → api.CompletionItem.Kind →
 * CompletionItemKind LSP4J (+ champ data pour les annotations)} — c'est
 * ce que le badge de type du popup consomme côté client.
 *
 * @author jo@Dev
 */
class CompletionKindMappingTest {

    @TempDir
    static Path tmp;

    static JavaTextDocumentService service;

    @BeforeAll
    static void setUp() throws Exception {
        String androidJar = findAndroidJar();
        Assumptions.assumeTrue(androidJar != null, "android.jar absent");
        Path src = Files.createDirectories(tmp.resolve("src/com/example/k"));
        CompilationEnvironment env = new CompilationEnvironment(
                List.of(src.toFile()), List.of(new File(androidJar)), "17");
        service = new JavaTextDocumentService(env, null);
    }

    private static String findAndroidJar() {
        String[] candidates = {
                "/home/z/tools/android-sdk/platforms/android-34/android.jar",
                System.getenv("ANDROID_HOME") + "/platforms/android-34/android.jar",
                System.getenv("ANDROID_SDK_ROOT") + "/platforms/android-34/android.jar"
        };
        for (String c : candidates) {
            if (c != null && new File(c).isFile()) return c;
        }
        File platforms = new File("/home/z/tools/android-sdk/platforms");
        File[] dirs = platforms.isDirectory() ? platforms.listFiles() : null;
        if (dirs != null) {
            for (File p : dirs) {
                File jar = new File(p, "android.jar");
                if (jar.isFile()) return jar.getAbsolutePath();
            }
        }
        return null;
    }

    private List<org.eclipse.lsp4j.CompletionItem> complete(String uri, String source, int offset) {
        service.didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "java", 1, source)));
        CompletionParams params = new CompletionParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        // offset → line/character
        int line = 0, col = 0;
        for (int i = 0; i < offset && i < source.length(); i++) {
            if (source.charAt(i) == '\n') { line++; col = 0; }
            else col++;
        }
        params.setPosition(new Position(line, col));
        try {
            Either<List<org.eclipse.lsp4j.CompletionItem>, org.eclipse.lsp4j.CompletionList> r =
                    service.completion(params).get();
            return r.isLeft() ? r.getLeft() : r.getRight().getItems();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    @Test
    void memberCompletionCarriesMethodAndFieldKinds() {
        String source = "package com.example.k;\n"
                + "public class Main {\n"
                + "    int myField = 1;\n"
                + "    void myMethod() {}\n"
                + "    void use() {\n"
                + "        my\n"
                + "    }\n"
                + "}\n";
        int offset = source.indexOf("my\n") + 2;
        List<org.eclipse.lsp4j.CompletionItem> items =
                complete("file:///t1.java", source, offset);
        boolean sawMethod = false, sawField = false;
        for (org.eclipse.lsp4j.CompletionItem it : items) {
            if ("myMethod".equals(it.getLabel())
                    && it.getKind() == CompletionItemKind.Method) sawMethod = true;
            if ("myField".equals(it.getLabel())
                    && it.getKind() == CompletionItemKind.Field) sawField = true;
        }
        assertTrue(sawMethod, "myMethod doit porter kind=Method : "
                + kindsOf(items, "myMethod"));
        assertTrue(sawField, "myField doit porter kind=Field : "
                + kindsOf(items, "myField"));
    }

    @Test
    void packageCompletionCarriesModuleKind() {
        String source = "package com.example.k;\n"
                + "import android.\n"
                + "public class Main {\n}\n";
        int offset = source.indexOf("android.") + "android.".length();
        List<org.eclipse.lsp4j.CompletionItem> items =
                complete("file:///t2.java", source, offset);
        boolean sawModule = false;
        for (org.eclipse.lsp4j.CompletionItem it : items) {
            if ("content".equals(it.getLabel())
                    && it.getKind() == CompletionItemKind.Module) sawModule = true;
        }
        assertTrue(sawModule, "le sous-paquet content doit porter kind=Module : "
                + kindsOf(items, "content"));
    }

    @Test
    void annotationTypeCarriesDataTag() {
        // TYPE_REFERENCE sur « Overrid » : java.lang.Override est un type
        // ANNOTATION — le kind LSP reste Class (le protocole n'a rien de
        // mieux) mais le champ data transporte « annotation » (badge « @ »).
        String source = "package com.example.k;\n"
                + "public class Main {\n"
                + "    Overrid\n"
                + "}\n";
        int offset = source.indexOf("Overrid") + "Overrid".length();
        List<org.eclipse.lsp4j.CompletionItem> items =
                complete("file:///t3.java", source, offset);
        boolean sawTagged = false;
        for (org.eclipse.lsp4j.CompletionItem it : items) {
            if ("Override".equals(it.getLabel())
                    && it.getKind() == CompletionItemKind.Class
                    && "annotation".equals(String.valueOf(it.getData()))) {
                sawTagged = true;
            }
        }
        assertTrue(sawTagged, "Override doit voyager en Class + data=\"annotation\" : "
                + kindsOf(items, "Override"));
    }

    @Test
    void typeReferenceCarriesClassKind() {
        String source = "package com.example.k;\n"
                + "public class Main {\n"
                + "    Strin\n"
                + "}\n";
        int offset = source.indexOf("Strin") + "Strin".length();
        List<org.eclipse.lsp4j.CompletionItem> items =
                complete("file:///t4.java", source, offset);
        boolean sawClass = false;
        for (org.eclipse.lsp4j.CompletionItem it : items) {
            if ("String".equals(it.getLabel())
                    && it.getKind() == CompletionItemKind.Class) sawClass = true;
        }
        assertTrue(sawClass, "String doit porter kind=Class : "
                + kindsOf(items, "String"));
    }

    private static String kindsOf(List<org.eclipse.lsp4j.CompletionItem> items,
                                  String label) {
        for (org.eclipse.lsp4j.CompletionItem it : items) {
            if (label.equals(it.getLabel())) {
                return label + " → kind=" + it.getKind()
                        + " data=" + it.getData();
            }
        }
        return label + " → ABSENT (" + items.size() + " items)";
    }
}
