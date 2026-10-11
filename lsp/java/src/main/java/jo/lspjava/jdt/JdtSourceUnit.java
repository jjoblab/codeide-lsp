package jo.lspjava.jdt;

import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;

/**
 * Unité de compilation JDT sur un contenu en mémoire, identifiée par son
 * FQCN — le pattern de {@code dev.ide.lang.jdt.env.JdtSourceUnit} de
 * projet de référence, porté en Java.
 *
 * <p>Le nom de fichier est dérivé du FQCN ({@code com/foo/Bar.java}) et le
 * nom du paquet est explicitement fourni : ecj n'a donc pas besoin de lire
 * le paquet depuis la source (l'emplacement disque du fichier est sans
 * importance).</p>
 *
 * @author jo@Dev
 */
final class JdtSourceUnit implements ICompilationUnit {

    private final char[] contents;
    private final char[][] packageName;
    private final char[] mainTypeName;
    private final char[] fileName;

    JdtSourceUnit(String fqcn, char[] contents) {
        this.contents = contents;
        String pkg = fqcn.contains(".")
                ? fqcn.substring(0, fqcn.lastIndexOf('.'))
                : "";
        this.mainTypeName = fqcn.substring(fqcn.lastIndexOf('.') + 1).toCharArray();
        this.fileName = (fqcn.replace('.', '/') + ".java").toCharArray();
        if (pkg.isEmpty()) {
            this.packageName = new char[0][];
        } else {
            String[] parts = pkg.split("\\.");
            this.packageName = new char[parts.length][];
            for (int i = 0; i < parts.length; i++) {
                packageName[i] = parts[i].toCharArray();
            }
        }
    }

    @Override
    public char[] getContents() {
        return contents;
    }

    @Override
    public char[][] getPackageName() {
        return packageName;
    }

    @Override
    public char[] getMainTypeName() {
        return mainTypeName;
    }

    @Override
    public char[] getFileName() {
        return fileName;
    }

    @Override
    public boolean ignoreOptionalProblems() {
        return false;
    }
}
