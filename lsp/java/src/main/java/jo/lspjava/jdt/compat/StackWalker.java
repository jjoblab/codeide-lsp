package jo.lspjava.jdt.compat;

/**
 * Remplaçant ART-safe de {@link java.lang.StackWalker}.
 *
 * <p>{@code java.lang.StackWalker} existe sur toute JVM depuis Java 9 mais
 * est <em>absent du runtime Android</em> à notre minSdk (pas dans
 * {@code android.jar} avant des niveaux d'API très récents, pas desugaré) et
 * il ne peut pas être stubbé — les classes d'application n'ont pas le droit
 * de vivre dans l'espace de noms {@code java.*}. Les classes Eclipse
 * {@code org.eclipse.core.runtime.Status} et
 * {@code org.eclipse.core.internal.runtime.InternalPlatform} portent chacune
 * un champ {@code static StackWalker} initialisé au chargement via
 * {@code StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)}.
 * Comme {@code Status} est l'un des types Eclipse les plus utilisés, ce
 * {@code <clinit>} résout {@code StackWalker} avidement et remonte en
 * {@link LinkageError} incapturable ({@code NoClassDefFoundError:
 * java.lang.StackWalker$Option}).</p>
 *
 * <p>Le build réécrit donc ces références vers cette classe (tâche
 * {@code :lspjava:patchEclipseForArt}), exactement comme la référence à
 * {@code Runtime$Version} d'ecj est réécrite vers
 * {@link RuntimeVersion}. Seule la surface réellement utilisée est
 * implémentée : la fabrique {@link #getInstance(Option)}, l'enum
 * {@link Option} (avec {@code RETAIN_CLASS_REFERENCE}) et
 * {@link #getCallerClass()}.</p>
 *
 * <p>{@link #getCallerClass()} est une reconstruction <em>best-effort</em> à
 * partir de la pile d'un throwable, pas une vraie marche de pile. Les deux
 * sites d'appel Eclipse ({@code Status.info/warning/error} et
 * {@code ILog.get}) enveloppent l'appel dans un
 * {@code try/catch (IllegalCallerException)} et tolèrent un appelant
 * imprécis ou {@code null} (repli sur un identifiant dérivé du nom de
 * classe), donc cette implémentation ne lève jamais — volontairement, car
 * {@code java.lang.IllegalCallerException} est lui-même indisponible avant
 * l'API 33.</p>
 *
 * <p>Porté de {@code dev.ide.lang.jdt.compat.StackWalker} (projet de référence,
 * GPLv3) — adapté au package {@code jo.lspjava}.</p>
 *
 * @author jo@Dev
 */
public final class StackWalker {

    /** Miroir de {@link java.lang.StackWalker.Option} ; seule
     *  {@code RETAIN_CLASS_REFERENCE} est référencée. */
    public enum Option {
        RETAIN_CLASS_REFERENCE,
        SHOW_REFLECT_FRAMES,
        SHOW_HIDDEN_FRAMES
    }

    private static final StackWalker INSTANCE = new StackWalker();

    private StackWalker() {
    }

    /** Équivalent, pour nos usages, de
     *  {@code java.lang.StackWalker.getInstance(Option)}. */
    public static StackWalker getInstance(Option option) {
        return INSTANCE;
    }

    /**
     * Best-effort pour {@code java.lang.StackWalker#getCallerClass()} :
     * retourne la classe qui a appelé la méthode ayant appelé ceci. Les deux
     * sites Eclipse l'invoquent un cran sous leur propre point d'entrée
     * public, donc l'appelant recherché est à profondeur fixe dans une pile
     * fraîche ([0]=cette méthode, [1]=le helper Eclipse, [2]=l'appelant
     * réel). Ne lève jamais et ne retourne jamais {@code null} ; retombe sur
     * cette classe si l'appelant est introuvable.
     */
    public Class<?> getCallerClass() {
        StackTraceElement[] trace = new Throwable().getStackTrace();
        // Saute la frame [0] (cette méthode) et [1] (le helper Eclipse) ;
        // [2] est l'appelant.
        for (int i = 2; i < trace.length; i++) {
            String name = trace[i].getClassName();
            if (name == null || name.isEmpty()) continue;
            try {
                return Class.forName(name, false,
                        StackWalker.class.getClassLoader());
            } catch (Throwable ignored) {
                // Classe réellement non chargeable ici — frame suivante.
            }
        }
        return StackWalker.class;
    }
}
