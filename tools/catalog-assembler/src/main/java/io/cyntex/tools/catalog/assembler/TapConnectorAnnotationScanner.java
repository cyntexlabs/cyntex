package io.cyntex.tools.catalog.assembler;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts a connector's {@code @TapConnectorClass} spec file and bearing class from Java source
 * text, without classloading. Line-commented annotations are ignored (some modules keep an obsolete
 * one, e.g. bigquery v1), the spec file is the annotation's string literal, and the class is the
 * first {@code class} declared after the annotation, qualified by the file's package.
 */
final class TapConnectorAnnotationScanner {

    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern ANNOTATION =
            Pattern.compile("@TapConnectorClass\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern CLASS_DECL = Pattern.compile("\\bclass\\s+(\\w+)");

    private TapConnectorAnnotationScanner() {
    }

    static Optional<TapConnectorRef> scan(String javaSource) {
        Matcher pkg = PACKAGE.matcher(javaSource);
        String packageName = pkg.find() ? pkg.group(1) : null;

        Matcher annotation = ANNOTATION.matcher(javaSource);
        while (annotation.find()) {
            if (isLineCommented(javaSource, annotation.start())) {
                continue;
            }
            Matcher classDecl = CLASS_DECL.matcher(javaSource);
            if (!classDecl.find(annotation.end())) {
                continue;
            }
            String className = classDecl.group(1);
            String fqn = packageName == null ? className : packageName + "." + className;
            return Optional.of(new TapConnectorRef(annotation.group(1), fqn));
        }
        return Optional.empty();
    }

    /** True if a {@code //} line comment opens before {@code position} on the same line. */
    private static boolean isLineCommented(String source, int position) {
        int lineStart = source.lastIndexOf('\n', position) + 1;
        return source.lastIndexOf("//", position) >= lineStart;
    }
}
