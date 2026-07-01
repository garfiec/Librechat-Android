package com.garfiec.librechat.core.common.identity

/**
 * Marks a Room DAO method whose statement is **deliberately not single-account-scoped at the SQL
 * level**, so the `AccountScopedDao` Detekt rule must not flag it. The annotation has one meaning —
 * "the matcher cannot prove this is account-safe; safety is established elsewhere and verified by a
 * test" — and the existing uses are all instances of it:
 *
 * - an `@Transaction` Kotlin default whose body threads `accountId` into the scoped statements it
 *   calls (the matcher can't see into the body);
 * - a legacy-claim statement scoped by `accountId IS NULL` (a negative predicate, deliberately not
 *   accepted as positive single-account scoping).
 *
 * It suppresses the *lint*, never the SQL: an annotated method is still responsible for being
 * account-safe at runtime, so every use must be paired with an isolation test that proves rows
 * don't cross accounts. Source-retained because the rule reads source PSI, not bytecode.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FUNCTION)
annotation class CrossAccount
