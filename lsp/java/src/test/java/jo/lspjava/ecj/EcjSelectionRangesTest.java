package jo.lspjava.ecj;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du lot S2-b (moteur) : la chaîne de sélection
 * ({@code selectionRange}) et la cible de formatage à la frappe
 * ({@code onTypeFormatting}).
 *
 * <p>La chaîne doit être STRICTEMENT croissante en longueur (chaque niveau
 * contient strictement le précédent — contracte LSP 3.17 pour la chaîne
 * {@code parent}) et couvrir l'escalade complète : jeton nom → expression →
 * instruction → bloc → déclaration → type → unité. La cible de formatage
 * doit tomber sur la construction qui SE TERMINE au caractère tapé.</p>
 *
 * @author jo@Dev
 */
class EcjSelectionRangesTest {

    private static final String SOURCE = """
            package demo;

            public class Main {
                private int total = 0;

                public int somme(int a, int b) {
                    return a + b;
                }

                public int malIndente() {
                int y=2;
                return y;
                }
            }
            """;

    private EcjCompilerEngine newEngine() {
        return new EcjCompilerEngine(
                Collections.<java.io.File>emptyList(),
                Collections.<java.io.File>emptyList(),
                "17");
    }

    // ─── chaîne de sélection ───────────────────────────────────────────

    @Test
    void chaineStrictementCroissanteDepuisUneExpression() {
        EcjSelectionRanges moteur = new EcjSelectionRanges(newEngine());
        int offset = SOURCE.indexOf("a + b") + 4; // au milieu de « + »
        List<EcjSelectionRanges.Plage> chaine =
                moteur.chaineSelection("/demo/Main.java", SOURCE, offset);

        assertFalse(chaine.isEmpty(), "une expression vivante a une chaîne");
        // Croissance stricte : chaque plage contient la précédente.
        for (int i = 1; i < chaine.size(); i++) {
            EcjSelectionRanges.Plage inner = chaine.get(i - 1);
            EcjSelectionRanges.Plage outer = chaine.get(i);
            assertTrue(outer.fin() - outer.debut() > inner.fin() - inner.debut(),
                    "niveau " + i + " STRICTEMENT plus long : " + inner
                            + " → " + outer);
            assertTrue(outer.debut() <= inner.debut()
                            && inner.fin() <= outer.fin(),
                    "niveau " + i + " contient le précédent : " + inner
                            + " → " + outer);
        }
        // Le sommet : l'unité entière.
        EcjSelectionRanges.Plage sommet = chaine.get(chaine.size() - 1);
        assertEquals(0, sommet.debut(), "le sommet démarre à 0");
        assertEquals(SOURCE.length(), sommet.fin(), "le sommet couvre tout");
    }

    @Test
    void escaladeCompleteJusquaLaDeclaration() {
        EcjSelectionRanges moteur = new EcjSelectionRanges(newEngine());
        int offset = SOURCE.indexOf("a + b");
        List<EcjSelectionRanges.Plage> chaine =
                moteur.chaineSelection("/demo/Main.java", SOURCE, offset);
        assertFalse(chaine.isEmpty());

        // L'escalade passe par : le return, le corps de somme, la
        // déclaration de somme, la classe Main.
        boolean voitReturn = chaine.stream().anyMatch(p ->
                fragment(p).strip().startsWith("return"));
        boolean voitSomme = chaine.stream().anyMatch(p ->
                fragment(p).contains("somme"));
        boolean voitClasse = chaine.stream().anyMatch(p ->
                fragment(p).contains("class Main"));
        assertTrue(voitReturn, "l'instruction return est un niveau : " + chaine);
        assertTrue(voitSomme, "la méthode somme est un niveau : " + chaine);
        assertTrue(voitClasse, "la classe Main est un niveau : " + chaine);
    }

    /** Le texte d'une plage (borné à la source). */
    private static String fragment(EcjSelectionRanges.Plage p) {
        return SOURCE.substring(p.debut(), Math.min(p.fin(), SOURCE.length()));
    }

    @Test
    void jetonNomDuChampEtDeclarationComplete() {
        EcjSelectionRanges moteur = new EcjSelectionRanges(newEngine());
        int offset = SOURCE.indexOf("total = 0") + 2; // sur « total » du champ
        List<EcjSelectionRanges.Plage> chaine =
                moteur.chaineSelection("/demo/Main.java", SOURCE, offset);
        assertFalse(chaine.isEmpty(), "le nom d'un champ a une chaîne");
        // Le niveau le plus intérieur : le jeton « total » seul.
        EcjSelectionRanges.Plage jeton = chaine.get(0);
        assertEquals("total", SOURCE.substring(jeton.debut(), jeton.fin()),
                "le premier niveau est le jeton nom du champ");
        // Le niveau suivant : la déclaration complète du champ.
        EcjSelectionRanges.Plage declaration = chaine.get(1);
        assertTrue(SOURCE.substring(declaration.debut(), declaration.fin())
                        .contains("private int total"),
                "le second niveau est la déclaration complète du champ : "
                        + SOURCE.substring(declaration.debut(), declaration.fin()));
    }

    @Test
    void offsetHorsBornesOuSourceVideChaineVide() {
        EcjSelectionRanges moteur = new EcjSelectionRanges(newEngine());
        assertTrue(moteur.chaineSelection("/demo/Main.java", SOURCE, -1).isEmpty(),
                "offset négatif → chaîne vide");
        assertTrue(moteur.chaineSelection("/demo/Main.java", SOURCE,
                SOURCE.length()).isEmpty(), "offset fin de source → chaîne vide");
        assertTrue(moteur.chaineSelection("/demo/Main.java", "", 0).isEmpty(),
                "source vide → chaîne vide");
    }

    @Test
    void sourceImparsableNeProvoquePasDException() {
        EcjSelectionRanges moteur = new EcjSelectionRanges(newEngine());
        // ecj est TOLÉRANT AUX FAUTES : « class {{{{ » produit quand même
        // une unité (partielle). Le contract : aucune exception, et toutes
        // les plages restent DANS les bornes de la source.
        List<EcjSelectionRanges.Plage> chaine = moteur.chaineSelection(
                "/demo/Casse.java", "class {{{{", 3);
        for (EcjSelectionRanges.Plage p : chaine) {
            assertTrue(p.debut() >= 0 && p.fin() <= 11 && p.debut() < p.fin(),
                    "plage dans les bornes de la source : " + p);
        }
    }

    // ─── cible de formatage à la frappe ────────────────────────────────

    @Test
    void accoladeFermanteCibleLeBlocDeLaMethode() {
        EcjSelectionRanges moteur = new EcjSelectionRanges(newEngine());
        // Le « } » qui ferme malIndente — le dernier de la source moins la
        // classe : index du « } » précédant le « } » final.
        int finMethode = SOURCE.lastIndexOf('}');
        int finBloc = SOURCE.lastIndexOf('}', finMethode - 1);
        EcjSelectionRanges.Plage cible = moteur.plageFormattable(
                "/demo/Main.java", SOURCE, finBloc, '}');
        assertNotNull(cible, "le } d'une méthode a une cible");
        String texte = SOURCE.substring(cible.debut(), cible.fin());
        assertTrue(texte.contains("int y=2;"),
                "la cible contient le corps mal indenté : " + texte);
        assertTrue(texte.contains("malIndente"),
                "la cible couvre la méthode malIndente : " + texte);
    }

    @Test
    void pointVirguleCibleLinstruction() {
        EcjSelectionRanges moteur = new EcjSelectionRanges(newEngine());
        int semicolon = SOURCE.indexOf("int y=2;") + "int y=2;".length() - 1;
        EcjSelectionRanges.Plage cible = moteur.plageFormattable(
                "/demo/Main.java", SOURCE, semicolon, ';');
        assertNotNull(cible, "le ; d'une instruction a une cible");
        String texte = SOURCE.substring(cible.debut(), cible.fin());
        assertTrue(texte.contains("int y=2;"),
                "la cible est l'instruction : " + texte);
        assertFalse(texte.contains("return"),
                "la cible ne déborde pas sur l'instruction suivante : " + texte);
    }

    @Test
    void caractereInattenduAucuneCible() {
        EcjSelectionRanges moteur = new EcjSelectionRanges(newEngine());
        int semicolon = SOURCE.indexOf("int y=2;") + "int y=2;".length() - 1;
        // Le caractère à l'offset n'est pas le déclencheur demandé → null.
        assertNull(moteur.plageFormattable("/demo/Main.java", SOURCE,
                        semicolon, '}'),
                "un ';' à l'offset ne formate pas via '}'");
        assertNull(moteur.plageFormattable("/demo/Main.java", SOURCE,
                        semicolon, 'x'),
                "un déclencheur hors liste (x) est refusé");
    }
}
