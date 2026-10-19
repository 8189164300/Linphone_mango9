// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class Mango9TasksRepositoryTest {
    @Test fun emptyLiveTasksEnvelopeUsesCamelCasePagination() = runBlocking {
        val repository =
            Mango9CalendarRepository(Mango9CalendarTestFixtures.Sessions(), { error("No refresh") }) { request ->
            assertTrue(request.url.encodedPath.endsWith("/mobile/tasks"))
            Mango9CalendarResponse(200, """{"success":true,"data":{"items":[],"counts":{},"pagination":{"page":1,"limit":50,"total":0,"hasMore":false}}}""")
        }
        val page = repository.tasks(repository.capture()!!, 1, "")
        assertEquals(1, page.page); assertFalse(page.hasMore); assertTrue(page.items.isEmpty())
    }

    @Test fun inconsistentMoreFlagIsNotSilentlyTreatedAsEmptySuccess() = runBlocking {
        val repository = Mango9CalendarRepository(Mango9CalendarTestFixtures.Sessions(), { error("No refresh") }) {
            Mango9CalendarResponse(200, """{"success":true,"data":{"items":[],"pagination":{"page":1,"hasMore":true}}}""")
        }
        assertTrue(
            runCatching {
            repository.tasks(repository.capture()!!, 1, "")
        }.exceptionOrNull() is IllegalArgumentException
        )
    }
}
