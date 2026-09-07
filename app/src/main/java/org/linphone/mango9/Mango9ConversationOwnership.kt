package org.linphone.mango9

/** Screen/attempt lease: outgoing screens cannot clear a newer conversation, even the same room. */
internal class Mango9ConversationOwnership {
    data class Lease(val owner: String, val identity: String?, val attempt: Long)

    private var serial = 0L
    private var current: Lease? = null

    @Synchronized fun claim(owner: String, identity: String?): Lease =
        Lease(owner, identity, ++serial).also { current = it }

    @Synchronized fun owns(lease: Lease, identity: String?): Boolean =
        current == lease && lease.identity != null && lease.identity == identity

    @Synchronized fun ownsOwner(owner: String, identity: String?): Boolean =
        current?.let { it.owner == owner && owns(it, identity) } == true

    @Synchronized fun release(owner: String): Boolean {
        if (current?.owner != owner) return false
        current = null
        return true
    }

    @Synchronized fun invalidate() { current = null }
}
