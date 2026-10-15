package jo.lspjava.ecj;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du lot S2-a : éditions de formatage de PLAGE
 * ({@link EcjFormatter#editionsRange}) — le substrat du handler LSP
 * {@code textDocument/rangeFormatting}.
 *
 * <p>Sémantique verrouillée : les éditions ne touchent QUE la plage
 * demandée (le formateur parse l'unité entière pour le contexte) ; elles
 * voyagent en ordre CROISSANT d'offset et sont disjointes ; une source
 * imparsable → null (no-op côté appelant) ; une plage vide → liste vide ;
 * une plage pleine page équivaut au formatage document intégral.</p>
 *
 * @author jo@Dev
 */
class EcjFormatterRangeTest {

    /** Source volontairement mal formatée (deux méthodes identiques). */
    private static final String SOURCE = """
            class Ex {
                void a() {
            int x=1;
                }

                void b() {
            int y=2;
                }
            }
            """;

    /** Applique les éditions comme le ferait un client LSP. */
    private static String applique(String source, List<EcjFormatter.Edition> editions) {
        String result = source;
        // Ordre décroissant — les offsets amont restent valides.
        var triees = new java.util.ArrayList<>(editions);
        triees.sort(java.util.Comparator.comparingInt(
                EcjFormatter.Edition::offset).reversed());
        for (EcjFormatter.Edition e : triees) {
            result = result.substring(0, e.offset()) + e.texte()
                    + result.substring(e.offset() + e.length());
        }
        return result;
    }

    @Test
    void editionsConfineesALaPlage() {
        EcjFormatter fmt = new EcjFormatter("17");
        int debutB = SOURCE.indexOf("void b()");
        int finB = SOURCE.indexOf('}', SOURCE.indexOf("int y=2;")) + 1;
        List<EcjFormatter.Edition> editions =
                fmt.editionsRange(SOURCE, debutB, finB - debutB);
        assertNotNull(editions, "le formateur produit des éditions");
        assertTrue(!editions.isEmpty(), "une plage non vide produit des éditions");
        for (EcjFormatter.Edition e : editions) {
            assertTrue(e.offset() >= debutB && e.offset() + e.length() <= finB,
                    "toute édition reste dans la plage demandée : " + e);
        }
        String resultat = applique(SOURCE, editions);
        // La méthode a (HORS plage) reste inchangée.
        assertTrue(resultat.contains("int x=1;"),
                "le code hors plage n'est jamais touché");
        // La méthode b (DANS la plage) est formatée.
        assertTrue(resultat.contains("int y = 2;"),
                "le code dans la plage est formaté : " + resultat);
    }

    @Test
    void editionsTrieesCroissantesEtDisjointes() {
        EcjFormatter fmt = new EcjFormatter("17");
        List<EcjFormatter.Edition> editions =
                fmt.editionsRange(SOURCE, 0, SOURCE.length());
        assertNotNull(editions);
        int precedent = -1;
        for (EcjFormatter.Edition e : editions) {
            assertTrue(e.offset() > precedent,
                    "offsets strictement croissants (éditions disjointes) : " + e);
            assertTrue(e.length() >= 0, "longueur non négative");
            assertTrue(e.offset() <= SOURCE.length(), "offset dans la source");
            precedent = e.offset();
        }
    }

    @Test
    void plageVideListeVide() {
        EcjFormatter fmt = new EcjFormatter("17");
        List<EcjFormatter.Edition> editions = fmt.editionsRange(SOURCE, 10, 0);
        assertNotNull(editions);
        assertTrue(editions.isEmpty(), "plage vide → liste vide (pas null)");
    }

    @Test
    void sourceImparsableNeMutilleJamais() {
        EcjFormatter fmt = new EcjFormatter("17");
        String cassee = "class {{{";
        List<EcjFormatter.Edition> editions =
                fmt.editionsRange(cassee, 0, cassee.length());
        // Contrat historique (EcjFormatterTest) : null OU source inchangée —
        // jamais une version mutilée.
        assertTrue(editions == null || editions.isEmpty(),
                "imparsable → null ou aucune édition (reçu : " + editions + ")");
        String viaRange = fmt.formatRange(cassee, 0, cassee.length());
        assertTrue(viaRange == null || viaRange.equals(cassee),
                "formatRange : null ou inchangé, reçu : " + viaRange);
    }

    @Test
    void pleinePageEquivautAuFormatDocument() {
        EcjFormatter fmt = new EcjFormatter("17");
        List<EcjFormatter.Edition> editions =
                fmt.editionsRange(SOURCE, 0, SOURCE.length());
        assertEquals(fmt.format(SOURCE), applique(SOURCE, editions),
                "une plage pleine page formate tout le document");
    }

    @Test
    void formatRangeResteCoherentAvecLesEditions() {
        // Le refactoring formatRange → editionsRange ne change pas le
        // comportement historique.
        EcjFormatter fmt = new EcjFormatter("17");
        int debutB = SOURCE.indexOf("void b()");
        int finB = SOURCE.indexOf('}', SOURCE.indexOf("int y=2;")) + 1;
        String viaRange = fmt.formatRange(SOURCE, debutB, finB - debutB);
        String viaEditions = applique(SOURCE,
                fmt.editionsRange(SOURCE, debutB, finB - debutB));
        assertEquals(viaRange, viaEditions);
        assertNotNull(viaRange);
        assertTrue(viaRange.contains("int x=1;"));
        assertTrue(viaRange.contains("int y = 2;"));
    }
}
