package com.garfiec.librechat.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

/**
 * Fails the build when an account-scoped Room DAO accesses a tenant table without scoping the
 * statement by `accountId`. This is the layer-3 enforcement of logical row-tenancy: the failure
 * mode of the whole design is a query that forgets its `accountId` filter, leaking one account's
 * rows into another.
 *
 * Two detection paths, because the leak is broader than `@Query` SELECTs:
 *
 * 1. **`@Query` statements** — the SQL string is parsed (no type resolution required). Any
 *    SELECT / UPDATE / DELETE touching a tenant table whose text lacks an `accountId` predicate is
 *    reported. This catches by-PK reads (`getById`), targeted updates (`updateText`), and no-WHERE
 *    deletes (`deleteAll`).
 * 2. **Concrete (block-body) methods** — `@Transaction` defaults like `upsertPreservingTags` /
 *    `replaceAll` carry their logic in Kotlin, not a `@Query` string. Without type resolution the
 *    rule cannot prove the body is account-safe, so every concrete method in a tenant DAO must be
 *    reviewed and explicitly opted out with `@CrossAccount` once it threads `accountId`.
 *
 * Opt out a genuinely cross-account statement with an `@CrossAccount` annotation on the method.
 * The rule runs without a binding context, so it works on the `detektMetadataCommonMain` source
 * set (where commonMain DAOs live).
 */
class AccountScopedDaoRule(config: Config) : Rule(config) {

    override val issue = Issue(
        id = "AccountScopedDao",
        severity = Severity.Defect,
        description = "Tenant-table DAO access must be scoped by accountId (row-tenancy enforcement).",
        debt = Debt.TWENTY_MINS,
    )

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)

        if (function.hasAnnotation(CROSS_ACCOUNT)) return

        val sql = function.queryAnnotationSql()
        if (sql != null) {
            val table = sql.tenantTableTouched() ?: return
            if (sql.isStatementNeedingScope() && !sql.hasAccountIdPredicate()) {
                report(
                    CodeSmell(
                        issue,
                        Entity.from(function),
                        "DAO @Query on tenant table `$table` is not scoped by accountId: " +
                            "${function.name}(). Add an `accountId` predicate or annotate @CrossAccount.",
                    ),
                )
            }
            return
        }

        // No @Query: a concrete method in a tenant DAO (e.g. an @Transaction default) may carry the
        // leak in its Kotlin body. Can't verify safety without type resolution -> require review.
        // `bodyBlockExpression != null` (not `hasBlockBody()`, which returns true for abstract
        // members) is what distinguishes a concrete `{ ... }` default from an abstract DAO method.
        val daoName = function.containingClassOrObject?.name
        if (daoName in TENANT_DAOS && function.bodyBlockExpression != null) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(function),
                    "Concrete method `${function.name}()` in tenant DAO `$daoName` is not a scoped " +
                        "@Query/@Upsert. Thread accountId through its body and annotate @CrossAccount.",
                ),
            )
        }
    }

    private fun KtNamedFunction.hasAnnotation(shortName: String): Boolean =
        annotationEntries.any { it.shortName?.asString() == shortName }

    /** Returns the SQL of a `@Query` annotation, or null if the function has no `@Query`. */
    private fun KtNamedFunction.queryAnnotationSql(): String? {
        val query = annotationEntries.firstOrNull { it.shortName?.asString() == "Query" } ?: return null
        val expr = query.valueArguments.firstOrNull()?.getArgumentExpression() as? KtStringTemplateExpression
            ?: return null
        // No @Query in this codebase uses template interpolation, so concatenating literal entries
        // reconstructs the SQL faithfully.
        return expr.entries.joinToString("") { (it as? KtLiteralStringTemplateEntry)?.text ?: it.text }
    }

    private fun String.tenantTableTouched(): String? =
        TENANT_TABLES.firstOrNull { table -> Regex("\\b$table\\b").containsMatchIn(this) }

    private fun String.isStatementNeedingScope(): Boolean {
        val head = trim().takeWhile { !it.isWhitespace() }.uppercase()
        return head == "SELECT" || head == "UPDATE" || head == "DELETE"
    }

    private fun String.hasAccountIdPredicate(): Boolean =
        Regex("(?i)accountId\\s*(=|<>|!=|\\bin\\b|\\bis\\b)").containsMatchIn(this)

    private companion object {
        val TENANT_TABLES = listOf("conversations", "messages", "drafts", "conversation_tags")
        val TENANT_DAOS = setOf("ConversationDao", "MessageDao", "DraftDao", "ConversationTagDao")
        const val CROSS_ACCOUNT = "CrossAccount"
    }
}
