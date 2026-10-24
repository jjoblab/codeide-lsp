package jo.lspjava.lsp;

import jo.lspjava.api.NavigationTarget;
import jo.lspjava.engine.CompilationEnvironment;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TypeDefinitionParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * AUDIT du panneau GO TO du LSP Java (ADR 0209) — caractérisation
 * BOUT-EN-BOUT à travers la façade réelle ({@link JavaTextDocumentService}) :
 * {@code definition} et {@code typeDefinition} CROSS-FICHIERS (le
 * nouveau chemin projet : intra-fichier → PROJET → binaire décompilé),
 * plus les gardes de discipline (homonymes, binaire, locale).
 *
 * <p>Chaîne éprouvée : le binding ecj prouve la cible au caret ; la
 * classe DÉCLARANTE est localisée par convention de chemin + parse
 * (overlay des tampons ouverts AVANT le disque) ; le NOM y est borné
 * au jeton. Une cible PROJET n'atterrit JAMAIS dans le stub décompilé
 * {@code jdt://decompiled/…} ; une cible BINAIRE n'atterrit jamais
 * dans les sources.</p>
 */
class AuditNavigationJavaTest {

    @TempDir
    static Path tmp;

    static JavaTextDocumentService service;
    static Path srcRoot;

    static String findAndroidJar() {
        String[] candidates = {
                "/home/z/tools/android-sdk/platforms/android-37.2/android.jar",
                System.getenv("ANDROID_HOME") + "/platforms/android-37.2/android.jar",
                System.getenv("ANDROID_SDK_ROOT") + "/platforms/android-37.2/android.jar"
        };
        for (String c : candidates) {
            if (c != null && new File(c).isFile()) return c;
        }
        File platforms = new File("/home/z/android-sdk/platforms");
        File[] dirs = platforms.isDirectory() ? platforms.listFiles() : null;
        if (dirs != null) {
            for (File p : dirs) {
                File jar = new File(p, "android.jar");
                if (jar.isFile()) return jar.getAbsolutePath();
            }
        }
        return null;
    }

    @BeforeAll
    static void setUp() throws Exception {
        String androidJar = findAndroidJar();
        assumeTrue(androidJar != null, "android.jar absent");
        srcRoot = Files.createDirectories(tmp.resolve("src"));
        CompilationEnvironment env = new CompilationEnvironment(
                List.of(srcRoot.toFile()),
                List.of(new File(androidJar)), "17");
        service = new JavaTextDocumentService(env, null);
    }

    private static String open(String relPath, String source) {
        Path file = srcRoot.resolve(relPath);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, source);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String uri = "file://" + file.toAbsolutePath();
        service.didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "java", 1, source)));
        return uri;
    }

    private static int offsetOf(String source, String needle) {
        return source.indexOf(needle);
    }

    private static Position positionOf(String source, int offset) {
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < offset && i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new Position(line, offset - lineStart);
    }

    private static List<Location> definition(String uri, String source, int offset) {
        try {
            DefinitionParams params = new DefinitionParams(
                    new TextDocumentIdentifier(uri), positionOf(source, offset));
            Either<List<? extends Location>, List<? extends org.eclipse.lsp4j.LocationLink>> result =
                    service.definition(params).get();
            return result == null || result.getLeft() == null
                    ? List.of() : List.copyOf(result.getLeft());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Location> typeDefinition(String uri, String source, int offset) {
        try {
            TypeDefinitionParams params = new TypeDefinitionParams(
                    new TextDocumentIdentifier(uri), positionOf(source, offset));
            Either<List<? extends Location>, List<? extends org.eclipse.lsp4j.LocationLink>> result =
                    service.typeDefinition(params).get();
            return result == null || result.getLeft() == null
                    ? List.of() : List.copyOf(result.getLeft());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<NavigationTarget> superDefinition(String uri, String source, int offset) {
        try {
            org.eclipse.lsp4j.TextDocumentPositionParams params =
                    new org.eclipse.lsp4j.TextDocumentPositionParams(
                            new TextDocumentIdentifier(uri), positionOf(source, offset));
            List<NavigationTarget> result = service.superDefinition(params).get();
            return result == null ? List.of() : result;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Fixtures
    // ══════════════════════════════════════════════════════════════

    static final String BASE = """
            package demo;

            public class Base {
                int compteur;

                void run() {
                    compteur++;
                }

                int doubler(int v) {
                    return v * 2;
                }

                static Base creer() {
                    return new Base();
                }
            }
            """;

    static final String IMPL_A = """
            package demo;

            public class ImplA extends Base {
                int tag;

                @Override
                void run() {
                    compteur = 1;
                }
            }
            """;

    static final String CONSOMMATEUR = """
            package demo;

            public class Consommateur {
                void use(Base b, ImplA a) {
                    b.run();
                    int x = b.doubler(21);
                    int y = a.tag;
                    int z = b.compteur;
                    Base autre = Base.creer();
                }
            }
            """;

    static final String UTILITAIRE = """
            package demo;

            public class Utilitaire {
                static int triple(int v) {
                    return v * 3;
                }
            }
            """;

    static final String IMPORT_STATIQUE = """
            package demo;

            import static demo.Utilitaire.triple;

            public class Statique {
                int use() {
                    return triple(2);
                }
            }
            """;

    static final String PORTEUR_UN = """
            package demo;

            class PorteurUn {
                void run() {
                }
            }
            """;

    static final String PORTEUR_DEUX = """
            package demo;

            class PorteurDeux {
                void run(int x) {
                }
            }
            """;

    static final String APPELEUR_DEUX_RUN = """
            package demo;

            public class AppeleurDeuxRun {
                void use() {
                    new PorteurUn().run();
                    new PorteurDeux().run(1);
                }
            }
            """;

    // ══════════════════════════════════════════════════════════════
    // definition — chemin PROJET cross-fichiers (ADR 0209)
    // ══════════════════════════════════════════════════════════════

    @Test
    void definition_methodeInterFichiers_viseLaClasseDeclarante() {
        open("demo/Base.java", BASE);
        open("demo/ImplA.java", IMPL_A);
        String uri = open("demo/Consommateur.java", CONSOMMATEUR);

        // b.run() : la DÉCLARATION vit dans Base.java (fichier fermé du scan).
        List<Location> hits = definition(uri, CONSOMMATEUR, offsetOf(CONSOMMATEUR, "b.run()") + 2);
        assertEquals(1, hits.size(), "une cible : " + hits);
        Location hit = hits.get(0);
        assertTrue(hit.getUri().endsWith("Base.java"), "cible dans Base.java : " + hit.getUri());
        assertEquals(positionOf(BASE, offsetOf(BASE, "void run()") + "void ".length()),
                hit.getRange().getStart(), "plage du NOM run dans Base.java");
    }

    @Test
    void definition_methodeHeritee_viseLeFichierDuDeclarant() {
        open("demo/Base.java", BASE);
        open("demo/ImplA.java", IMPL_A);
        String uri = open("demo/Consommateur.java", CONSOMMATEUR);

        // b.doubler(21) sur un récepteur Base : déclaré dans Base.
        List<Location> hits = definition(uri, CONSOMMATEUR, offsetOf(CONSOMMATEUR, "b.doubler") + 2);
        assertEquals(1, hits.size(), "une cible : " + hits);
        Location hit = hits.get(0);
        assertTrue(hit.getUri().endsWith("Base.java"), "le DECLARANT porte la cible : " + hit.getUri());
        assertEquals(positionOf(BASE, offsetOf(BASE, "int doubler") + "int ".length()),
                hit.getRange().getStart(), "plage du NOM doubler");
    }

    @Test
    void definition_champInterFichiers_viseSaDeclaration() {
        open("demo/Base.java", BASE);
        open("demo/ImplA.java", IMPL_A);
        String uri = open("demo/Consommateur.java", CONSOMMATEUR);

        // a.tag, caret SUR « tag » (le segment visé — le récepteur « a »
        // donnerait le paramètre, le point le dernier chaînon) : le
        // champ vit dans ImplA.java.
        List<Location> hits = definition(uri, CONSOMMATEUR, offsetOf(CONSOMMATEUR, "a.tag") + 2);
        assertEquals(1, hits.size(), "une cible : " + hits);
        assertTrue(hits.get(0).getUri().endsWith("ImplA.java"), "le champ tag vit dans ImplA : " + hits.get(0).getUri());
        assertEquals(positionOf(IMPL_A, offsetOf(IMPL_A, "int tag;") + "int ".length()),
                hits.get(0).getRange().getStart(), "plage du NOM tag");
    }

    @Test
    void definition_referenceDeType_viseLaClasse() {
        open("demo/Base.java", BASE);
        open("demo/ImplA.java", IMPL_A);
        String uri = open("demo/Consommateur.java", CONSOMMATEUR);

        // Le caret sur « Base » (référence de type dans la signature).
        List<Location> hits = definition(uri, CONSOMMATEUR, offsetOf(CONSOMMATEUR, "Base b"));
        assertEquals(1, hits.size(), "une cible : " + hits);
        assertTrue(hits.get(0).getUri().endsWith("Base.java"), "la classe : " + hits.get(0).getUri());
        assertEquals(positionOf(BASE, offsetOf(BASE, "class Base") + "class ".length()),
                hits.get(0).getRange().getStart(), "plage du NOM Base");
    }

    @Test
    void definition_constructeurProjet_viseLeNomDeLaClasse() {
        open("demo/Base.java", BASE);
        String uri = open("demo/Consommateur.java", CONSOMMATEUR);

        // Base.creer() rend un Base ; le caret sur « creer » : méthode
        // statique PROJET — la cible est SA déclaration dans Base.java.
        List<Location> hits = definition(uri, CONSOMMATEUR, offsetOf(CONSOMMATEUR, "Base.creer()") + 5);
        assertEquals(1, hits.size(), "une cible : " + hits);
        assertTrue(hits.get(0).getUri().endsWith("Base.java"), "creer() vit dans Base : " + hits.get(0).getUri());
        assertEquals(positionOf(BASE, offsetOf(BASE, "static Base creer") + "static Base ".length()),
                hits.get(0).getRange().getStart(), "plage du NOM creer");
    }

    @Test
    void definition_importStatique_viseLaMethodeImportee() {
        open("demo/Utilitaire.java", UTILITAIRE);
        String uri = open("demo/Statique.java", IMPORT_STATIQUE);

        // triple(2) sans qualification : le binding résout par l'import
        // STATIQUE — la cible vit dans Utilitaire.java.
        List<Location> hits = definition(uri, IMPORT_STATIQUE, offsetOf(IMPORT_STATIQUE, "triple(2)") + 1);
        assertEquals(1, hits.size(), "une cible : " + hits);
        assertTrue(hits.get(0).getUri().endsWith("Utilitaire.java"),
                "l'import static résout : " + hits.get(0).getUri());
        assertEquals(positionOf(UTILITAIRE, offsetOf(UTILITAIRE, "int triple") + "int ".length()),
                hits.get(0).getRange().getStart(), "plage du NOM triple");
    }

    @Test
    void definition_homonymes_deuxRun_differentParBindings() {
        open("demo/PorteurUn.java", PORTEUR_UN);
        open("demo/PorteurDeux.java", PORTEUR_DEUX);
        String uri = open("demo/AppeleurDeuxRun.java", APPELEUR_DEUX_RUN);

        // DEUX « run » dans le projet (fichiers distincts) : le BINDING
        // discrimine — chaque invocation vise SON déclarant, l'arité
        // départage les surcharges homonymes.
        List<Location> un = definition(uri, APPELEUR_DEUX_RUN,
                offsetOf(APPELEUR_DEUX_RUN, "new PorteurUn().run()") + 18);
        assertEquals(1, un.size(), "une cible : " + un);
        assertTrue(un.get(0).getUri().endsWith("PorteurUn.java"), "fichier déclarant : " + un.get(0).getUri());
        assertEquals(positionOf(PORTEUR_UN, offsetOf(PORTEUR_UN, "void run()") + "void ".length()),
                un.get(0).getRange().getStart(), "le run de PorteurUn, pas celui de PorteurDeux");

        List<Location> deux = definition(uri, APPELEUR_DEUX_RUN,
                offsetOf(APPELEUR_DEUX_RUN, "new PorteurDeux().run(1)") + 19);
        assertEquals(1, deux.size(), "une cible : " + deux);
        assertTrue(deux.get(0).getUri().endsWith("PorteurDeux.java"), "fichier déclarant : " + deux.get(0).getUri());
        assertEquals(positionOf(PORTEUR_DEUX, offsetOf(PORTEUR_DEUX, "void run(int x)") + "void ".length()),
                deux.get(0).getRange().getStart(), "le run(int) de PorteurDeux — l'arité départage");
    }

    @Test
    void definition_locale_viseLeFichierCourant() {
        String SOURCE = """
                package demo;

                public class LocaleUse {
                    void use() {
                        int locale = 1;
                        int x = locale + 1;
                    }
                }
                """;
        String uri = open("demo/LocaleUse.java", SOURCE);
        // Une LOCALE reste INTRA-FICHIER (elle y vit) — jamais un saut
        // cross-fichier hasardeux.
        List<Location> hits = definition(uri, SOURCE, offsetOf(SOURCE, "locale + 1"));
        assertEquals(1, hits.size(), "la locale est servie : " + hits);
        assertTrue(hits.get(0).getUri().endsWith("LocaleUse.java"), "le fichier courant");
        assertEquals(positionOf(SOURCE, offsetOf(SOURCE, "int locale") + "int ".length()),
                hits.get(0).getRange().getStart(), "plage du nom de la locale");
    }

    @Test
    void definition_cibleBinaire_resteDecompilee() {
        String SOURCE = """
                package demo;

                import java.util.HashMap;

                public class BinaireUse {
                    void use() {
                        HashMap<String, String> carte = new HashMap<>();
                        carte.put("a", "b");
                    }
                }
                """;
        String uri = open("demo/BinaireUse.java", SOURCE);
        // « put » : méthode BINAIRE (java.util.HashMap) — le fallback
        // décompilé sert, JAMAIS le chemin projet.
        List<Location> hits = definition(uri, SOURCE, offsetOf(SOURCE, "carte.put") + 6);
        assertEquals(1, hits.size(), "une cible : " + hits);
        assertTrue(hits.get(0).getUri().startsWith("jdt://decompiled/"),
                "la cible binaire est décompilée : " + hits.get(0).getUri());
    }

    // ══════════════════════════════════════════════════════════════
    // typeDefinition — chemin PROJET cross-fichiers (ADR 0209)
    // ══════════════════════════════════════════════════════════════

    @Test
    void typeDefinition_variableProjet_viseLaClasse() {
        open("demo/Base.java", BASE);
        open("demo/ImplA.java", IMPL_A);
        String uri = open("demo/Consommateur.java", CONSOMMATEUR);

        // Caret SUR « a » (paramètre ImplA — pas sur le point : le
        // point vise le dernier chaînon, le champ) : le TYPE de la
        // variable déclaré dans ImplA.java.
        List<Location> hits = typeDefinition(uri, CONSOMMATEUR, offsetOf(CONSOMMATEUR, "a.tag"));
        assertEquals(1, hits.size(), "une cible : " + hits);
        assertTrue(hits.get(0).getUri().endsWith("ImplA.java"),
                "le type ImplA vit dans ImplA.java : " + hits.get(0).getUri());
        assertEquals(positionOf(IMPL_A, offsetOf(IMPL_A, "class ImplA") + "class ".length()),
                hits.get(0).getRange().getStart(), "plage du NOM ImplA");
    }

    @Test
    void typeDefinition_typeBinaire_resteDecompile() {
        String SOURCE = """
                package demo;

                public class ChaineUse {
                    String use() {
                        return "x";
                    }
                }
                """;
        String uri = open("demo/ChaineUse.java", SOURCE);
        // Le caret sur « String » (type binaire java.lang) : fallback
        // décompilé — jamais le chemin projet.
        List<Location> hits = typeDefinition(uri, SOURCE, offsetOf(SOURCE, "String use()"));
        assertEquals(1, hits.size(), "une cible : " + hits);
        assertTrue(hits.get(0).getUri().startsWith("jdt://decompiled/java/lang/String"),
                "java.lang.String décompilé : " + hits.get(0).getUri());
    }

    // ══════════════════════════════════════════════════════════════
    // super — garde de parité (miroir NavigationFacadeTest)
    // ══════════════════════════════════════════════════════════════

    @Test
    void super_surOverride_viseLaMethodeDuSupertype() {
        open("demo/Base.java", BASE);
        String uri = open("demo/ImplA.java", IMPL_A);
        List<NavigationTarget> hits = superDefinition(uri, IMPL_A, offsetOf(IMPL_A, "void run()") + 5);
        assertEquals(1, hits.size(), "run() de Base : " + hits);
        assertEquals("run  ·  Base", hits.get(0).label, "libellé picker");
    }

    @Test
    void super_surChampDeClasseSansSuperProjet_vide() {
        open("demo/Base.java", BASE);
        String uri = open("demo/ImplA.java", IMPL_A);
        // tag : pas un membre outrepassant — retombée sur les supertypes
        // DIRECTS du type en contexte (ImplA → Base).
        List<NavigationTarget> hits = superDefinition(uri, IMPL_A, offsetOf(IMPL_A, "int tag;") + 4);
        assertEquals(1, hits.size(), "le supertype direct : " + hits);
        assertEquals("Base  ·  demo", hits.get(0).label);
    }

    // ══════════════════════════════════════════════════════════════
    // Coexistence des trois chemins — discipline de l'ordre
    // ══════════════════════════════════════════════════════════════

    @Test
    void ordreDesChemins_projetAvantBinaire_binaireQuandPasProjet() {
        open("demo/Base.java", BASE);
        open("demo/ImplA.java", IMPL_A);
        String uri = open("demo/Consommateur.java", CONSOMMATEUR);
        // PROJET (b.run) puis BINAIRE (HashMap.put) dans le MÊME document :
        // chaque requête rend SON régime — aucune fuite entre chemins.
        List<Location> projet = definition(uri, CONSOMMATEUR, offsetOf(CONSOMMATEUR, "b.run()") + 2);
        assertEquals(1, projet.size());
        assertTrue(projet.get(0).getUri().endsWith("Base.java"));

        String SOURCE_BIN = """
                package demo;

                import java.util.HashMap;

                public class MixUse {
                    void use() {
                        new HashMap<String, String>().size();
                    }
                }
                """;
        String uriBin = open("demo/MixUse.java", SOURCE_BIN);
        List<Location> binaire = definition(uriBin, SOURCE_BIN, offsetOf(SOURCE_BIN, ".size()") + 1);
        assertEquals(1, binaire.size());
        assertTrue(binaire.get(0).getUri().startsWith("jdt://decompiled/"),
                "size() est binaire : " + binaire.get(0).getUri());
    }

    @Test
    void definition_documentInconnu_ouCurseurHorsSymbole_vide() {
        // Document JAMAIS ouvert : offsets sans texte — vide honnête,
        // jamais d'exception.
        DefinitionParams params = new DefinitionParams(
                new TextDocumentIdentifier("file:///introuvable.java"),
                new Position(0, 0));
        Either<List<? extends Location>, List<? extends org.eclipse.lsp4j.LocationLink>> result =
                service.definition(params).join();
        assertNotNull(result);
        assertTrue(result.getLeft().isEmpty(), "aucune cible sur un document inconnu");
    }
}
