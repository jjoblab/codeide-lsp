package jo.lspjava.ecj;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * v1.9.1 — Cache négatif de présence source : la résolution ecj appelle
 * {@code find(fqcn)} des CENTAINES de fois par passe (toute la hiérarchie
 * View par exemple) ; chaque appelsondait AVANT TOUT le disque
 * ({@code Files.isRegularFile} par racine source). Sur le stockage FUSE
 * d'Android c'était la cause dominante de la lenteur de complétion
 * rapportée (0,5–5 ms par stat × des centaines d'appels × 3–5 splices).
 *
 * <p>Le cache négatif mémorise « ce FQCN n'est PAS une source du projet »
 * pendant 30 s ; la CRÉATION d'un fichier .java via l'événement de
 * surveillance ({@code didChangeWatchedFiles} Created) invalide
 * immédiatement ({@code invalidateSourcePresence}).</p>
 */
class CachePresenceSourceTest {

    @TempDir
    static Path tmp;

    static EcjCompilerEngine engine;
    static Path src;

    @BeforeAll
    static void setUp() throws Exception {
        File androidJar = findAndroidJar();
        Assumptions.assumeTrue(androidJar != null, "android.jar absent");

        // Racine source = src/main/java ; les paquets vivent EN DESSOUS.
        src = Files.createDirectories(
                tmp.resolve("app/src/main/java/com/example/c"));
        List<File> roots = new ArrayList<>();
        roots.add(tmp.resolve("app/src/main/java").toFile());
        engine = new EcjCompilerEngine(roots, List.of(androidJar), "17");
    }

    @Test
    void classeCreeeApresNegatifDevientVisibleApresInvalidation() throws Exception {
        // 1. Résout un FQCN absent — le négatif est mémorisé.
        String fqcn = "com.example.c.Nouvelle";
        org.junit.jupiter.api.Assertions.assertNull(
                engine.typeIndex().find(fqcn),
                "le FQCN absent ne doit rien résoudre");

        // 2. Le fichier est créé SUR DISK (nouvelle classe hors éditeur).
        Files.writeString(src.resolve("Nouvelle.java"), """
                package com.example.c;
                public class Nouvelle { public int un() { return 1; } }
                """, StandardCharsets.UTF_8);

        // 3. SANS invalidation : le négatif TTL masque encore le fichier
        //    (le COMPORTEMENT du cache — c'est la fenêtre documentée).
        org.junit.jupiter.api.Assertions.assertNull(
                engine.typeIndex().find(fqcn),
                "le négatif TTL doit masquer la création dans sa fenêtre");

        // 4. L'événement de surveillance (Created) invalide — la classe
        //    résoudre immédiatement, sans attendre l'expiration du TTL.
        engine.invalidateSourcePresence();
        org.junit.jupiter.api.Assertions.assertNotNull(
                engine.typeIndex().find(fqcn),
                "après invalidation la nouvelle source doit résoudre");
    }

    @Test
    void sourceExistanteResteToujoursLisible() throws Exception {
        Files.writeString(src.resolve("Stable.java"), """
                package com.example.c;
                public class Stable { }
                """, StandardCharsets.UTF_8);
        // Plusieurs lectures successives — le contenu suit le disque
        // (positifs jamais cachés).
        for (int i = 0; i < 3; i++) {
            org.junit.jupiter.api.Assertions.assertNotNull(
                    engine.typeIndex().find("com.example.c.Stable"),
                    "une source existante résout à chaque appel");
        }
    }

    private static File findAndroidJar() {
        String[] candidates = {
                "/home/z/android-sdk/platforms/android-37.2/android.jar",
                "/home/z/tools/android-sdk/platforms/android-37.2/android.jar",
        };
        for (String c : candidates) {
            File f = new File(c);
            if (f.isFile()) return f;
        }
        return null;
    }
}
