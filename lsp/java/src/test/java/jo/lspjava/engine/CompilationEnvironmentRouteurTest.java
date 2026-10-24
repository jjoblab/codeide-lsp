package jo.lspjava.engine;

import jo.lspjava.ecj.EcjCompilerEngine;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests du ROUTAGE fichier → module (ADR 0215 — découverte D6 du
 * rapport d'audit §14) : le serveur LSP Java doit analyser un fichier
 * avec le moteur de SON module, pas avec le contexte défaut.
 *
 * <p>Sans le routage, un projet Android de deux fichiers dans le MÊME
 * paquet (`MainActivity.java` + `Greeter.java`) montrait « Greeter
 * cannot be resolved to a type » : l'orchestrateur nourrissait bien
 * les moteurs PAR MODULE (racines sources + jars du modèle), mais
 * TOUS les services LSP consultaient le contexte DÉFAUT — android.jar
 * seul, sans racines sources. {@code AssociationFichier} (modèle)
 * faisait déjà l'association ; il n'était branché nulle part.</p>
 *
 * @author jo@Dev
 */
class CompilationEnvironmentRouteurTest {

    /** Un module factice (« :app »). */
    private static final String APP = ":app";

    /** Un autre module (« :lib »). */
    private static final String LIB = ":lib";

    @Test
    void sansRouteurComportementHistorique() {
        CompilationEnvironment env = new CompilationEnvironment();
        EcjCompilerEngine pourFichier = env.jdtPourFichier("/quelconque/App.java");
        assertSame(env.jdt(), pourFichier,
                "sans routeur posé, tout fichier route vers le contexte défaut");
    }

    @Test
    void routeurAssocieLeMoteurDuModule() {
        CompilationEnvironment env = new CompilationEnvironment();
        env.setRouteurModules(chemin -> chemin.endsWith("App.java") ? APP : null);
        EcjCompilerEngine moteurApp = env.jdtPourFichier("/projet/src/App.java");
        assertSame(env.jdt(APP), moteurApp,
                "jdtPourFichier doit retourner LE moteur du module routé");
        assertNotSame(env.jdt(), moteurApp,
                "le moteur du module est distinct du contexte défaut");
        // Un chemin non associé (routeur → null) revient au défaut.
        assertSame(env.jdt(), env.jdtPourFichier("/projet/src/Autre.java"),
                "routeur null sur CE fichier = contexte défaut");
    }

    @Test
    void routeurVuAThaqueRequeteSansRePose() {
        CompilationEnvironment env = new CompilationEnvironment();
        // Le routeur lit un ÉTAT qui change ENTRE deux requêtes — miroir
        // du dépôt de modèle : sync suivante = nouveau module routé,
        // SANS re-pose du routeur.
        final String[] moduleCourant = {null};
        env.setRouteurModules(chemin -> moduleCourant[0]);
        assertSame(env.jdt(), env.jdtPourFichier("/p/A.java"));
        moduleCourant[0] = APP;
        assertSame(env.jdt(APP), env.jdtPourFichier("/p/A.java"),
                "le routage suit l'instantané COURANT du modèle");
    }

    @Test
    void routeurQuiJetteRepliSurDefaut() {
        CompilationEnvironment env = new CompilationEnvironment();
        env.setRouteurModules(chemin -> {
            throw new IllegalStateException("dépôt illisible");
        });
        assertSame(env.jdt(), env.jdtPourFichier("/p/A.java"),
                "un routeur fautif ne casse JAMAIS une requête — repli défaut");
    }

    @Test
    void fichiersDeModulesDistinctsMoteursDistincts() {
        CompilationEnvironment env = new CompilationEnvironment();
        env.setRouteurModules(chemin -> chemin.contains("/lib/") ? LIB : APP);
        EcjCompilerEngine moteurApp = env.jdtPourFichier("/projet/app/src/App.java");
        EcjCompilerEngine moteurLib = env.jdtPourFichier("/projet/lib/src/Lib.java");
        assertNotSame(moteurApp, moteurLib,
                ":app et :lib vivent sur des moteurs isolés (lot E)");
        assertSame(moteurApp, env.jdt(APP));
        assertSame(moteurLib, env.jdt(LIB));
    }

    @Test
    void gardeParesseuseParModuleSansConstruction() {
        CompilationEnvironment env = new CompilationEnvironment();
        env.setRouteurModules(chemin -> APP);
        String cheminApp = "/p/App.java";
        assertFalse(env.isEcjInitialisePour(cheminApp),
                "avant toute requête, le moteur du module n'existe pas");
        env.jdtPourFichier(cheminApp);
        assertTrue(env.isEcjInitialisePour(cheminApp),
                "après jdtPourFichier, le moteur du module est construit");
        assertFalse(env.isEcjInitialized(),
                "le contexte DÉFAUT reste non construit (jamais sollicité)");
        // La garde est la même que la variante qualifiée.
        assertTrue(env.isEcjInitialized(APP));
    }

    @Test
    void moteursVivantsDefautPuisModules() {
        CompilationEnvironment env = new CompilationEnvironment();
        env.setRouteurModules(chemin -> chemin.endsWith("Lib.java") ? LIB : APP);
        assertTrue(env.moteursVivants().isEmpty(),
                "rien n'est construit : aucun moteur vivant");
        env.jdt(); // défaut d'abord
        env.jdtPourFichier("/p/Lib.java"); // :lib ensuite
        List<EcjCompilerEngine> vivants = env.moteursVivants();
        assertEquals(2, vivants.size(), "défaut + :lib");
        assertSame(env.jdt(), vivants.get(0), "le défaut vient en PREMIER");
        assertSame(env.jdt(LIB), vivants.get(1), "puis les modules");
    }

    @Test
    void remplacementDeContexteInvalideLeMoteurDuSeulModule() throws Exception {
        CompilationEnvironment env = new CompilationEnvironment();
        env.setRouteurModules(chemin -> chemin.endsWith("Lib.java") ? LIB : APP);
        java.io.File racine =
                new java.io.File(System.getProperty("java.io.tmpdir"),
                        "routeur-test-" + System.nanoTime());
        assertTrue(racine.mkdirs(), "racine de test doit être créée");
        java.io.File appSrc = new File(racine, "app-src");
        java.io.File libSrc = new File(racine, "lib-src");
        java.io.File appSrc2 = new File(racine, "app-src-2");
        assertTrue(appSrc.mkdirs() && libSrc.mkdirs() && appSrc2.mkdirs(),
                "racines sources de test doivent exister (garde isDirectory)");
        try {
            env.remplacerContexteModule(APP, List.of(appSrc), List.of());
            env.remplacerContexteModule(LIB, List.of(libSrc), List.of());
            EcjCompilerEngine moteurApp = env.jdtPourFichier("/p/App.java");
            EcjCompilerEngine moteurLib = env.jdtPourFichier("/p/Lib.java");
            // Un remplacement EFFECTIF de :app invalide SON moteur, PAS
            // celui de :lib (sémantiques lot E, désormais via le routage).
            env.remplacerContexteModule(APP, List.of(appSrc2), List.of());
            assertNotSame(moteurApp, env.jdtPourFichier("/p/App.java"),
                    "le moteur de :app est reconstruit après son remplacement");
            assertSame(moteurLib, env.jdtPourFichier("/p/Lib.java"),
                    "le moteur de :lib VIT — jamais invalidé par la sync de :app");
        } finally {
            // Best-effort — le dossier de test vit sous tmpdir.
            appSrc.delete();
            libSrc.delete();
            appSrc2.delete();
            racine.delete();
        }
    }
}
