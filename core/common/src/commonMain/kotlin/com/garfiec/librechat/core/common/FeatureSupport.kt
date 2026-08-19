package com.garfiec.librechat.core.common

/**
 * What the detected backend identity actually tells us about one gated feature.
 *
 * A `Boolean` cannot carry this: it folds a server we KNOW predates the feature together with one
 * we could not place at all, and those want opposite handling — the first must never be called,
 * the second is usually a server built PAST this app's commit-map pin, i.e. the population most
 * likely to HAVE the feature.
 *
 * See `BackendVersion.featureSupport` for how each state is derived, and `VERSION_GATES.md`
 * for the per-gate rules on which state gets which behaviour.
 */
enum class FeatureSupport {
    /** The server is known to carry the feature. Call the route. */
    PRESENT,

    /**
     * The server is known NOT to carry the feature: its build commit resolved to a release or
     * prerelease TAG below the threshold (where the reported version is exact), or a dev build
     * whose commit date settles a date-gated feature. Suppress the call — it cannot succeed.
     */
    ABSENT,

    /**
     * The server could not be placed on either side. Either its build commit resolved to
     * nothing (no `buildInfo`, a commit past the app's pin, or one outside the map's window),
     * or it is a dev build, whose reported version is a floor and not a ceiling — upstream
     * bumps `package.json` only at rc prep, so a whole release cycle's worth of servers report
     * the PREVIOUS version while carrying the next one's features.
     *
     * The callsite decides. Where the feature is discoverable by asking (a cheap probe whose
     * 404 is a definitive "no"), probe once and cache the verdict. Where it is not, fail closed
     * and say so in the gate's `VERSION_GATES.md` row.
     */
    UNKNOWN,
    ;

    val isPresent: Boolean get() = this == PRESENT

    /** The route is known to be missing — the only state in which suppressing a call is free. */
    val isRuledOut: Boolean get() = this == ABSENT
}
