package com.garfiec.librechat.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

/**
 * Fails the build when an account-scoped Room DAO accesses a tenant table without scoping the
 * statement by `accountId`. This is the layer-3 enforcement of logical row-tenancy: the failure
 * mode of the whole design is a query that forgets its `accountId` filter, leaking one account's
 * rows into another.
 *
 * It is **fail-closed**: anything it can't statically prove account-safe is reported, and the only
 * escape hatch is an explicit `@CrossAccount` annotation (paired, by convention, with an isolation
 * test). Five things trip it:
 *
 * 1. **`@Query` statements** — the SQL string is reconstructed from compile-time constants (single
 *    literals *and* `"a" + "b"` concatenations). Any statement touching a tenant table whose *outer*
 *    WHERE clause lacks a *positive, top-level* `accountId = ...` / `accountId IN ...` predicate is
 *    reported. Subqueries are stripped before the outer WHERE is located, so a predicate (or WHERE)
 *    buried in a subquery in the `SET`/`FROM` clause can't masquerade as scoping the statement; a
 *    validly-grouped `(accountId = :a)` predicate *is* accepted. `SET accountId = ...`, `accountId IS
 *    NULL`, and an `accountId` term joined by a top-level `OR` are **not** accepted. A statement that
 *    spans more than one tenant table or uses a `JOIN` is fail-closed: a regex can't prove which
 *    table's `accountId` the predicate scopes, so it must be split or annotated `@CrossAccount`.
 * 2. **`@Query` with non-constant SQL** (string interpolation / dynamic expression) — can't be
 *    inspected, so a tenant-DAO method carrying one is reported.
 * 3. **`@RawQuery`** in a tenant DAO — opaque to static analysis, so reported.
 * 4. **Entity-based `@Delete` / `@Update`** — Room matches the row by primary key alone (no `accountId`
 *    in the generated WHERE), so a by-PK delete/update can hit another account's row. A SQL-text rule
 *    can't add the predicate, so these must become a scoped `@Query` or be annotated `@CrossAccount`.
 * 5. **Concrete (block-body) methods** — `@Transaction` defaults like `upsertPreservingTags` /
 *    `replaceAll*` carry their logic in Kotlin, not a `@Query` string. Without type resolution the
 *    rule can't prove the body is account-safe, so every concrete method in a tenant DAO must thread
 *    `accountId` and be explicitly opted out with `@CrossAccount`.
 *
 * **Out of scope:** `@Insert` / `@Upsert` attribute the account through the `accountId` *field* of the
 * entity being written, set by hand at the callsite (`.copy(accountId = …)`). A SQL-text rule can't see
 * or enforce that field, so these are deliberately not flagged; correct attribution rests on the
 * capture-before-suspend repo convention today and is the job of the deferred `SessionWriter` facade.
 *
 * A DAO is "tenant" if its name is in [TENANT_DAOS] **or** it carries any `@Query` whose *constant* SQL
 * touches a tenant table. Inference therefore covers a renamed/new DAO that has at least one constant
 * tenant `@Query`. A DAO whose *only* tenant-table access is a `@RawQuery` or interpolated `@Query`
 * (no constant SQL to read the table name from) can't be inferred and must be added to [TENANT_DAOS]
 * explicitly; until then its opaque statements are not flagged.
 *
 * The rule runs without a binding context, so it works on the `detektMetadataCommonMain` source set
 * (where commonMain DAOs live).
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

        val daoName = function.containingClassOrObject?.name
        val inTenantDao = daoName in TENANT_DAOS || function.containingDaoTouchesTenantTable()

        // @RawQuery hands raw SQL to Room at runtime — nothing to inspect statically.
        if (inTenantDao && function.hasAnnotation(RAW_QUERY)) {
            report(
                function,
                "@RawQuery `${function.name}()` in tenant DAO `$daoName` can't be statically scoped by " +
                    "accountId. Use a scoped @Query or annotate @CrossAccount with a paired isolation test.",
            )
            return
        }

        val queryArg = function.queryAnnotationArg()
        if (queryArg != null) {
            val sql = queryArg.constStringOrNull()
            if (sql == null) {
                // @Query whose SQL isn't a compile-time constant (interpolation / dynamic expression).
                // Fail closed for tenant DAOs: we can't prove it's scoped.
                if (inTenantDao) {
                    report(
                        function,
                        "@Query `${function.name}()` in tenant DAO `$daoName` uses non-constant SQL and " +
                            "can't be verified. Build it from string constants or annotate @CrossAccount.",
                    )
                }
                return
            }
            val tables = sql.tenantTablesTouched()
            if (tables.isEmpty()) return
            if (tables.size > 1 || JOIN_KEYWORD.containsMatchIn(sql)) {
                report(
                    function,
                    "@Query `${function.name}()` in tenant DAO `$daoName` spans multiple tables " +
                        "(${tables.joinToString()}) or uses a JOIN; a regex can't prove which table's accountId " +
                        "scopes the write. Split into a single-table scoped @Query or annotate @CrossAccount.",
                )
                return
            }
            if (!sql.hasAccountIdPredicate()) {
                report(
                    function,
                    "DAO @Query on tenant table `${tables.first()}` is not scoped by accountId: ${function.name}(). " +
                        "Add an `accountId = ...` predicate to the WHERE clause or annotate @CrossAccount.",
                )
            }
            return
        }

        // Entity-based @Delete / @Update match rows by PRIMARY KEY only — Room generates no WHERE beyond
        // the PK, so account A could delete or overwrite account B's row by passing an entity carrying
        // B's id. A SQL-text rule can't inject a predicate here, so these must be replaced with a scoped
        // @Query (`... WHERE <pk> = :id AND accountId = :a`) or explicitly opted out with @CrossAccount.
        // @Insert/@Upsert are intentionally NOT flagged: they carry accountId in the entity itself —
        // attribution-by-field this rule can't verify, owned by the callsite (and, later, SessionWriter).
        if (inTenantDao && (function.hasAnnotation(DELETE) || function.hasAnnotation(UPDATE))) {
            report(
                function,
                "Entity-based @Delete/@Update `${function.name}()` in tenant DAO `$daoName` matches by " +
                    "primary key only and isn't scoped by accountId. Use a scoped @Query or annotate @CrossAccount.",
            )
            return
        }

        // No @Query: a concrete method in a tenant DAO (e.g. an @Transaction default) may carry the
        // leak in its Kotlin body. Can't verify safety without type resolution -> require review.
        // `bodyBlockExpression != null` (not `hasBlockBody()`, which returns true for abstract
        // members) is what distinguishes a concrete `{ ... }` default from an abstract DAO method.
        if (inTenantDao && function.bodyBlockExpression != null) {
            report(
                function,
                "Concrete method `${function.name}()` in tenant DAO `$daoName` is not a scoped " +
                    "@Query/@Upsert. Thread accountId through its body and annotate @CrossAccount.",
            )
        }
    }

    private fun report(function: KtNamedFunction, message: String) {
        report(CodeSmell(issue, Entity.from(function), message))
    }

    private fun KtNamedFunction.hasAnnotation(shortName: String): Boolean =
        annotationEntries.any { it.shortName?.asString() == shortName }

    // Tenant-ness of a DAO is a per-class fact, but visitNamedFunction fires once per method. Memoize
    // by the enclosing class so the member scan runs once per DAO instead of once per method (O(n) not
    // O(n^2)).
    private val tenantDaoMemo = HashMap<KtClassOrObject, Boolean>()

    /** True if the enclosing DAO has any `@Query` whose constant SQL touches a tenant table. */
    private fun KtNamedFunction.containingDaoTouchesTenantTable(): Boolean {
        val owner = containingClassOrObject ?: return false
        return tenantDaoMemo.getOrPut(owner) {
            owner.declarations.filterIsInstance<KtNamedFunction>().any { member ->
                val sql = member.queryAnnotationArg()?.constStringOrNull() ?: return@any false
                sql.tenantTablesTouched().isNotEmpty()
            }
        }
    }

    /** The value-argument expression of a `@Query` annotation, or null if the function has none. */
    private fun KtNamedFunction.queryAnnotationArg(): KtExpression? {
        val query = annotationEntries.firstOrNull { it.shortName?.asString() == "Query" } ?: return null
        return query.valueArguments.firstOrNull()?.getArgumentExpression()
    }

    /**
     * Reconstructs the SQL text from compile-time-constant string expressions: a single string
     * literal, or any `+` concatenation of constant strings. Returns null for anything non-constant
     * (`$x`/`${x}` interpolation, function calls, identifiers) so the caller can fail closed.
     */
    private fun KtExpression.constStringOrNull(): String? = when (this) {
        is KtStringTemplateExpression -> {
            val sb = StringBuilder()
            for (entry in entries) {
                when (entry) {
                    is KtLiteralStringTemplateEntry -> sb.append(entry.text)
                    is KtEscapeStringTemplateEntry -> sb.append(entry.unescapedValue)
                    else -> return null // interpolation — not a compile-time constant
                }
            }
            sb.toString()
        }
        is KtParenthesizedExpression -> expression?.constStringOrNull()
        is KtBinaryExpression -> {
            if (operationToken != KtTokens.PLUS) return null
            val left = left?.constStringOrNull() ?: return null
            val right = right?.constStringOrNull() ?: return null
            left + right
        }
        else -> null
    }

    /** Every distinct tenant table the statement references (for multi-table fail-closed detection). */
    private fun String.tenantTablesTouched(): List<String> =
        TENANT_TABLE_REGEXES.filter { (_, regex) -> regex.containsMatchIn(this) }.map { it.first }

    /**
     * A positive single-account predicate (`accountId = ...` / `accountId IN ...`) in the *outer* WHERE
     * clause. The transform order matters:
     * 1. Unwrap a *pure* parenthesized accountId predicate (`(accountId = :a)`) to its bare content so a
     *    validly-grouped scope survives the stripping below (otherwise it's a false positive).
     * 2. Strip every remaining parenthesized group (subqueries — including their inner WHERE — and
     *    grouped sub-conditions / `IN (...)` value-lists). With subqueries gone, the first WHERE in what
     *    remains is the statement's *outer* WHERE, so a subquery WHERE in a `SET`/`FROM` clause can't be
     *    mistaken for it.
     * 3. A surviving top-level `OR` fails closed: the `accountId` term may be non-restrictive (e.g.
     *    `id = :x OR accountId = :a` returns foreign rows).
     */
    private fun String.hasAccountIdPredicate(): Boolean {
        val unwrapped = PURE_ACCOUNT_ID_GROUP.replace(this) { " ${it.groupValues[1]} " }
        val outer = unwrapped.stripParenGroups().whereClause() ?: return false
        if (TOP_LEVEL_OR.containsMatchIn(outer)) return false
        return ACCOUNT_ID_PREDICATE.containsMatchIn(outer)
    }

    /** Everything after the first `WHERE` keyword, or null when the statement has no WHERE clause. */
    private fun String.whereClause(): String? {
        val match = WHERE_KEYWORD.find(this) ?: return null
        return substring(match.range.last + 1)
    }

    /** Removes parenthesized groups (subqueries, `IN (...)` value lists), innermost-first, to handle nesting. */
    private fun String.stripParenGroups(): String {
        var current = this
        while (true) {
            val next = PAREN_GROUP.replace(current, " ")
            if (next == current) return current
            current = next
        }
    }

    private companion object {
        val TENANT_TABLES = listOf("conversations", "messages", "drafts", "conversation_tags")
        // Case-insensitive: SQLite identifiers are case-insensitive, so `FROM Messages` / `FROM
        // CONVERSATIONS` hit the real tenant table and must not slip past the matcher as "no table".
        val TENANT_TABLE_REGEXES: List<Pair<String, Regex>> = TENANT_TABLES.map { it to Regex("(?i)\\b$it\\b") }
        val TENANT_DAOS = setOf("ConversationDao", "MessageDao", "DraftDao", "ConversationTagDao")
        const val CROSS_ACCOUNT = "CrossAccount"
        const val RAW_QUERY = "RawQuery"
        const val DELETE = "Delete"
        const val UPDATE = "Update"
        val WHERE_KEYWORD = Regex("(?i)\\bWHERE\\b")
        val PAREN_GROUP = Regex("\\([^()]*\\)")
        val TOP_LEVEL_OR = Regex("(?i)\\bOR\\b")
        val JOIN_KEYWORD = Regex("(?i)\\bJOIN\\b")

        // The positive single-account operator fragment, shared by the two regexes below so they can't
        // drift: equality/membership on the exact `accountId` column. `IS NULL`, `<>`, `!=` are excluded.
        private const val ACCOUNT_ID_OP = "accountId\\s*(?:=|\\bIN\\b)"

        // A parenthesized group whose entire content is a single accountId predicate, e.g.
        // `(accountId = :a)`. Group 1 is the bare predicate so it can be unwrapped and survive paren
        // stripping. A group containing more (an OR, another column, a subquery) does NOT match and is
        // stripped instead — fail-closed.
        val PURE_ACCOUNT_ID_GROUP = Regex("(?i)\\(\\s*($ACCOUNT_ID_OP[^()]*?)\\s*\\)")

        // Positive equality/membership on the exact `accountId` column (leading \b rejects e.g.
        // `userAccountId`).
        val ACCOUNT_ID_PREDICATE = Regex("(?i)\\b$ACCOUNT_ID_OP")
    }
}
