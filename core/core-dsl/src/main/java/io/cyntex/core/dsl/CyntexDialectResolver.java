package io.cyntex.core.dsl;

import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.resolver.Resolver;

import java.util.regex.Pattern;

/**
 * Restricts snakeyaml's YAML 1.1 implicit tags to the Cyntex dialect (canonical-form.md §2,
 * review decision 2026-06-12): YAML 1.2 core schema semantics — only {@code true}/{@code false}
 * are booleans, {@code yes}/{@code no}/{@code on}/{@code off} are plain strings (so nest's
 * {@code on:} key needs no quoting), no sexagesimals, no timestamps.
 */
final class CyntexDialectResolver extends Resolver {

    @Override
    protected void addImplicitResolvers() {
        addImplicitResolver(Tag.BOOL, Pattern.compile("^(?:true|false)$"), "tf");
        addImplicitResolver(Tag.INT,
                Pattern.compile("^[-+]?[0-9]+$|^0o[0-7]+$|^0x[0-9a-fA-F]+$"), "-+0123456789");
        addImplicitResolver(Tag.FLOAT, Pattern.compile(
                        "^[-+]?(\\.[0-9]+|[0-9]+(\\.[0-9]*)?)([eE][-+]?[0-9]+)?$"
                                + "|^[-+]?\\.(?:inf|Inf|INF)$|^\\.(?:nan|NaN|NAN)$"),
                "-+0123456789.");
        addImplicitResolver(Tag.NULL, Pattern.compile("^(?:~|null|Null|NULL|)$"), "~nN ");
    }
}
