package jo.lspjava.ecj;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du lot S2-a : extraction du javadoc par le moteur (survol riche) +
 * nettoyeur {@link EcjJavadocMarkdown}.
 *
 * <p>Le hover RICHE porte la signature du symbole au caret PLUS la javadoc
 * de sa déclaration quand elle vit dans la MÊME unité : une référence vue
 * avant (ou après) sa déclaration retrouve la documentation via le registre
 * des déclarations du scan. Les symboles binaires (classpath, jrt) n'ont
 * pas de javadoc ici — documentation nulle, signature seule (périmètre
 * honnête).</p>
 *
 * @author jo@Dev
 */
class EcjHoverJavadocTest {

    private static final String SOURCE = """
            package demo;

            /**
             * Un compteur d'essais.
             */
            public class Main {
                /** Le total courant. */
                private int total = 0;

                /**
                 * Additionne deux valeurs.
                 * <p>
                 * Les deux arguments doivent être finis.
                 *
                 * @param a la première valeur
                 * @param b la seconde valeur
                 * @return la somme de {@code a} et {@code b}
                 */
                public int somme(int a, int b) {
                    return a + b;
                }

                public int doubleTotal() {
                    return somme(total, total);
                }

                public int pasDeDoc(int x) {
                    return x;
                }
            }
            """;

    private EcjCompilerEngine newEngine() {
        return new EcjCompilerEngine(
                Collections.<java.io.File>emptyList(),
                Collections.<java.io.File>emptyList(),
                "17");
    }

    // ─── Survol riche (moteur) ─────────────────────────────────────────

    @Test
    void referenceMethodeAvantArriereRetrouveLeJavadoc() {
        EcjCompilerEngine engine = newEngine();
        // Référence à somme DANS un autre corps (déclaration ailleurs) —
        // le registre des déclarations est balayé APRÈS la traversée :
        // l'ordre fichier est indifférent.
        int offset = SOURCE.indexOf("somme(total");
        EcjCompilerEngine.SurvolRiche riche =
                engine.hoverRicheAt("/demo/Main.java", SOURCE, offset);
        assertNotNull(riche, "survol riche attendu sur l'appel somme(...)");
        assertTrue(riche.signature().contains("somme"),
                "la signature nomme la méthode : " + riche.signature());
        assertNotNull(riche.documentation(), "le javadoc de somme est attaché");
        assertTrue(riche.documentation().contains("Additionne deux valeurs"),
                "la première ligne du javadoc est là : " + riche.documentation());
        // Balises inline converties : {@code a} → `a`.
        assertTrue(riche.documentation().contains("`a` et `b`"),
                "{@code} converti en code span : " + riche.documentation());
        // Balises de bloc conservées telles quelles (portée bornée).
        assertTrue(riche.documentation().contains("@param a la première valeur"),
                "les lignes @param passent verbatim : " + riche.documentation());
        // <p> devient un saut de paragraphe markdown (ligne vide).
        assertTrue(riche.documentation().contains("valeurs.\n\nLes deux"),
                "<p> devient un paragraphe : " + riche.documentation());
    }

    @Test
    void referenceChampRetrouveSonJavadoc() {
        EcjCompilerEngine engine = newEngine();
        int offset = SOURCE.indexOf("total, total");
        EcjCompilerEngine.SurvolRiche riche =
                engine.hoverRicheAt("/demo/Main.java", SOURCE, offset);
        assertNotNull(riche, "survol riche attendu sur total");
        assertTrue(riche.signature().contains("total"),
                "la signature nomme le champ : " + riche.signature());
        assertNotNull(riche.documentation(), "le javadoc du champ est attaché");
        assertTrue(riche.documentation().contains("Le total courant"),
                "le contenu du javadoc du champ : " + riche.documentation());
    }

    @Test
    void declarationClasseRetrouveSonJavadoc() {
        EcjCompilerEngine engine = newEngine();
        int offset = SOURCE.indexOf("class Main") + "class ".length();
        EcjCompilerEngine.SurvolRiche riche =
                engine.hoverRicheAt("/demo/Main.java", SOURCE, offset);
        assertNotNull(riche, "survol riche attendu sur Main");
        assertTrue(riche.signature().contains("Main"),
                "la signature nomme le type : " + riche.signature());
        assertNotNull(riche.documentation(), "le javadoc du type est attaché");
        assertTrue(riche.documentation().contains("Un compteur d'essais"),
                "le contenu du javadoc du type : " + riche.documentation());
    }

    @Test
    void methodeSansJavadocDocumentationNulle() {
        EcjCompilerEngine engine = newEngine();
        int offset = SOURCE.indexOf("pasDeDoc");
        EcjCompilerEngine.SurvolRiche riche =
                engine.hoverRicheAt("/demo/Main.java", SOURCE, offset);
        assertNotNull(riche, "la signature reste servie");
        assertNotNull(riche.signature());
        assertNull(riche.documentation(),
                "sans javadoc → documentation nulle (survol signature seule)");
    }

    @Test
    void referenceBinaireSansDocumentation() {
        EcjCompilerEngine engine = newEngine();
        String source = """
                package demo;

                class Bin {
                    int n() {
                        return "abc".length();
                    }
                }
                """;
        int offset = source.indexOf("length");
        EcjCompilerEngine.SurvolRiche riche =
                engine.hoverRicheAt("/demo/Bin.java", source, offset);
        assertNotNull(riche, "survol attendu sur length()");
        assertTrue(riche.signature().contains("length"),
                "la signature nomme la méthode binaire : " + riche.signature());
        assertNull(riche.documentation(),
                "symbole binaire → pas de javadoc ici (périmètre même fichier)");
    }

    @Test
    void hoverAtResteSousCloturesSansDocumentation() {
        // Compat : hoverAt garde EXACTEMENT son contrat historique — clôtures
        // markdown autour de la signature, jamais de documentation.
        EcjCompilerEngine engine = newEngine();
        int offset = SOURCE.indexOf("somme(total");
        String hover = engine.hoverAt("/demo/Main.java", SOURCE, offset);
        assertNotNull(hover);
        assertTrue(hover.startsWith("```java\n"), "clôture ouvrante : " + hover);
        assertTrue(hover.endsWith("\n```"), "clôture fermante : " + hover);
        assertFalse(hover.contains("Additionne"),
                "hoverAt ne porte PAS la documentation : " + hover);
    }

    // ─── Nettoyeur javadoc (unitaire) ──────────────────────────────────

    @Test
    void nettoyeurCasLimites() {
        assertNull(EcjJavadocMarkdown.nettoyer(null));
        assertNull(EcjJavadocMarkdown.nettoyer("   "));
        assertNull(EcjJavadocMarkdown.nettoyer("/***/"),
                "commentaire vide → null (survol signature seule)");
        assertEquals("x", EcjJavadocMarkdown.nettoyer("/**x*/"));
        assertEquals("x", EcjJavadocMarkdown.nettoyer("/** x */"));
    }

    @Test
    void nettoyeurAsterisquesDeMarge() {
        String brut = "/**\n"
                + " * Ligne une.\n"
                + " * Ligne deux.\n"
                + " */";
        assertEquals("Ligne une.\nLigne deux.", EcjJavadocMarkdown.nettoyer(brut));
    }

    @Test
    void nettoyeurBalisesInline() {
        assertEquals("Voir `Foo`.",
                EcjJavadocMarkdown.nettoyer("/** Voir {@code Foo}. */"));
        assertEquals("Voir `Foo.bar()`.",
                EcjJavadocMarkdown.nettoyer("/** Voir {@link Foo#bar()}. */"));
        assertEquals("Voir `Foo`.",
                EcjJavadocMarkdown.nettoyer("/** Voir {@linkplain Foo}. */"));
        assertEquals("abc", EcjJavadocMarkdown.nettoyer("/** {@literal abc} */"));
        assertEquals("A\n\nB", EcjJavadocMarkdown.nettoyer("/** A<p>B */"));
        assertEquals("A\nB", EcjJavadocMarkdown.nettoyer("/** A<br>B */"));
        assertEquals("A\nB", EcjJavadocMarkdown.nettoyer("/** A<br/>B */"));
    }
}
