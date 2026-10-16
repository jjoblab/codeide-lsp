package jo.lspjava.ecj;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du lot S2-b (moteur) : hiérarchies de TYPES et d'APPELS.
 *
 * <p>Projet multi-fichiers réel (racines sources sur un {@link TempDir}) :
 * {@code demo.Base} ← {@code demo.Enfant}, {@code demo.Contrat} implémenté,
 * et {@code demo.Appelant} qui appelle {@code Base.utilise}. Les éléments
 * doivent être localisés dans les SOURCES du projet (jamais d'URI factice
 * pour un type binaire — D3), les appels entrants groupés par méthode
 * appelante, les appels sortants résolus vers des déclarations source.</p>
 *
 * @author jo@Dev
 */
class EcjHierarchiesTest {

    private static final String BASE = """
            package demo;

            public class Base {
                public void utilise(int x) {
                    log(x);
                }

                public void log(int valeur) {
                }

                public int fournis() {
                    return 1;
                }
            }
            """;

    private static final String CONTRAT = """
            package demo;

            public interface Contrat {
                int fournis();
            }
            """;

    private static final String ENFANT = """
            package demo;

            public class Enfant extends Base implements Contrat {
                @Override
                public int fournis() {
                    return 2;
                }
            }
            """;

    private static final String APPELANT = """
            package demo;

            public class Appelant {
                public void declenche() {
                    Base b = new Base();
                    b.utilise(3);
                    b.utilise(4);
                }
            }
            """;

    @TempDir
    Path racine;

    private EcjCompilerEngine newEngine() throws Exception {
        ecrire("demo/Base.java", BASE);
        ecrire("demo/Contrat.java", CONTRAT);
        ecrire("demo/Enfant.java", ENFANT);
        ecrire("demo/Appelant.java", APPELANT);
        return new EcjCompilerEngine(
                List.of(racine.toFile()),
                Collections.<java.io.File>emptyList(),
                "17");
    }

    private void ecrire(String relatif, String contenu) throws Exception {
        Path fichier = racine.resolve(relatif);
        Files.createDirectories(fichier.getParent());
        Files.writeString(fichier, contenu);
    }

    // ─── hiérarchie de types ───────────────────────────────────────────

    @Test
    void elementTypeAuCaretSurUneReference() throws Exception {
        EcjNavigationEngine moteur = new EcjNavigationEngine(newEngine());
        int offset = ENFANT.indexOf("extends Base") + "extends ".length();
        EcjNavigationEngine.CibleHierarchie cible = moteur.typeHierarchieAu(
                racine.resolve("demo/Enfant.java").toAbsolutePath().toString(),
                ENFANT, offset, null);
        assertNotNull(cible, "le type référencé Base est localisé");
        assertEquals("Base", cible.nom(), "le nom simple");
        assertTrue(cible.chemin().endsWith("demo" + java.io.File.separator
                        + "Base.java"),
                "localisé dans SA source : " + cible.chemin());
        assertEquals("Base", BASE.substring(cible.nomDebut(), cible.nomFin()),
                "la plage nom vise le jeton « Base »");
        assertTrue(BASE.substring(cible.declDebut(), cible.declFin())
                        .contains("class Base"),
                "la plage déclaration couvre la classe : "
                        + BASE.substring(cible.declDebut(), cible.declFin()));
    }

    @Test
    void supertypesDirectsClasseEtInterface() throws Exception {
        EcjNavigationEngine moteur = new EcjNavigationEngine(newEngine());
        String cheminEnfant = racine.resolve("demo/Enfant.java")
                .toAbsolutePath().toString();
        int offset = ENFANT.indexOf("class Enfant") + "class ".length();
        List<EcjNavigationEngine.CibleHierarchie> sups =
                moteur.supertypesHierarchie(cheminEnfant, ENFANT, offset, null);
        assertEquals(2, sups.size(),
                "Base (superclasse) + Contrat (interface) : " + sups);
        assertTrue(sups.stream().anyMatch(s -> s.nom().equals("Base")));
        assertTrue(sups.stream().anyMatch(s -> s.nom().equals("Contrat")));
        assertTrue(sups.stream().allMatch(s -> s.chemin().contains("demo")),
                "tous localisés dans les sources du projet");
    }

    @Test
    void sousTypesViaBalayageProjet() throws Exception {
        EcjNavigationEngine moteur = new EcjNavigationEngine(newEngine());
        String cheminBase = racine.resolve("demo/Base.java")
                .toAbsolutePath().toString();
        int offset = BASE.indexOf("class Base") + "class ".length();
        List<EcjNavigationEngine.CibleHierarchie> sous =
                moteur.sousTypesHierarchie(cheminBase, BASE, offset, null);
        assertEquals(1, sous.size(), "Enfant hérite directement de Base : " + sous);
        assertEquals("Enfant", sous.get(0).nom());
        assertTrue(sous.get(0).chemin().endsWith("Enfant.java"));
    }

    @Test
    void typeBinaireAuCaretNullPasDUriFactice() throws Exception {
        EcjNavigationEngine moteur = new EcjNavigationEngine(newEngine());
        // « @Override » n'est pas un type projet ; une annotation du jrt
        // serait binaire — ici le caret est sur un mot non-type : null.
        int offset = ENFANT.indexOf("@Override") + 2;
        EcjNavigationEngine.CibleHierarchie cible = moteur.typeHierarchieAu(
                racine.resolve("demo/Enfant.java").toAbsolutePath().toString(),
                ENFANT, offset, null);
        assertNull(cible, "pas de type projet au caret → null (jamais d'URI factice)");
    }

    // ─── hiérarchie d'appels ───────────────────────────────────────────

    @Test
    void elementMethodeAuCaretSurUnAppel() throws Exception {
        EcjNavigationEngine moteur = new EcjNavigationEngine(newEngine());
        String cheminAppelant = racine.resolve("demo/Appelant.java")
                .toAbsolutePath().toString();
        int offset = APPELANT.indexOf("b.utilise(3)") + "b.".length();
        EcjNavigationEngine.CibleHierarchie cible = moteur.methodeHierarchieAu(
                cheminAppelant, APPELANT, offset, null);
        assertNotNull(cible, "la méthode appelée est résolue et localisée");
        assertEquals("utilise", cible.nom());
        assertTrue(cible.chemin().endsWith("Base.java"),
                "la déclaration vit dans Base.java : " + cible.chemin());
        assertEquals("utilise", BASE.substring(cible.nomDebut(), cible.nomFin()));
    }

    @Test
    void appelsEntrantsGroupesParAppelant() throws Exception {
        EcjNavigationEngine moteur = new EcjNavigationEngine(newEngine());
        String cheminBase = racine.resolve("demo/Base.java")
                .toAbsolutePath().toString();
        int offset = BASE.indexOf("public void utilise") + "public void ".length();
        List<EcjNavigationEngine.CibleAppel> entrants =
                moteur.appelsEntrants(cheminBase, BASE, offset, null);
        assertEquals(1, entrants.size(),
                "UN appelant (Appelant.declenche) : " + entrants);
        EcjNavigationEngine.CibleAppel appelant = entrants.get(0);
        assertEquals("declenche", appelant.nomMethode(),
                "la méthode appelante est declenche");
        assertTrue(appelant.chemin().endsWith("Appelant.java"));
        assertEquals(2, appelant.sites().size(),
                "deux sites d'appel (utilise(3), utilise(4)) : "
                        + appelant.sites());
        for (int[] site : appelant.sites()) {
            String texte = APPELANT.substring(site[0], site[1]);
            assertTrue(texte.startsWith("b.utilise"),
                    "chaque site est un appel : " + texte);
        }
    }

    @Test
    void appelsSortantsResolusVersSourcesDuProjet() throws Exception {
        EcjNavigationEngine moteur = new EcjNavigationEngine(newEngine());
        String cheminAppelant = racine.resolve("demo/Appelant.java")
                .toAbsolutePath().toString();
        int offset = APPELANT.indexOf("declenche()") + 2;
        List<EcjNavigationEngine.CibleAppel> sortants =
                moteur.appelsSortants(cheminAppelant, APPELANT, offset, null);
        // Les cibles binaires (Object.<init> etc.) sont écartées — D3.
        assertEquals(1, sortants.size(),
                "une cible source (Base.utilise) : " + sortants);
        EcjNavigationEngine.CibleAppel cible = sortants.get(0);
        assertEquals("utilise", cible.nomMethode());
        assertEquals("demo.Base", cible.classeProprietaire());
        assertEquals(2, cible.sites().size(),
                "les deux sites d'appel voyagent : " + cible.sites());
    }

    @Test
    void pasDeMethodeAuCaretListesVides() throws Exception {
        EcjNavigationEngine moteur = new EcjNavigationEngine(newEngine());
        String cheminBase = racine.resolve("demo/Base.java")
                .toAbsolutePath().toString();
        // Le caret est sur un littéral, pas sur un nom de méthode.
        int offset = BASE.indexOf("return 1;") + 2;
        List<EcjNavigationEngine.CibleAppel> entrants =
                moteur.appelsEntrants(cheminBase, BASE, offset, null);
        assertTrue(entrants.isEmpty(),
                "pas de méthode au caret → aucun appel entrant");
    }
}
