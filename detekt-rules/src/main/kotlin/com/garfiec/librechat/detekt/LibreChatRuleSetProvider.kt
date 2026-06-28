package com.garfiec.librechat.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

class LibreChatRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = "librechat"

    override fun instance(config: Config): RuleSet =
        RuleSet(
            ruleSetId,
            listOf(
                AccountScopedDaoRule(config),
            ),
        )
}
