package jo.lspjava.ecj;

import org.eclipse.jdt.internal.compiler.ASTVisitor;
import org.eclipse.jdt.internal.compiler.ast.AllocationExpression;
import org.eclipse.jdt.internal.compiler.ast.Argument;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.LambdaExpression;
import org.eclipse.jdt.internal.compiler.ast.MessageSend;
import org.eclipse.jdt.internal.compiler.lookup.BlockScope;
import org.junit.jupiter.api.Test;

import java.util.Collections;

/** Dump empirique des formes AST (lambda/chaining/alloc) — diagnostic stdout. */
class ScratchAstShapesTest {

    private static final String SRC = """
            package demo;

            import java.util.List;
            import java.util.function.BiConsumer;

            public class Main {
                StringBuilder make() { return new StringBuilder(); }

                void run(List<String> items, BiConsumer<String, Integer> fn) {
                    fn.accept((x, y) -> { System.out.println(x); }, 2);
                    items.forEach(s -> System.out.println(s));
                    java.util.function.BiConsumer<String, Integer> typed = (String a, Integer b) -> { };
                    make()
                        .append("a")
                        .append("b");
                    new Main().run(items, fn);
                    Widget w = new Widget(1, "x");
                }
            }

            class Widget {
                Widget(int count, String label) { }
            }
            """;

    static int[] seps;

    static int lineOf(int offset) {
        int line = 1;
        for (int sep : seps) {
            if (sep < offset) line++;
        }
        return line;
    }

    @Test
    void dump() {
        EcjCompilerEngine engine = new EcjCompilerEngine(
                Collections.emptyList(), Collections.emptyList(), "17");
        CompilationUnitDeclaration cud = engine.resolveUnit("/demo/Main.java", SRC, false);
        System.out.println("cud=" + (cud != null) + " scope=" + (cud != null && cud.scope != null));
        seps = cud != null && cud.compilationResult != null
                ? cud.compilationResult.lineSeparatorPositions : new int[0];
        System.out.println("seps=" + java.util.Arrays.toString(seps));
        System.out.println("lineOf(0)=" + lineOf(0)
                + " lineOf(line2start)=" + lineOf(SRC.indexOf("package") + 8));
        EcjAst.traverseResilient(cud, new ASTVisitor() {
            @Override
            public boolean visit(LambdaExpression le, BlockScope s) {
                System.out.println("[LAMBDA] elided=" + le.argumentsTypeElided()
                        + " args=" + (le.arguments == null ? -1 : le.arguments.length));
                if (le.arguments != null) {
                    for (Argument a : le.arguments) {
                        System.out.println("   arg name=" + String.valueOf(a.name)
                                + " type=" + (a.type == null ? "NULL" : String.valueOf(a.type))
                                + " sourceStart=" + a.sourceStart
                                + " sourceEnd=" + a.sourceEnd
                                + " binding=" + (a.binding == null ? "null"
                                    : (a.binding.type == null ? "no-type"
                                        : String.valueOf(a.binding.type.readableName()))));
                    }
                }
                return true;
            }

            @Override
            public boolean visit(MessageSend ms, BlockScope s) {
                System.out.println("[SEND] selector=" + String.valueOf(ms.selector)
                        + " recv=" + (ms.receiver == null ? "null" : ms.receiver.getClass().getSimpleName())
                        + " recvResolved=" + (ms.receiver != null && ms.receiver.resolvedType != null
                            ? String.valueOf(ms.receiver.resolvedType.readableName()) : "none")
                        + " nameSrcPos=0x" + Long.toHexString(ms.nameSourcePosition)
                        + " nameStart=" + (int) (ms.nameSourcePosition >>> 32)
                        + " nameEnd=" + (int) ms.nameSourcePosition
                        + " recvEnd=" + (ms.receiver == null ? -1 : ms.receiver.sourceEnd)
                        + " line(recvEnd)=" + (ms.receiver != null ? lineOf(ms.receiver.sourceEnd + 1) : -1)
                        + " line(nameStart)=" + lineOf((int) (ms.nameSourcePosition >>> 32)));
                return true;
            }

            @Override
            public boolean visit(AllocationExpression ae, BlockScope s) {
                System.out.println("[ALLOC] class=" + ae.getClass().getSimpleName()
                        + " binding=" + (ae.binding != null ? String.valueOf(ae.binding.readableName()) : "null")
                        + " ctor=" + (ae.binding != null && ae.binding.isConstructor())
                        + " declaring=" + (ae.binding != null && ae.binding.declaringClass != null
                            ? String.valueOf(ae.binding.declaringClass.readableName()) : "?")
                        + " argc=" + (ae.arguments == null ? 0 : ae.arguments.length)
                        + " arg0class=" + (ae.arguments != null && ae.arguments.length > 0
                            ? ae.arguments[0].getClass().getSimpleName() : "-"));
                return true;
            }
        });
    }
}
