package com.github.ifrugal.gateway.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class, method, or field as an internal implementation detail
 * of Spring Gateway Toolkit. Internal API is NOT part of the project's
 * SemVer contract: it MAY change, be renamed, or be removed in any
 * release (including PATCH) without notice.
 *
 * <p>Downstream consumers should treat anything bearing this annotation
 * as if it were {@code package-private}. The annotation exists because:
 *
 * <ul>
 *   <li>Several classes have to be {@code public} for Spring's
 *       reflective machinery (bean wiring, proxying, configuration
 *       processor metadata) or because they are wrapped/extended within
 *       this same library across packages.</li>
 *   <li>Once a class is shipped to Maven Central as {@code public}, the
 *       project cannot demote its access level in a future release
 *       without a MAJOR bump. This annotation is the cheapest way to
 *       signal "do not depend on me" without breaking that rule.</li>
 *   <li>API-audit tools such as {@code revapi}, {@code apilyzer}, and
 *       IntelliJ IDEA's structural-search inspections recognise the
 *       {@code Internal} naming convention and will warn (or fail the
 *       build) when downstream code subclasses or references annotated
 *       members.</li>
 * </ul>
 *
 * <p>If you are reading this annotation on a class you want to extend,
 * file an issue describing the use case rather than subclassing. We
 * would rather promote the affected member to public API (and commit to
 * its compatibility) than have you silently depend on a moving target.
 *
 * <p>Retention is {@link RetentionPolicy#CLASS} — the marker is present
 * in compiled bytecode (so tooling can detect it post-build) but not
 * retained at runtime (so it doesn't encourage reflection-based
 * lookups). The annotation itself has no behavioural effect.
 *
 * @see <a href="https://github.com/iFrugal/gateway/blob/main/docs/versioning.md">docs/versioning.md</a>
 *      for the project's SemVer policy and the contractual meaning of
 *      "public" vs "internal".
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({
        ElementType.TYPE,
        ElementType.METHOD,
        ElementType.CONSTRUCTOR,
        ElementType.FIELD,
        ElementType.PACKAGE
})
public @interface Internal {
}
