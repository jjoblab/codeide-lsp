package jo.lspjava.ecj;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.logging.Logger;
import java.util.zip.ZipFile;

import jo.lspjava.api.CompletionItem;

/**
 * Banc de mesure du MOTEUR (mission « LSP Java », étape 3 lot F2 —
 * docs/LSP_JAVA.md §15.8) : port DANS LE DÉPÔT du banc d'étude
 * ({@code BancMesure.java}, hors dépôt et perdu avec le bac à sable
 * d'étude — les scénarios sont RECÉÉS ici d'après §4.2/§11.1).
 *
 * <p><strong>But :</strong> mesurer la complétion NOM (types) p50/p95
 * AVANT (moteur SANS index — balayage linéaire W3) puis APRÈS (moteur
 * BRANCHÉ sur l'index disque, lot C) sur les jeux §12 — la cible §9 est
 * p95 &lt; 100 ms ; mesurer aussi le diagnose chaud (memo et édition
 * confinée), le tas et les poignées de fichiers, et la libération à
 * {@code dispose()} (contrat « 0 ressource orpheline »).</p>
 *
 * <p><strong>Périmètre :</strong> coût MOTEUR SEUL, sans LSP4J ni UI
 * (comme §4.1). Une invocation = UN jeu × UN mode (JVM séparée par
 * run — {@code -Xmx2g -XX:+UseSerialGC}, §11.1) ; la tâche
 * {@code :lsp:testing:bench} orchestre les runs. Écart de protocole
 * documenté : {@value #ECHAUFFEMENTS} échauffements précèdent chaque
 * série (la cible §9 porte sur le régime CHAUD, pas le premier appel
 * qui absorbe l'initialisation) ; le premier diagnose est mesuré
 * SÉPARÉMENT ({@code premierDiagnoseMs}).</p>
 *
 * <p><strong>Corpus :</strong> le manifeste (une entrée par ligne,
 * chemins absolus) est NORMALISÉ — les AAR y sont réduits à leur
 * {@code classes.jar} extrait ({@link #normaliserAar}), comme la
 * production normalise à la résolution (v0.92, §2.2) : les DEUX modes
 * voient exactement les mêmes jars, la comparaison est honnête. Les
 * moteurs reçoivent la conformité 17 et le {@code java.home} du run
 * (JDK complet — image jrt + src.zip).</p>
 *
 * <p><strong>Modules :</strong> vit dans le source set de TEST de
 * {@code :lsp:testing} (v0.96.3) — même paquet {@code jo.lspjava.ecj}
 * que {@link AdaptateurIndexNoms} (membres package-private), jamais
 * dans la chaîne de vérification.</p>
 *
 * @author jo@Dev
 */
public final class BancMoteur {

    private static final Logger LOG = Logger.getLogger("BancMoteur");

    /** Itérations de mesure par métrique — protocole §11.1 : 30. */
    static final int ITERATIONS_DEFAUT = 30;

    /**
     * Échauffements avant chaque série (régime chaud — documenté
     * §15.8 : la cible p95 porte sur l'état stabilisé, le coût du
     * premier appel est porté par {@code premierDiagnoseMs}).
     */
    static final int ECHAUFFEMENTS = 3;

    /** Conformité unique du banc (production : par module, lot E). */
    private static final String COMPLIANCE = "17";

    /** Délai d'attente de la construction de l'index disque (ms). */
    private static final long DELAI_INDEX_DEFAUT_MS = 600_000L;

    /** Le corpus normalisé d'un manifeste. */
    record Corpus(List<Path> jars, int aarsNormalises, int entreesIgnorees) {}

    /**
     * Point d'entrée — arguments :
     * <pre>
     *   BancMoteur &lt;avant|apres&gt; &lt;manifeste.txt&gt; &lt;sortie.json&gt;
     *             &lt;repertoireTravail&gt; [iterations] [delaiIndexMs]
     * </pre>
     *
     * @param args voir ci-dessus
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 4
                || !(args[0].equals("avant") || args[0].equals("apres"))) {
            System.err.println("usage : BancMoteur <avant|apres> <manifeste>"
                    + " <sortie.json> <repertoireTravail>"
                    + " [iterations] [delaiIndexMs]");
            System.exit(2);
        }
        String mode = args[0];
        Path manifeste = Path.of(args[1]);
        Path sortieJson = Path.of(args[2]);
        Path repTravail = Path.of(args[3]);
        int iterations = args.length > 4
                ? Integer.parseInt(args[4]) : ITERATIONS_DEFAUT;
        long delaiIndex = args.length > 5
                ? Long.parseLong(args[5]) : DELAI_INDEX_DEFAUT_MS;

        Map<String, Object> mesures = new LinkedHashMap<>();
        mesures.put("jeu", manifeste.getParent().getFileName().toString());
        mesures.put("mode", mode);
        mesures.put("iterations", iterations);
        int code = executer(mode, manifeste, repTravail,
                iterations, delaiIndex, mesures);

        // Mesure APRÈS dispose, les locales d'executer SONT MORTES (le
        // champ indexNoms du moteur est FINAL — dispose() ne peut pas le
        // détacher, l'index tient le cache LRU : la chaîne ne doit rester
        // atteignable de RIEN — contrat « 0 ressource orpheline »).
        if (code == 0) {
            mesures.put("tasApresDisposeMo", tasUtiliseMo());
            mesures.put("poigneesApresDispose", poignees());
        }

        Files.createDirectories(sortieJson.getParent());
        Files.writeString(sortieJson, json(mesures), StandardCharsets.UTF_8);
        System.out.println("[BANC] " + jeuDe(mesures) + " " + mode
                + " → " + sortieJson + " (exit " + code + ")");
        System.exit(code);
    }

    /**
     * Le run complet d'un (jeu × mode) — remplit {@code mesures}.
     *
     * @return le code de sortie (0 = mesures complètes, 1 = erreur
     *         fatale documentée dans les mesures)
     */
    private static int executer(String mode, Path manifeste,
                                Path repTravail, int iterations,
                                long delaiIndex,
                                Map<String, Object> mesures) throws Exception {
        // Racine de sources = src (les fichiers vivent dans src/banc —
        // le FQCN dérivé du chemin doit CONCORDER avec le package déclaré,
        // sinon « declared package does not match » pollue le diagnose).
        Path src = Files.createDirectories(repTravail.resolve("src"));
        Files.createDirectories(src.resolve("banc"));
        Corpus corpus = chargerCorpus(manifeste, repTravail.resolve("normalises"));
        mesures.put("jars", corpus.jars().size());
        mesures.put("aarsNormalises", corpus.aarsNormalises());
        mesures.put("entreesIgnorees", corpus.entreesIgnorees());
        if (corpus.jars().isEmpty()) {
            mesures.put("erreur", "CORPUS_VIDE");
            LOG.severe("corpus vide — manifeste : " + manifeste);
            return 1;
        }

        // ── Index disque (mode APRÈS uniquement) ──────────────────────
        // Stockage EFFACÉ au départ : la construction est mesurée à
        // FROID ; la reprise (segments déjà présents) est mesurée à la
        // FIN du run (tempsRepriseDisqueMs).
        AdaptateurIndexNoms index = null;
        Path stockage = repTravail.getParent().resolve(
                "index-" + mesures.get("jeu"));
        if (mode.equals("apres")) {
            // Budget de cache PROPORTIONNEL au corpus (~512 Kio/jar,
            // plancher 32 Mio) : la cible §9 « tas < 30 Mo / 100 jars »
            // EST un budget par jar — à 250 jars le budget PLAT de 30 Mio
            // fait évincer l'ensemble de travail à chaque requête GLOBALE
            // (complétion 452–536 ms, diagnose édité 523–593 ms — MESURÉ,
            // §15.8) ; résident, les mêmes requêtes tiennent en
            // millisecondes. L'hôte de production (étape 4) dérivera le
            // budget du modèle — arbitrage documenté.
            long budget = Math.max(32L * 1024 * 1024,
                    corpus.jars().size() * 512L * 1024);
            mesures.put("budgetCacheMo", budget / (1024 * 1024));
            effacer(stockage);
            long debut = System.nanoTime();
            index = AdaptateurIndexNoms.enConstruction(
                    stockage, corpus.jars(), budget);
            index.attendreFin(delaiIndex);
            mesures.put("tempsConstructionDisqueMs", ms(debut));
            if (!index.estPret()) {
                // Diagnostic honnête : phase + progression + causes.
                var etat = index.etat();
                mesures.put("phaseIndex", etat.getPhase().name());
                mesures.put("indexFaits", etat.getFaits());
                mesures.put("indexTotal", etat.getTotal());
                mesures.put("indexAvertissements",
                        String.join(" | ", etat.getAvertissements()));
                mesures.put("indexErreurs",
                        String.join(" | ", etat.getErreurs()));
                mesures.put("erreur", "INDEX_NON_PRET");
                LOG.severe("index non prêt : phase=" + etat.getPhase()
                        + " faits=" + etat.getFaits() + "/" + etat.getTotal()
                        + " avertissements=" + etat.getAvertissements()
                        + " erreurs=" + etat.getErreurs());
                return 1;
            }
        }

        // ── Moteur (le même pour les deux modes, SEUL l'index change) ─
        List<java.io.File> fichiers = new ArrayList<>();
        for (Path jar : corpus.jars()) fichiers.add(jar.toFile());
        Path jdkHome = Path.of(System.getProperty("java.home"));
        EcjCompilerEngine moteur = new EcjCompilerEngine(
                List.of(src.toFile()), fichiers, COMPLIANCE, jdkHome, index);

        if (mode.equals("avant")) {
            // Index mémoire : construit au premier usage — forcé et
            // CHRONOMÉTRÉ via typeIndex() (ensureIndex SYNCHRONE ;
            // preWarmIndex serait asynchrone : il ne mesure rien).
            // Équivalent §4.2 « Index mémoire, JVM froide ».
            long debut = System.nanoTime();
            moteur.typeIndex();
            mesures.put("tempsIndexMemoireMs", ms(debut));
        }

        // ── Diagnose : premier appel, memo, édition confinée ──────────
        boolean avecAndroid = corpus.jars().stream().anyMatch(
                p -> p.getFileName().toString().equals("android.jar"));
        Path fichierDiag = src.resolve("banc/ClasseBanc.java");
        String contenuDiag = sourceDiagnose(avecAndroid);
        Files.writeString(fichierDiag, contenuDiag, StandardCharsets.UTF_8);

        long debut = System.nanoTime();
        List<jo.lspjava.api.Diagnostic> premiers =
                moteur.diagnose(fichierDiag.toString(), contenuDiag);
        mesures.put("premierDiagnoseMs", ms(debut));
        mesures.put("premierDiagnoseErreurs", nbErreurs(premiers));

        long[] diagMemo = chronometrer(iterations,
                i -> moteur.diagnose(fichierDiag.toString(), contenuDiag));
        mesures.put("diagnoseMemoP50Ms", p50Ms(diagMemo));
        mesures.put("diagnoseMemoP95Ms", p95Ms(diagMemo));

        long[] diagEdite = chronometrer(iterations, i -> {
            // Édition CONFINÉE dans le corps de m() (fast-path P6) ;
            // contenu distinct à chaque itération (memo ne peut pas
            // répondre, le parse cache évince — régime « re-diagnose
            // après frappe »).
            String variant = contenuDiag.replace(
                    "int x = 0;", "int x = " + (i + ECHAUFFEMENTS) + ";");
            return moteur.diagnose(fichierDiag.toString(), variant);
        });
        mesures.put("diagnoseEditeP50Ms", p50Ms(diagEdite));
        mesures.put("diagnoseEditeP95Ms", p95Ms(diagEdite));

        // ── Complétion NOM (types) — la métrique §9 ───────────────────
        EcjCompletionEngine completer = new EcjCompletionEngine(moteur);
        Path fichierNom = src.resolve("banc/ComplNom.java");
        String avantCaret = "package banc;\n\npublic class ComplNom {\n"
                + "    void m() {\n        B";
        String sourceNom = avantCaret + "\n    }\n}\n";
        Files.writeString(fichierNom, sourceNom, StandardCharsets.UTF_8);
        int caretNom = avantCaret.length();
        List<CompletionItem> itemsNom = completer.complete(
                fichierNom.toString(), sourceNom, caretNom);
        long[] complNom = chronometrer(iterations,
                i -> completer.complete(fichierNom.toString(), sourceNom, caretNom));
        mesures.put("completionNomP50Ms", p50Ms(complNom));
        mesures.put("completionNomP95Ms", p95Ms(complNom));
        mesures.put("completionNomItems", itemsNom.size());

        // ── Complétion MEMBRE (contrôle — déjà tenue §9) ──────────────
        Path fichierMembre = src.resolve("banc/ComplMembre.java");
        String avantMembre = "package banc;\n\npublic class ComplMembre {\n"
                + "    void m() {\n"
                + "        java.util.List<String> b = null;\n        b";
        String sourceMembre = avantMembre + "\n    }\n}\n";
        Files.writeString(fichierMembre, sourceMembre, StandardCharsets.UTF_8);
        int caretMembre = avantMembre.length();
        List<CompletionItem> itemsMembre = completer.complete(
                fichierMembre.toString(), sourceMembre, caretMembre);
        long[] complMembre = chronometrer(iterations,
                i -> completer.complete(fichierMembre.toString(),
                        sourceMembre, caretMembre));
        mesures.put("completionMembreP50Ms", p50Ms(complMembre));
        mesures.put("completionMembreP95Ms", p95Ms(complMembre));
        mesures.put("completionMembreItems", itemsMembre.size());

        // ── Tas et poignées (état stable) ─────────────────────────────
        mesures.put("tasStableMo", tasUtiliseMo());
        mesures.put("poigneesStables", poignees());
        // dispose ici ; la mesure « après dispose » vit dans main(), les
        // locales ici doivent être MORTES à ce moment-là.
        moteur.dispose();
        if (index != null) index.fermer();

        // ── Reprise de l'index disque (segments déjà présents) ────────
        if (mode.equals("apres")) {
            long debutReprise = System.nanoTime();
            AdaptateurIndexNoms reprise = AdaptateurIndexNoms.enConstruction(
                    stockage, corpus.jars(),
                    Math.max(32L * 1024 * 1024,
                            corpus.jars().size() * 512L * 1024));
            reprise.attendreFin(delaiIndex);
            mesures.put("tempsRepriseDisqueMs", ms(debutReprise));
            reprise.fermer();
        }
        return 0;
    }

    // ─── Scénarios sources ──────────────────────────────────────────

    /**
     * Source du scénario diagnose — référence le classpath (Bundle si
     * android.jar est présent, sinon ArrayList de l'image jrt) pour que
     * la résolution ecj PARCOURRE réellement les jars.
     */
    static String sourceDiagnose(boolean avecAndroid) {
        String fqcn = avecAndroid ? "android.os.Bundle" : "java.util.ArrayList";
        String simple = fqcn.substring(fqcn.lastIndexOf('.') + 1);
        return "package banc;\n\nimport " + fqcn + ";\n\n"
                + "public class ClasseBanc {\n"
                + "    private final " + simple + " champ = new " + simple + "();\n"
                + "    void m() {\n        int x = 0;\n"
                + "        champ.hashCode();\n    }\n}\n";
    }

    // ─── Corpus : manifeste + normalisation AAR ─────────────────────

    /**
     * Charge et NORMALISE le manifeste : jars tels quels, AAR réduits à
     * leur {@code classes.jar} extrait (idempotent), doublons dédupliqués,
     * entrées absentes ignorées (comptées), ordre déterministe.
     */
    static Corpus chargerCorpus(Path manifeste, Path dossierNormalises)
            throws IOException {
        List<Path> jars = new ArrayList<>();
        Set<Path> vus = new LinkedHashSet<>();
        int aars = 0;
        int ignores = 0;
        for (String ligne : Files.readAllLines(manifeste, StandardCharsets.UTF_8)) {
            String chemin = ligne.trim();
            if (chemin.isEmpty() || chemin.startsWith("#")) continue;
            Path p = Path.of(chemin);
            if (!Files.isRegularFile(p)) {
                ignores++;
                LOG.warning("entrée absente ignorée : " + chemin);
                continue;
            }
            String nom = p.getFileName().toString();
            if (nom.endsWith(".jar")) {
                if (vus.add(p.toAbsolutePath().normalize())) jars.add(p);
            } else if (nom.endsWith(".aar")) {
                Path extrait = normaliserAar(p, dossierNormalises);
                if (extrait != null) {
                    aars++;
                    if (vus.add(extrait)) jars.add(extrait);
                } else {
                    ignores++;
                }
            } else {
                ignores++;
                LOG.warning("entrée ni jar ni aar ignorée : " + chemin);
            }
        }
        jars.sort(Comparator.comparing(Path::toString));
        return new Corpus(List.copyOf(jars), aars, ignores);
    }

    /**
     * Extrait {@code classes.jar} d'un AAR vers
     * {@code <dossier>/<nom>.aar.classes.jar} (idempotent — retourne le
     * fichier existant). {@code null} si l'AAR n'a pas de classes.jar ou
     * est illisible (jamais d'exception : le banc compte et continue).
     */
    static Path normaliserAar(Path aar, Path dossier) {
        try {
            Files.createDirectories(dossier);
            Path sortie = dossier.resolve(
                    aar.getFileName().toString() + ".classes.jar");
            if (Files.isRegularFile(sortie)) return sortie;
            try (ZipFile zip = new ZipFile(aar.toFile())) {
                var entree = zip.getEntry("classes.jar");
                if (entree == null) {
                    LOG.warning("AAR sans classes.jar : " + aar);
                    return null;
                }
                try (InputStream in = zip.getInputStream(entree);
                     OutputStream out = Files.newOutputStream(sortie)) {
                    in.transferTo(out);
                }
            }
            return sortie;
        } catch (IOException t) {
            LOG.warning("AAR illisible (" + t + ") : " + aar);
            try {
                Files.deleteIfExists(dossier.resolve(
                        aar.getFileName().toString() + ".classes.jar"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return null;
        }
    }

    // ─── Chronométrage et percentiles ───────────────────────────────

    /**
     * Chronomètre {@code iterations} appels (retour en NANOSECONDES,
     * précision conservée pour les métriques sub-millisecondes) après
     * {@value #ECHAUFFEMENTS} échauffements ; l'index de l'appel va de
     * 0 à {@code iterations - 1} (les échauffements reçoivent des
     * indices négatifs distincts — contenus différents).
     */
    static long[] chronometrer(int iterations, IntFunction<Object> appel) {
        for (int i = 0; i < ECHAUFFEMENTS; i++) {
            appel.apply(-i - 1);
        }
        long[] temps = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            long debut = System.nanoTime();
            appel.apply(i);
            temps[i] = System.nanoTime() - debut;
        }
        return temps;
    }

    /**
     * Percentile au rang le plus proche (nearest-rank) sur un tableau
     * NON trié — copie triée interne, jamais de mutation.
     *
     * @param p le percentile voulu (1–99)
     * @return la valeur en nanosecondes
     */
    static long percentile(long[] valeurs, int p) {
        if (valeurs == null || valeurs.length == 0) return -1;
        long[] tri = valeurs.clone();
        java.util.Arrays.sort(tri);
        int rang = (int) Math.ceil(p / 100.0 * tri.length);
        int index = Math.min(Math.max(rang, 1), tri.length) - 1;
        return tri[index];
    }

    /** p50 en millisecondes (arrondi au millième). */
    static double p50Ms(long[] nanos) {
        return msDe(percentile(nanos, 50));
    }

    /** p95 en millisecondes (arrondi au millième). */
    static double p95Ms(long[] nanos) {
        return msDe(percentile(nanos, 95));
    }

    /** Nano → millisecondes (arrondi au millième). */
    static double msDe(long nanos) {
        return Math.round(nanos / 1_000.0) / 1_000.0;
    }

    /** Millisecondes écoulées depuis {@code debut} (nanoTime). */
    static double ms(long debut) {
        return msDe(System.nanoTime() - debut);
    }

    // ─── Tas et poignées ────────────────────────────────────────────

    /** Tas utilisé (Mo) après GC déterministes. */
    static long tasUtiliseMo() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024);
    }

    /** Poignées de fichiers ouvertes ({@code /proc/self/fd}, Linux). */
    static long poignees() {
        Path dir = Path.of("/proc/self/fd");
        if (!Files.isDirectory(dir)) return -1;
        try (var flux = Files.list(dir)) {
            return flux.count();
        } catch (IOException e) {
            return -1;
        }
    }

    // ─── Divers ─────────────────────────────────────────────────────

    /** Efface récursivement un répertoire (reconstruction à froid). */
    static void effacer(Path dossier) throws IOException {
        if (!Files.exists(dossier)) return;
        try (var flux = Files.walk(dossier)) {
            flux.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
    }

    /** Nombre de diagnostics de sévérité ERREUR. */
    static int nbErreurs(List<jo.lspjava.api.Diagnostic> diags) {
        int n = 0;
        for (jo.lspjava.api.Diagnostic d : diags) {
            if (d.severity() == jo.lspjava.api.Severity.ERROR) n++;
        }
        return n;
    }

    /** Le nom du jeu courant (parent du manifeste). */
    static String jeuDe(Map<String, Object> mesures) {
        return String.valueOf(mesures.get("jeu"));
    }

    /**
     * Émetteur JSON minimal (ordre d'insertion conservé, aucune
     * dépendance) — clés ASCII, valeurs chaîne échappées, nombres et
     * booléens bruts.
     */
    static String json(Map<String, Object> champs) {
        StringBuilder sb = new StringBuilder("{\n");
        boolean premier = true;
        for (Map.Entry<String, Object> e : champs.entrySet()) {
            if (!premier) sb.append(",\n");
            premier = false;
            sb.append("  \"").append(e.getKey()).append("\": ");
            Object v = e.getValue();
            if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append('"').append(String.valueOf(v)
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\""))
                        .append('"');
            }
        }
        return sb.append("\n}\n").toString();
    }
}
