package jo.lspjava.ecj;

import jo.codeide.lsp.index.Conteneur;
import jo.codeide.lsp.index.NomSimpleTypes;
import jo.codeide.lsp.index.PhaseIndex;
import jo.codeide.lsp.index.ServiceIndexDisque;
import jo.codeide.lsp.index.TypesIndexKt;
import jo.codeide.lsp.index.VueIndex;
import jo.codeide.lsp.model.ClasspathInstantane;
import jo.codeide.lsp.model.EntreeClasspathInstantane;
import jo.codeide.lsp.model.KindEntree;
import jo.lspjava.api.IndexNoms;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.JobKt;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Adaptateur de TEST de la SPI {@link IndexNoms} sur l'index disque réel
 * ({@code :lsp:index}, Kotlin) — étape 3 lot C.
 *
 * <p>Rôle : prouver le BRANCHEMENT réel (le moteur Java consomme
 * l'index Kotlin à travers la SPI) dans la suite d'intégration
 * {@code :lsp:testing} (v0.96.3 — module de test AU-DESSUS du moteur
 * et de l'index, pattern {@code :tooling:testing} : le cœur vendored
 * ne porte aucune dépendance interne, test compris). L'adaptateur de
 * PRODUCTION vivra dans {@code :lsp:host} (étape 4) : il traduira le
 * {@code ClasspathInstantane} du modèle (entrées MODULE résolues en
 * sources du frère, AAR déjà normalisés v8) et branchera la vue sur
 * le répertoire applicatif.</p>
 *
 * <p>La clé de segment est l'empreinte W6 du modèle : cet adaptateur de
 * test dérive une clé STABLE par jar (contenu identique = clé
 * identique — pour un jar de test réécrit entre deux exécutions, la
 * clé change et le segment se reconstruit, ce qui est le comportement
 * attendu de l'index).</p>
 *
 * @author jo@Dev
 */
final class AdaptateurIndexNoms implements IndexNoms {

    private static final Logger LOG =
            Logger.getLogger("AdaptateurIndexNoms");

    private final VueIndex vue;
    private final Job racine;
    private final Job construction;

    /** Observateurs P7/P8 — notifiés depuis le fil surveillant. */
    private final List<Runnable> observateurs = new CopyOnWriteArrayList<>();

    /** Fil qui surveille la transition CONSTRUCTION → PRÊT. */
    private final Thread surveillant;

    private volatile boolean pret;

    private AdaptateurIndexNoms(VueIndex vue, Job racine,
                                Job construction) {
        this.vue = vue;
        this.racine = racine;
        this.construction = construction;
        this.surveillant = new Thread(this::surveiller, "adaptateur-index-noms");
        this.surveillant.setDaemon(true);
        this.surveillant.start();
    }

    /**
     * Construit l'index des JARS donnés (séquentiel, stockage temporaire)
     * et rend l'adaptateur IMMÉDIATEMENT — la construction vit en
     * arrière-plan, {@link #estPret()} passe à true à la transition.
     */
    static AdaptateurIndexNoms enConstruction(Path stockage,
                                               List<Path> jars) {
        // Racine des coroutines de test — annulable depuis fermer().
        Job racine = JobKt.Job(null);
        CoroutineScope scope = CoroutineScopeKtHelper.pourTests(racine);
        ServiceIndexDisque service = new ServiceIndexDisque(
                stockage.toFile(), scope, Dispatchers.getIO(),
                1, 32L * 1024 * 1024);
        List<EntreeClasspathInstantane> entrees = new ArrayList<>(jars.size());
        for (Path jar : jars) {
            File f = jar.toFile();
            entrees.add(new EntreeClasspathInstantane(
                    KindEntree.JAR, jar.toString(), "compile", null, null,
                    cleTest(jar), f.isFile(), f.length(),
                    f.lastModified()));
        }
        ClasspathInstantane classpath =
                new ClasspathInstantane(entrees, "classpath-test-lot-c");
        VueIndex vue = service.vuePour(classpath);
        Job job = service.construire(vue);
        return new AdaptateurIndexNoms(vue, racine, job);
    }

    /** Clé de segment stable par jar de test (empreinte du contenu). */
    private static String cleTest(Path jar) {
        try {
            byte[] octets = Files.readAllBytes(jar);
            java.security.MessageDigest sha =
                    java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder("test:");
            for (byte b : sha.digest(octets)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Throwable t) {
            // Jar illisible : clé dégénérée par chemin (jamais construit).
            return "test:chemin:" + jar;
        }
    }

    /** Attend la fin de la construction (PRÊT ou PARTIEL/ERREUR). */
    void attendreFin(long delaiMs) throws InterruptedException {
        long fin = System.currentTimeMillis() + delaiMs;
        while (System.currentTimeMillis() < fin) {
            PhaseIndex phase = vue.getEtat().getValue().getPhase();
            if (phase != PhaseIndex.CONSTRUCTION) return;
            TimeUnit.MILLISECONDS.sleep(10);
        }
        throw new AssertionError("index non construit à temps : "
                + vue.getEtat().getValue());
    }

    private void surveiller() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                PhaseIndex phase = vue.getEtat().getValue().getPhase();
                if (phase == PhaseIndex.PRET) {
                    pret = true;
                    for (Runnable r : observateurs) {
                        try {
                            r.run();
                        } catch (Throwable t) {
                            LOG.log(Level.WARNING,
                                    "observateur PRÊT fautif — ignoré", t);
                        }
                    }
                    return;
                }
                if (phase == PhaseIndex.PARTIEL || phase == PhaseIndex.ERREUR) {
                    // Jamais prêt : l'index reste un repli (jamais
                    // autoritaire), les observateurs ne sont pas notifiés.
                    return;
                }
                TimeUnit.MILLISECONDS.sleep(10);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean estPret() {
        return pret || vue.getEtat().getValue().getPhase() == PhaseIndex.PRET;
    }

    @Override
    public void ajouterObservateurPret(Runnable observateur) {
        if (observateur == null) return;
        if (estPret()) {
            // Déjà prêt : notifié immédiatement, une fois (contrat SPI).
            observateur.run();
            return;
        }
        observateurs.add(observateur);
    }

    @Override
    public Path jarDe(String fqcn) {
        if (fqcn == null || fqcn.isEmpty()) return null;
        try {
            var localisation = vue.localiser(fqcn);
            if (localisation == null) return null;
            Conteneur conteneur = localisation.getConteneur();
            if (conteneur instanceof Conteneur.Jar jar) {
                return Path.of(jar.getChemin());
            }
            // Arr (AAR non normalisé) / Dossier : illisible côté moteur.
            return null;
        } catch (Throwable t) {
            LOG.log(Level.FINE, "localiser a levé — " + fqcn, t);
            return null;
        }
    }

    @Override
    public Map<String, List<String>> nomsParPrefixe(String prefixe, int limite) {
        Map<String, List<String>> fusion = new LinkedHashMap<>();
        try {
            List<NomSimpleTypes> noms = vue.nomsParPrefixe(
                    prefixe == null ? "" : prefixe, limite);
            if (noms == null) return fusion;
            for (NomSimpleTypes n : noms) {
                fusion.put(n.getNomSimple(), new ArrayList<>(n.getFqcns()));
            }
        } catch (Throwable t) {
            LOG.log(Level.FINE, "nomsParPrefixe a levé", t);
        }
        return fusion;
    }

    @Override
    public boolean estPaquet(String nom) {
        try {
            return vue.estPaquet(nom == null ? "" : nom);
        } catch (Throwable t) {
            LOG.log(Level.FINE, "estPaquet a levé", t);
            return false;
        }
    }

    @Override
    public List<String> sousPaquetsDirects(String nom) {
        try {
            List<String> sous = vue.sousPaquetsDirects(nom == null ? "" : nom);
            return sous == null ? List.of() : sous;
        } catch (Throwable t) {
            LOG.log(Level.FINE, "sousPaquetsDirects a levé", t);
            return List.of();
        }
    }

    @Override
    public List<String> typesDansPaquet(String nom) {
        try {
            List<String> types = vue.typesDansPaquet(nom == null ? "" : nom);
            return types == null ? List.of() : types;
        } catch (Throwable t) {
            LOG.log(Level.FINE, "typesDansPaquet a levé", t);
            return List.of();
        }
    }

    @Override
    public List<String> membresStatiques(String fqcn) {
        List<String> noms = new ArrayList<>();
        try {
            var membres = vue.membresStatiques(fqcn == null ? "" : fqcn);
            if (membres == null) return noms;
            for (var m : membres) {
                // Public + static (imports statiques — le consommateur
                // propose ce que le code peut importer).
                if ((m.getDrapeaux() & TypesIndexKt.DRAPEAU_STATIC) != 0
                        && (m.getDrapeaux() & 0x0001) != 0) {
                    noms.add(m.getNom());
                }
            }
        } catch (Throwable t) {
            LOG.log(Level.FINE, "membresStatiques a levé", t);
        }
        return noms;
    }

    /** Libère la coroutine de surveillance (fin de test). */
    void fermer() {
        surveillant.interrupt();
        construction.cancel(null);
        racine.cancel(null);
    }

    /** Pont Java vers {@code CoroutineScope(Dispatchers.IO + racine)}. */
    private static final class CoroutineScopeKtHelper {
        static CoroutineScope pourTests(Job racine) {
            return kotlinx.coroutines.CoroutineScopeKt
                    .CoroutineScope(Dispatchers.getIO().plus(racine));
        }
    }
}
