package jo.lspjava.ecj;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests unitaires pour {@link SourceFocuser} (technique de
 * java-language-server).
 *
 * <p>Valide :</p>
 * <ul>
 *   <li>Les corps hors-portée sont réduits à un bloc vide {@code {}}.</li>
 *   <li>La méthode contenant l'offset est préservée intégralement.</li>
 *   <li>Les signatures de méthode restent intactes (pour résolution de
 *       surcharges / héritage).</li>
 *   <li>Les strings et commentaires contenant des accolades ne cassent pas
 *       le bracket matcher.</li>
 * </ul>
 *
 * @author jo@Dev
 */
class SourceFocuserTest {

    @Test
    void shortSourceIsReturnedUnchanged() {
        String src = "class A { void m() { int x = 0; } }";
        assertEquals(src, SourceFocuser.focus(src, 25),
                "Les sources courtes (< 2000 chars) ne sont pas réduites");
    }

    @Test
    void methodBodiesOutsideOffsetAreErased() {
        // Une classe avec 3 méthodes longues — l'offset dans la 2e méthode.
        StringBuilder src = new StringBuilder();
        src.append("class Big {\n");
        src.append("  void m1() {\n    // commentaire\n");
        for (int i = 0; i < 200; i++) src.append("    int a").append(i).append(" = ").append(i).append(";\n");
        src.append("  }\n");
        src.append("  void m2() {\n    String target = \"here\";\n");  // <-- offset ici
        for (int i = 0; i < 200; i++) src.append("    int b").append(i).append(" = ").append(i).append(";\n");
        src.append("  }\n");
        src.append("  void m3() {\n");
        for (int i = 0; i < 200; i++) src.append("    int c").append(i).append(" = ").append(i).append(";\n");
        src.append("  }\n");
        src.append("}\n");
        String source = src.toString();
        assertTrue(source.length() > 2000);

        // Offset dans m2, après "String target =".
        int offset = source.indexOf("\"here\"");
        String reduced = SourceFocuser.focus(source, offset);

        // m2 doit rester intact — on y retrouve "here".
        assertTrue(reduced.contains("\"here\""),
                "La méthode focalisée doit être préservée : " + reduced);

        // m1 doit être réduite — on ne retrouve plus "int a150 = 150;".
        assertTrue(!reduced.contains("a150 = 150"),
                "Le corps de m1 doit être effacé : " + reduced);

        // m3 doit être réduite aussi.
        assertTrue(!reduced.contains("c150 = 150"),
                "Le corps de m3 doit être effacé : " + reduced);

        // Les signatures des 3 méthodes restent — pour la résolution de
        // surcharges et héritage.
        assertTrue(reduced.contains("void m1()"),
                "La signature de m1 doit rester : " + reduced);
        assertTrue(reduced.contains("void m2()"),
                "La signature de m2 doit rester : " + reduced);
        assertTrue(reduced.contains("void m3()"),
                "La signature de m3 doit rester : " + reduced);
    }

    @Test
    void stringsAndCommentsDoNotConfuseMatcher() {
        StringBuilder src = new StringBuilder();
        src.append("class T {\n");
        src.append("  void m1() {\n");
        src.append("    String s = \"\\\"{\\\"}\"; // un '{' dans un string\n");
        for (int i = 0; i < 300; i++) src.append("    int a").append(i).append(" = ").append(i).append(";\n");
        src.append("  }\n");
        src.append("  void m2() {\n");
        src.append("    String t = \"target\";  // <-- offset ici\n");
        for (int i = 0; i < 300; i++) src.append("    int b").append(i).append(" = ").append(i).append(";\n");
        src.append("  }\n");
        src.append("}\n");
        String source = src.toString();
        assertTrue(source.length() > 2000);

        int offset = source.indexOf("\"target\"");
        String reduced = SourceFocuser.focus(source, offset);

        // m2 préserve "target".
        assertTrue(reduced.contains("\"target\""),
                "m2 doit être préservée : " + reduced);
        // m1 réduite — on perd "a150".
        assertTrue(!reduced.contains("a150 = 150"),
                "m1 doit être effacée malgré le '{' dans son string : " + reduced);
    }

    @Test
    void offsetOutsideMethodDoesNotReduce() {
        // Si l'offset est hors d'un corps de méthode (au niveau classe,
        // sur un champ), le focuser doit rendre la source inchangée.
        StringBuilder src = new StringBuilder();
        src.append("class T {\n");
        src.append("  int x = 0;  // <-- offset sur ce champ\n");
        src.append("  void m1() {\n");
        for (int i = 0; i < 400; i++) src.append("    int a").append(i).append(" = ").append(i).append(";\n");
        src.append("  }\n");
        src.append("}\n");
        String source = src.toString();
        int offset = source.indexOf("int x");
        String reduced = SourceFocuser.focus(source, offset);
        // Pas de réduction — l'offset est hors d'une méthode.
        assertEquals(source, reduced,
                "Hors d'un corps de méthode, la source doit rester inchangée");
    }

    // ── mapBack (traduction réduite → originale) ────────────

    /** Fixture : classe > 2000 chars avec 3 méthodes, l'offset dans m2. */
    private static String bigSource() {
        StringBuilder src = new StringBuilder();
        src.append("class Big {\n");
        src.append("  void m1() {\n");
        for (int i = 0; i < 200; i++) src.append("    int a").append(i).append(" = ").append(i).append(";\n");
        src.append("  }\n");
        src.append("  void m2() {\n    String target = \"here\";\n");
        for (int i = 0; i < 200; i++) src.append("    int b").append(i).append(" = ").append(i).append(";\n");
        src.append("  }\n");
        src.append("  void m3() {\n");
        for (int i = 0; i < 200; i++) src.append("    int c").append(i).append(" = ").append(i).append(";\n");
        src.append("  }\n");
        src.append("}\n");
        return src.toString();
    }

    @Test
    void mapBackUnreducedSourceIsIdentity() {
        String src = "class A { void m() { int x = 0; } }";
        SourceFocuser.Focused f = SourceFocuser.focusAndMap(src, 25);
        assertEquals(10, f.mapBack(10),
                "Sans réduction, mapBack est l'identité");
        assertEquals(src.length(), f.mapBack(src.length()),
                "Sans réduction, mapBack en fin de source est l'identité");
    }

    @Test
    void mapBackTranslatesPositionsAfterErasedBodies() {
        String source = bigSource();
        int offset = source.indexOf("\"here\"");
        SourceFocuser.Focused f = SourceFocuser.focusAndMap(source, offset);
        assertTrue(f.source().length() < source.length(),
                "La fixture doit être réduite");

        // Positions AVANT la première insertion (dans m1, avant son corps
        // effacé) → identité.
        int m1Sig = f.source().indexOf("void m1()");
        assertEquals(m1Sig, f.mapBack(m1Sig),
                "Avant toute insertion, mapBack est l'identité");

        // La position du nom m3 (APRÈS les corps effacés de m1 et m2… non :
        // m2 est la méthode focalisée, seule m1 est effacée AVANT m3)
        // → doit revenir à la position ORIGINALE de "m3" dans la source.
        int m3Reduced = f.source().indexOf("void m3()");
        int m3Original = source.indexOf("void m3()");
        assertTrue(m3Reduced < m3Original,
                "m3 doit être décalé vers la gauche dans la source réduite");
        assertEquals(m3Original, f.mapBack(m3Reduced),
                "mapBack doit restituer la position originale de m3");

        // La fin de la source réduite doit mapper sur la fin originale.
        assertEquals(source.length(), f.mapBack(f.source().length()),
                "La fin de la source réduite correspond à la fin originale");
    }

    @Test
    void mapBackInsideInsertedTokenMapsToResumePosition() {
        String source = bigSource();
        int offset = source.indexOf("\"here\"");
        SourceFocuser.Focused f = SourceFocuser.focusAndMap(source, offset);

        // Le corps de m1 est remplacé par « {} » : une position DANS ce
        // jeton (le « } ») doit mapper sur la position de reprise — juste
        // après le corps original de m1 (le « } » de m1 + 1).
        int m1Open = source.indexOf("void m1()");
        int m1BodyOpen = source.indexOf('{', m1Open);
        // Trouve le « {} » inséré dans la source réduite (après la
        // signature de m1) et tape sur le 2e char.
        int m1ReducedSig = f.source().indexOf("void m1()");
        int insertedClose = f.source().indexOf("{}", m1ReducedSig) + 1;
        int m1BodyClose = source.indexOf('}', m1BodyOpen);
        assertEquals(m1BodyClose + 1, f.mapBack(insertedClose),
                "Une position dans le jeton inséré mappe sur la reprise");
    }

    @Test
    void mapBackRoundTripsTheCaretOffset() {
        String source = bigSource();
        int offset = source.indexOf("\"here\"");
        SourceFocuser.Focused f = SourceFocuser.focusAndMap(source, offset);
        assertEquals(offset, f.mapBack(f.offset()),
                "mapBack(offset()) restitue l'offset original (aller-retour)");
    }
}
