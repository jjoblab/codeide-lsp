package jo.lspjava.ecj;

import org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolveur de noms de paramètres pour méthodes BINAIRES — portage Java du
 * pattern {@code SourceMethodResolver} du projet tiers de référence.
 *
 * <p><strong>Problème :</strong> pour les méthodes
 * binaires (jars du classpath — android.jar, libs AndroidX, JDK), ecj ne
 * dispose PAS des noms de paramètres (ils ne vivent que dans le bytecode
 * debug-info optionnelle). Le moteur rendait donc {@code arg0, arg1, arg2…}
 * pour signature help et inlay hints — visuellement pauvre vs Android Studio
 * qui affiche les vrais noms.</p>
 *
 * <p><strong>Approche :</strong> on recherche dans :</p>
 * <ol>
 *   <li><strong>src.zip du JDK</strong> ({@code java.home/lib/src.zip}) —
 *       pour {@code java.util.*}, {@code java.io.*}, etc. ;</li>
 *   <li><strong>-sources.jar du classpath</strong> — pour les libs AndroidX,
 *       Retrofit, Gson, etc. quand l'utilisateur les a attachées.</li>
 * </ol>
 *
 * <p>Pour chaque source, on extrait par regex la signature de la méthode
 * ciblée (par FQN + sélecteur + types de paramètres erasés) puis on lit
 * la liste des noms de paramètres entre parenthèses. Cache LRU soft (256
 * entrées) — les mêmes méthodes sont consultées plusieurs fois pendant une
 * frappe.</p>
 *
 * <p><strong>Limites :</strong></p>
 * <ul>
 *   <li>ne gère pas les méthodes génériques effacées (la signature du
 *       bytecode est erasée, pas la source — un {@code <T> void m(T t)} est
 *       vu comme {@code m(Object)} côté binaire, mais la source porte
 *       {@code T t} qu'on ne sait pas binder sans résolution complète ;</li>
 *   <li>ne supporte que les méthodes de premier niveau (pas les lambdas
 *       synthétiques) ;</li>
 *   <li>la recherche regex est best-effort — si elle échoue, on rend
 *       {@code arg0, arg1…}.</li>
 * </ul>
 *
 * @author jo@Dev
 */
final class EcjBinarySourceNames {

    // Cache LRU réel : LinkedHashMap synchronisée avec accessOrder=true (LRU
    // véritable) et removeEldestEntry qui n'invalide que l'entrée la plus
    // ancienne. La taille reste bornée à CACHE_LIMIT mais on garde les
    // entrées chaudes — un clear() brutal pénaliserait lourdement les gros
    // workspaces (perte totale du cache toutes les 256 insertions, obligeant
    // à re-parcourir les jars).
    private static final int CACHE_LIMIT = 256;
    private static final java.util.Map<String, List<String>> CACHE =
            java.util.Collections.synchronizedMap(
                new java.util.LinkedHashMap<String, List<String>>(CACHE_LIMIT, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(java.util.Map.Entry<String, List<String>> eldest) {
                        return size() > CACHE_LIMIT;
                    }
                });

    /** Home du JDK (pour src.zip). Peut être null si non configuré. */
    private final java.nio.file.Path jdkHome;

    /** Jars -sources du classpath. Peut être vide. */
    private final List<Path> sourceJars;

    /**
     * GRADLE_USER_HOME ({@code ~/.gradle} du layout CodeIDE) — là où Gradle
     * Tooling pioche les dépendances ET leurs {@code -sources.jar}.
     * Layout : {@code <gradleUserHome>/caches/modules-2/files-2.1/
     * <group>/<artifact>/<version>/<hash>/<artifact>-<version>-sources.jar}.
     * Peut être null sur desktop/tests.
     */
    private final java.nio.file.Path gradleUserHome;

    EcjBinarySourceNames(java.nio.file.Path jdkHome, List<Path> sourceJars) {
        this(jdkHome, sourceJars, null);
    }

    /**
     * Constructeur complet.
     *
     * @param jdkHome le JDK home résolu par {@code ToolPaths.javaHome()}
     *                (jamais fallback {@code System.getProperty("java.home")}
     *                — qui sur Android pointe vers le runtime ART)
     * @param sourceJars liste explicite de jars -sources (si déjà résolus)
     * @param gradleUserHome le {@code ~/.gradle} du layout CodeIDE — pour
     *                       scanner les caches Gradle à la recherche de
     *                       sources.jar par FQN
     */
    EcjBinarySourceNames(java.nio.file.Path jdkHome, List<Path> sourceJars,
                         java.nio.file.Path gradleUserHome) {
        this.jdkHome = jdkHome;
        this.sourceJars = sourceJars != null ? new ArrayList<>(sourceJars)
                : new ArrayList<>();
        this.gradleUserHome = gradleUserHome;
    }

    /**
     * Tente de résoudre les noms de paramètres d'une méthode binaire.
     *
     * <p>Ordre de recherche :</p>
     * <ol>
     *   <li>{@code src.zip} du JDK Termux (résolu via {@code ToolPaths.javaHome()})
     *       — pour {@code java.util.*}, {@code java.io.*}, etc. ;</li>
     *   <li>Jars {@code -sources.jar} explicitement passés au constructeur
     *       (rare — l'appelant les connaît déjà) ;</li>
     *   <li>Scan des caches Gradle ({@code ~/.gradle/caches/modules-2/files-2.1/})
     *       — pour AndroidX, Retrofit, Gson, etc. quand l'utilisateur les a
     *       téléchargées via Gradle Tooling. On cherche tous les
     *       {@code *-sources.jar} et on teste la présence de la classe
     *       (lookup ZipEntry). Meilleur effort — peut être lent au premier
     *       appel (cache ensuite).</li>
     * </ol>
     *
     * @param method le binding de la méthode binaire (par exemple
     *              {@code java.util.List.add})
     * @return la liste des noms de paramètres depuis la source, ou null si
     *         non trouvable (l'appelant retombe sur {@code arg0, arg1…}).
     */
    List<String> resolveParameterNames(MethodBinding method) {
        if (method == null || method.declaringClass == null) return null;
        String cacheKey = methodKey(method);
        List<String> cached = CACHE.get(cacheKey);
        if (cached != null) return cached;

        String fqn = String.valueOf(method.declaringClass.readableName());
        String selector = String.valueOf(method.selector);
        String[] erasedParams = new String[method.parameters == null ? 0
                : method.parameters.length];
        for (int i = 0; i < erasedParams.length; i++) {
            erasedParams[i] = erasedSimpleName(method.parameters[i]);
        }

        // 1. src.zip du JDK — java.*, javax.*, etc.
        List<String> result = resolveFromJdkSourceZip(fqn, selector, erasedParams);
        // 2. -sources.jar du classpath — AndroidX, libs tierces.
        if (result == null) {
            result = resolveFromSourceJars(fqn, selector, erasedParams);
        }
        // 3. Scan des caches Gradle (~/.gradle/caches/modules-2/files-2.1/)
        // — quand le Gradle Tooling a déjà téléchargé les dépendances ET
        // leurs sources.jar (classifier -sources), on les trouve ici. Sans
        // cette étape, les noms de paramètres des méthodes AndroidX restent à
        // arg0, arg1….
        if (result == null && gradleUserHome != null) {
            result = resolveFromGradleCaches(fqn, selector, erasedParams);
        }

        if (result != null) {
            // Le LRU gère lui-même l'éviction via removeEldestEntry : on
            // insère, et LinkedHashMap évince l'entrée la plus ancienne si
            // > CACHE_LIMIT.
            CACHE.put(cacheKey, result);
        }
        return result;
    }

    private static String methodKey(MethodBinding m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.declaringClass != null
                ? String.valueOf(m.declaringClass.readableName()) : "?");
        sb.append('.').append(String.valueOf(m.selector)).append('(');
        if (m.parameters != null) {
            for (int i = 0; i < m.parameters.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(erasedSimpleName(m.parameters[i]));
            }
        }
        sb.append(')');
        return sb.toString();
    }

    private static String erasedSimpleName(TypeBinding t) {
        if (t == null) return "Object";
        try {
            TypeBinding erasure = t.erasure() != null ? t.erasure() : t;
            String n = String.valueOf(erasure.shortReadableName());
            // Tableau → composant simple.
            return n.replace("[]", "");
        } catch (Throwable e) {
            return "Object";
        }
    }

    private List<String> resolveFromJdkSourceZip(String fqn, String selector,
                                                  String[] erasedParams) {
        if (jdkHome == null) return null;
        Path srcZip = jdkHome.resolve("lib/src.zip");
        if (!Files.isRegularFile(srcZip)) return null;
        return resolveFromZip(srcZip, fqn, selector, erasedParams);
    }

    private List<String> resolveFromSourceJars(String fqn, String selector,
                                                String[] erasedParams) {
        for (Path jar : sourceJars) {
            if (!Files.isRegularFile(jar)) continue;
            // Convention Maven : foo-bar-1.0-sources.jar
            String name = jar.getFileName().toString();
            if (!name.endsWith(".jar")) continue;
            if (!name.contains("sources") && !name.endsWith("-sources.jar")) {
                // On accepte les sources.jar même si le nom ne suit pas la
                // convention — laissons l'appelant décider via la liste.
            }
            List<String> result = resolveFromZip(jar, fqn, selector, erasedParams);
            if (result != null) return result;
        }
        return null;
    }

    /**
     * Scan des caches Gradle ({@code ~/.gradle/caches/modules-2/files-2.1/})
     * à la recherche d'un jar {@code -sources.jar} contenant la classe FQN.
     *
     * <p>Layout Gradle :
     * {@code <gradleUserHome>/caches/modules-2/files-2.1/
     * <group>/<artifact>/<version>/<hash>/<artifact>-<version>-sources.jar}.</p>
     *
     * <p>Stratégie :</p>
     * <ol>
     *   <li>Walk du répertoire {@code files-2.1/} à la recherche des fichiers
     *       nommés {@code *-sources.jar} ;</li>
     *   <li>pour chaque jar, vérifie que la classe FQN y vit (ZipEntry
     *       {@code <fqn-path>.java}) ;</li>
     *   <li>si oui, extractParameterNames — sinon passe au jar suivant.</li>
     * </ol>
     *
     * <p>Le walk est mis en cache : la liste des sources.jar est mémorisée
     * pour la durée de vie du résolveur (pas de re-scan à chaque appel).
     * Sur un projet moyen AndroidX, on a typiquement ~50 sources.jar — le
     * scan initial prend ~100 ms, ensuite chaque lookup est O(1) par
     * ZipEntry test.</p>
     */
    private List<String> resolveFromGradleCaches(String fqn, String selector,
                                                  String[] erasedParams) {
        if (gradleUserHome == null) return null;
        Path modulesDir = gradleUserHome.resolve("caches/modules-2/files-2.1");
        if (!Files.isDirectory(modulesDir)) return null;

        List<Path> sourcesJars = scanSourcesJars(modulesDir);
        if (sourcesJars.isEmpty()) return null;

        String relPath = fqn.replace('.', '/') + ".java";
        for (Path jar : sourcesJars) {
            // Test rapide : la classe est-elle dans CE jar ? (ZipEntry lookup)
            if (!jarContains(jar, relPath)) continue;
            List<String> result = resolveFromZip(jar, fqn, selector, erasedParams);
            if (result != null) return result;
        }
        return null;
    }

    /** Cache du scan des sources.jar dans les caches Gradle. */
    private volatile List<Path> scannedSourcesJars;

    /**
     * ZÉRO handle persistant : pas de pool LRU de
     * {@code NoCloseZipFile} (pattern projet tiers de référence délibérément abandonné : le
     * no-op {@code close()} n'empêche PAS le CloseGuard interne de libcore
     * d'émettre « A resource failed to call ZipFile.close. » quand un handle
     * est finalisé sans fermeture réelle — rafales observées en prod au swap
     * d'index). À la place : CACHE DE NOMS D'ENTRÉES uniquement (strings,
     * aucun fd), peuplé par une ouverture TRANSITOIRE du jar refermée aussitôt.
     *
     * <p>Portage du besoin projet tiers de référence ({@code JdtEnvironmentCache}) avec une
     * ressource strictement Java : LinkedHashMap access-order bornée à
     * {@value #MAX_CACHED_ENTRY_SETS}; chaque valeur est un Set&lt;String&gt;
     * des entrées du jar — les lookups de présence (la grande majorité des
     * appels du scanner Gradle : 50+ jars testés par signature-help) deviennent
     * des recherches en mémoire SANS aucune I/O ni ouverture.</p>
     */
    private final java.util.LinkedHashMap<Path, Set<String>> entryNamesCache =
            new java.util.LinkedHashMap<>(32, 0.75f, /*accessOrder=*/true);

    /** Nombre maximum d'ensembles de noms gardés en mémoire. */
    private static final int MAX_CACHED_ENTRY_SETS = 96;

    /**
     * @return les noms d'entrées du jar (cache mémoire ; construction par
     *         ouverture transitoire fermée immédiatement), ou null si le jar
     *         est illisible. Thread-safe via {@code synchronized}.
     *
     *         <p>Lecture via {@link RawZipReader} — plus AUCUNE
     *         instance de {@code java.util.zip.ZipFile} (le
     *         CloseGuard de la ROM cible warnait même pour des instances
     *         correctement refermées).</p>
     */
    private synchronized Set<String> entryNamesFor(Path jarPath) {
        Set<String> cached = entryNamesCache.get(jarPath);
        if (cached != null) return cached;
        Set<String> names;
        try {
            names = RawZipReader.scanNames(jarPath);
        } catch (Throwable ignored) {
            return null; // Jar illisible — l'appelant skip.
        }
        if (entryNamesCache.size() >= MAX_CACHED_ENTRY_SETS) {
            // Éviction LRU (LinkedHashMap accessOrder=true) : l'itérateur
            // retourne les entrées en ordre d'accès (least-recently-used
            // d'abord). Un Set évicté sera reconstruit au besoin ; aucune
            // ressource système à libérer (juste des strings).
            var first = entryNamesCache.entrySet().iterator();
            if (first.hasNext()) {
                first.next();
                first.remove();
            }
        }
        entryNamesCache.put(jarPath, names);
        return names;
    }

    /**
     * Lit UNE entrée d'un zip par ouverture transitoire — invariant :
     * AUCUN {@code ZipFile} ni fd ne survit à cette méthode ({@link
     * RawZipReader#readSingle} : RandomAccessFile ouvert/fermé sur place).
     */
    private static byte[] readEntryOnce(Path zipPath, String entryName) {
        try {
            return RawZipReader.readSingle(zipPath, entryName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Vide les caches mémoires — appelé par
     * {@link EcjCompilerEngine#dispose()} au shutdown du serveur LSP.
     * Idempotent.
     *
     * <p>Il n'y a AUCUN ZipFile ouvert ici (ni pool, ni handle) — seulement
     * des sets de noms et une liste de paths à libérer.</p>
     */
    synchronized void dispose() {
        entryNamesCache.clear();
        // Invalide aussi le cache du scan Gradle — un redémarrage serveur
        // doit repartir propre (l'utilisateur peut avoir ajouté des deps).
        scannedSourcesJars = null;
        // Invalide aussi le CACHE static : après un replaceAll (jars
        // différents), les noms de paramètres mis en cache pourraient être
        // obsolètes.
        CACHE.clear();
    }

    /**
     * Walk le répertoire Gradle et collecte tous les {@code *-sources.jar}.
     * Mis en cache après le premier appel (la liste ne change pas pendant une
     * session LSP — Gradle Tooling ne re-télécharge pas les dépendances déjà
     * résolues pendant une session d'édition).
     */
    private List<Path> scanSourcesJars(Path modulesDir) {
        List<Path> cached = scannedSourcesJars;
        if (cached != null) return cached;
        List<Path> out = new ArrayList<>();
        try (var walk = Files.walk(modulesDir)) {
            walk.filter(p -> {
                String name = p.getFileName() == null ? "" : p.getFileName().toString();
                return name.endsWith("-sources.jar") && Files.isRegularFile(p);
            }).forEach(out::add);
        } catch (Throwable ignored) {
            // Best-effort — Gradle caches illisible.
        }
        scannedSourcesJars = out;
        return out;
    }

    /**
     * @return true si {@code zipPath} contient l'entrée {@code entryPath}
     *         (directement ou sous un sous-dossier de module type
     *         {@code java.base/java/util/List.java}).
     *
     * <p>Consulte le CACHE DE NOMS (zéro I/O une fois construit) — SANS
     * aucun fd durable, donc sans warning CloseGuard possible.</p>
     */
    private boolean jarContains(Path zipPath, String entryPath) {
        Set<String> names = entryNamesFor(zipPath);
        if (names == null) return false;
        if (names.contains(entryPath)) return true;
        String suffix = "/" + entryPath;
        for (String name : names) {
            if (name.endsWith(suffix)) return true;
        }
        return false;
    }

    private List<String> resolveFromZip(Path zipPath, String fqn, String selector,
                                          String[] erasedParams) {
        // Convertit le FQN en chemin source. Les sources Java vivent dans
        // /<pkg>/<Type>.java. Dans src.zip du JDK, c'est parfois sous
        // un sous-dossier (java.base/, etc.).
        String found = findSourceEntryPath(zipPath, fqn);
        if (found == null) return null;

        // Lecture par ouverture transitoire refermée immédiatement —
        // invariant : AUCUN handle de ZipFile ne survit ici.
        byte[] bytes = readEntryOnce(zipPath, found);
        if (bytes == null) return null;
        try {
            String content = new String(bytes, StandardCharsets.UTF_8);
            return extractParameterNames(content, selector, erasedParams);
        } catch (Throwable ignored) {
            // Best-effort — ne pas planter le résolveur sur une regex mal formée.
            return null;
        }
    }

    /**
     * Extrait l'entrée de chemin source pour un FQN donné depuis
     * un zip, SANS consumer les bytes. Réutilise le même cache de noms
     * d'entrées que {@link #resolveFromZip} (zéro I/O sur les hits chauds).
     *
     * <p>Recherche : chemin direct ({@code <fqn-path>.java}), puis scan
     * des sous-dossiers de modules JDK ({@code java.base/<fqn-path>.java},
     * etc.) — exactement la même logique que {@link #resolveFromZip} mais
     * factorisée pour être réutilisée par {@link #readSourceAttachment}.</p>
     *
     * @param zipPath le jar à inspecter
     * @param fqn     le fully-qualified name (ex. {@code java.util.Map})
     * @return le nom de l'entrée dans le zip, ou {@code null} si absent
     */
    private String findSourceEntryPath(Path zipPath, String fqn) {
        String relPath = fqn.replace('.', '/') + ".java";
        Set<String> names = entryNamesFor(zipPath);
        if (names == null) return null;

        // Chemin direct, puis scan des sous-dossiers de modules JDK
        // (src.zip du JDK structure par module : java.base/java/util/Map.java)
        String found = names.contains(relPath) ? relPath : null;
        if (found == null) {
            String suffix = "/" + relPath;
            for (String name : names) {
                if (name.endsWith(suffix)) {
                    found = name;
                    break;
                }
            }
        }
        return found;
    }

    /**
     * Lit la source complète attachée à un FQN, dans le même ordre
     * de recherche que {@link #resolveParameterNames} (JDK src.zip →
     * {@code -sources.jar} du classpath → caches Gradle). Retourne le texte
     * source brut (verbatim), sans parsing.
     *
     * <p>Utilisé par {@link EcjCompilerEngine#decompiledSource(String)} pour
     * la fonctionnalité « GO TO Definition sur cible binaire » : quand
     * l'utilisateur Go-To sur {@code java.util.List.add(...)} et que la
     * source JDK est attachée, l'éditeur ouvre le {@code .java} extrait
     * du {@code src.zip} plutôt qu'un stub décompilé.</p>
     *
     * @param fqn fully-qualified name (ex. {@code java.util.HashMap})
     * @return le texte source complet, ou {@code null} si aucune source
     *         attachée n'est trouvable pour ce FQN
     */
    synchronized String readSourceAttachment(String fqn) {
        if (fqn == null || fqn.isEmpty()) return null;

        // 1. src.zip du JDK.
        if (jdkHome != null) {
            Path srcZip = jdkHome.resolve("lib/src.zip");
            if (Files.isRegularFile(srcZip)) {
                String src = readSourceFromZip(srcZip, fqn);
                if (src != null) return src;
            }
        }
        // 2. -sources.jar du classpath.
        for (Path jar : sourceJars) {
            if (!Files.isRegularFile(jar)) continue;
            String src = readSourceFromZip(jar, fqn);
            if (src != null) return src;
        }
        // 3. Caches Gradle (~/.gradle/caches/modules-2/files-2.1/).
        if (gradleUserHome != null) {
            Path modulesDir = gradleUserHome.resolve("caches/modules-2/files-2.1");
            if (Files.isDirectory(modulesDir)) {
                List<Path> sourcesJars = scanSourcesJars(modulesDir);
                String relPath = fqn.replace('.', '/') + ".java";
                for (Path jar : sourcesJars) {
                    if (!jarContains(jar, relPath)) continue;
                    String src = readSourceFromZip(jar, fqn);
                    if (src != null) return src;
                }
            }
        }
        return null;
    }

    /**
     * Helper privé : extrait le texte source d'un FQN depuis un
     * zip donné, en réutilisant {@link #findSourceEntryPath} puis
     * {@link #readEntryOnce}.
     */
    private String readSourceFromZip(Path zipPath, String fqn) {
        String entry = findSourceEntryPath(zipPath, fqn);
        if (entry == null) return null;
        byte[] bytes = readEntryOnce(zipPath, entry);
        if (bytes == null) return null;
        try {
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Regex d'extraction des paramètres : {@code methodName(Type1 p1, Type2 p2)}. */
    private static final Pattern METHOD_TEMPLATE = Pattern.compile(
            "\\b(mod|public|private|protected|static|final|synchronized|native|abstract|"
                    + "default|strictfp|<[^>]+>\\s+)*\\s*\\w+(?:<[^>]*>)?\\s+"
                    + "__SELECTOR__"
                    + "\\s*\\(([^)]*)\\)\\s*(?:throws[^{;]+)?[{;]",
            Pattern.MULTILINE);

    /**
     * Extrait les noms de paramètres d'une déclaration de méthode.
     *
     * <p>Approche regex : on cherche {@code <type> selector(<params>) throws?... {/;}}
     * où {@code <params>} est la liste séparée par virgules. On vérifie
     * ensuite que le nombre de types matche erasedParams.</p>
     */
    private List<String> extractParameterNames(String source, String selector,
                                                String[] erasedParams) {
        if (selector == null || selector.isEmpty()) return null;
        // Compile la pattern avec le sélecteur échappé (regex-safe).
        Pattern p = Pattern.compile(
                METHOD_TEMPLATE.pattern().replace("__SELECTOR__",
                        Pattern.quote(selector)),
                Pattern.MULTILINE);
        Matcher m = p.matcher(source);
        while (m.find()) {
            String paramsGroup = m.group(1);
            if (paramsGroup == null || paramsGroup.trim().isEmpty()) {
                // 0 params — ok si erasedParams.length == 0.
                if (erasedParams.length == 0) return Collections.emptyList();
                continue;
            }
            List<String> names = parseParameterList(paramsGroup);
            if (names != null && names.size() == erasedParams.length) {
                return names;
            }
        }
        return null;
    }

    /**
     * Parse une liste de paramètres séparés par virgules.
     * Rend le nom du dernier identifiant de chaque paramètre.
     */
    private List<String> parseParameterList(String paramsGroup) {
        List<String> out = new ArrayList<>();
        // Split sur virgules hors <> et [].
        int depth = 0;
        int start = 0;
        for (int i = 0; i < paramsGroup.length(); i++) {
            char c = paramsGroup.charAt(i);
            if (c == '<' || c == '[') depth++;
            else if (c == '>' || c == ']') depth--;
            else if (c == ',' && depth == 0) {
                String name = lastIdentifierOf(paramsGroup.substring(start, i).trim());
                if (name == null) return null;
                out.add(name);
                start = i + 1;
            }
        }
        String last = lastIdentifierOf(paramsGroup.substring(start).trim());
        if (last == null) return null;
        out.add(last);
        return out;
    }

    /** Rend le dernier identifiant Java d'un token type+nom. */
    private static String lastIdentifierOf(String typeAndName) {
        if (typeAndName == null || typeAndName.isEmpty()) return null;
        // Retire annotations finales (@Foo) et varargs (Type...).
        String s = typeAndName.replaceAll("@\\w+(\\s*\\([^)]*\\))?", "").trim();
        if (s.endsWith("...")) s = s.substring(0, s.length() - 3).trim();
        // Dernier token = identifiant.
        int i = s.length() - 1;
        while (i >= 0 && (Character.isJavaIdentifierPart(s.charAt(i)) || s.charAt(i) == '_')) {
            i--;
        }
        if (i + 1 >= s.length()) return null;
        return s.substring(i + 1);
    }
}
