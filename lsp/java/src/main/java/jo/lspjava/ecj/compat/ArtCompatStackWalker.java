package jo.lspjava.ecj.compat;

/**
 * Remplaçant ART-safe de {@code java.lang.StackWalker} — réécriture
 * « niveau A » du lot 1 du traitement D3 (ADR 0110 §5.3), depuis la
 * spécification publique (javadoc du JDK), SANS lecture du code
 * projet de référence.
 *
 * <p><strong>Pourquoi ce shim existe :</strong> {@code java.lang
 * .StackWalker} est absent du runtime Android à notre minSdk (pas dans
 * {@code android.jar} avant des niveaux d'API récents, pas desugaré) et
 * ne peut pas être stubbé — les classes d'application n'ont pas le droit
 * de vivre dans l'espace de noms {@code java.*}. Les classes Eclipse
 * {@code org.eclipse.core.runtime.Status} et
 * {@code org.eclipse.core.internal.runtime.InternalPlatform} portent
 * chacune un champ {@code static StackWalker} initialisé au chargement
 * via {@code StackWalker.getInstance(StackWalker.Option
 * .RETAIN_CLASS_REFERENCE)}. Comme {@code Status} est l'un des types
 * Eclipse les plus utilisés, ce {@code <clinit>} résout
 * {@code StackWalker$Option} avidement et remonte en
 * {@link LinkageError} incapturable.</p>
 *
 * <p><strong>Spécification implémentée</strong> (javadoc
 * {@code java.lang.StackWalker}) : {@code getInstance(Option)} rend un
 * marcheur ; l'enum {@code Option} expose exactement les trois constantes
 * du JDK ({@code SHOW_HIDDEN_FRAMES}, {@code SHOW_REFLECT_FRAMES},
 * {@code RETAIN_CLASS_REFERENCE}) ; {@code getCallerClass()} rend « la
 * classe appelante de la méthode qui invoque {@code getCallerClass} ».
 * Implémentation de cette dernière <em>best-effort</em> à partir de la
 * pile d'un throwable : on saute les frames appartenant à CE shim
 * (profondeur variable — plus robuste qu'une profondeur codée en dur),
 * puis la frame de la méthode invoquante ; la première frame CHARGEABLE
 * au-delà est l'appelant recherché.</p>
 *
 * <p><strong>Divergences documentées :</strong> le JDK lève
 * {@code IllegalCallerException} quand l'appelant est introuvable et
 * {@code UnsupportedOperationException} sans
 * {@code RETAIN_CLASS_REFERENCE} — ces exceptions sont indisponibles
 * avant l'API 33, et les deux sites d'appel Eclipse attrapent et
 * tolèrent un appelant imprécis. Ce shim ne lève donc JAMAIS et ne rend
 * jamais {@code null} (repli sur sa propre classe). De plus, la surface
 * impose {@code getInstance(Option)} à UN argument (et non le varargs
 * du JDK) : le patcher bytecode ne change pas l'arité des appels, et les
 * sites Eclipse passent exactement une option.</p>
 *
 * @author jo@Dev
 */
public final class ArtCompatStackWalker {

    /** Miroir de {@code java.lang.StackWalker.Option} (constantes du JDK). */
    public enum Option {
        RETAIN_CLASS_REFERENCE,
        SHOW_REFLECT_FRAMES,
        SHOW_HIDDEN_FRAMES
    }

    private static final ArtCompatStackWalker MARCHEUR = new ArtCompatStackWalker();

    private ArtCompatStackWalker() {
    }

    /** Équivalent, pour nos usages, de
     *  {@code java.lang.StackWalker.getInstance(Option)} (un argument —
     *  contrainte de descripteur, cf. javadoc de classe). */
    public static ArtCompatStackWalker getInstance(Option option) {
        return MARCHEUR;
    }

    /**
     * Best-effort pour {@code java.lang.StackWalker#getCallerClass()} :
     * rend la classe qui a appelé la méthode ayant appelé ceci.
     *
     * <p>Méthode : les frames de ce shim sont sautées par NOM (leur
     * profondeur exacte n'importe pas), puis on saute la frame de la
     * méthode invoquante (première frame hors shim) — la suivante est
     * l'appelant selon la spécification. Une frame dont la classe ne
     * charge pas est ignorée (frame suivante) ; à défaut de tout, ce shim
     * se rend lui-même : jamais d'exception, jamais de null.</p>
     */
    public Class<?> getCallerClass() {
        StackTraceElement[] pile = new Throwable().getStackTrace();
        String nomShim = ArtCompatStackWalker.class.getName();
        int i = 0;
        while (i < pile.length && nomShim.equals(pile[i].getClassName())) {
            i++;
        }
        // La frame i est la méthode invoquante (hors shim) — l'appelant
        // est la frame suivante.
        for (int appelant = i + 1; appelant < pile.length; appelant++) {
            Class<?> c = chargerSansErreur(pile[appelant].getClassName());
            if (c != null) {
                return c;
            }
        }
        return ArtCompatStackWalker.class;
    }

    /** {@code Class.forName} silencieux — {@code null} si introuvable. */
    private static Class<?> chargerSansErreur(String nom) {
        if (nom == null || nom.isEmpty()) {
            return null;
        }
        try {
            return Class.forName(nom, false,
                    ArtCompatStackWalker.class.getClassLoader());
        } catch (Throwable introuvable) {
            return null;
        }
    }
}
