// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/** Coalesce the selected login only; optional directory work never gates chat connection. */
internal class Mango9LatestAccountRefresh<K>(
    private val scope: CoroutineScope,
    private val directory: suspend (K) -> Unit,
    private val connect: suspend (K) -> Unit,
) {
    private var current: K? = null
    private var work: Job? = null

    @Synchronized fun refresh(key: K) {
        if (current == key && work?.isActive == true) return
        work?.cancel()
        current = key
        work = scope.launch(start = CoroutineStart.LAZY) {
            supervisorScope {
                launch { directory(key) }
                launch { connect(key) }
            }
        }.also { it.start() }
    }

    @Synchronized fun cancel() {
        work?.cancel()
        work = null
        current = null
    }
}
