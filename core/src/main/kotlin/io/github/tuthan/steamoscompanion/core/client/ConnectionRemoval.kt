package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.jsonObject
import java.util.UUID

/** Remote revoke uses the durable journal; local Forget is always an explicit separate action. */
class ConnectionRemoval(
    private val journal: OperationJournal,
    private val transactions: PairingStateTransactions,
) {
    fun revoke(expected: ActiveHost, sender: JournalMutationSender): JournalDispatchResult {
        val request = ApiRequest(
            HttpMethod.POST,
            ApiRoute.PAIR_REVOKE_SELF.path,
            jsonObject("request_id" to JsonValue.StringValue(UUID.randomUUID().toString())),
            expected.bearerToken,
        )
        return journal.dispatchForHost(expected, request, sender)
    }

    /** The caller must label this action as local only, including after an unconfirmed revoke. */
    fun forgetOnThisDevice(expected: ActiveHost): StateCommitResult =
        transactions.forgetActiveHost(expected)
}
