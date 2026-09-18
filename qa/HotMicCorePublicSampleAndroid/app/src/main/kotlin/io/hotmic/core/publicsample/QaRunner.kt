package io.hotmic.core.publicsample

import android.content.Context
import android.os.Bundle
import android.os.Debug
import io.hotmic.core.HotMicClient
import io.hotmic.core.HotMicError
import io.hotmic.core.HotMicStreamQuery
import io.hotmic.core.HotMicStreamSession
import io.hotmic.core.LogLevel
import io.hotmic.core.models.HotMicChatMessage
import io.hotmic.core.models.HotMicStream
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Scripted QA rows (Android mirror of the iOS `QARunner`). Every row logs `ROW <id> PASS|FAIL|INFO …`
 * and the runner ends with `AUTORUN COMPLETE`. Rows that need the host driven from outside
 * (background/Doze/airplane, curl-created chats, a second client) are the `HOLD` row: it keeps a
 * session up for `HEM96_HOLD_SECONDS` and logs every event with a wall-clock timestamp.
 */
object QaRunner {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var config: AppConfig
    private lateinit var app: Context
    private var extras: Bundle? = null

    fun start(context: Context, cfg: AppConfig, rows: String, bundle: Bundle?) {
        app = context.applicationContext
        config = cfg
        extras = bundle
        scope.launch {
            for (row in rows.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }) {
                Hem96.log("ROW $row begin")
                try {
                    when (row) {
                        "N4" -> n4ListFilterPage()
                        "N5" -> n5Detail()
                        "N6" -> n6StartLatency()
                        "N8" -> n8Chat()
                        "N10" -> n10Polls()
                        "N11" -> n11Participants()
                        "N12" -> n12Block()
                        "N15" -> n15BadCredentials()
                        "N15MID" -> n15MidSessionExpiry()
                        "N16" -> n16Rotation()
                        "N17" -> n17Lifecycle()
                        "N19" -> n19MemoryCycles()
                        "N20" -> n20Soak()
                        "N21" -> n21EventListener()
                        "N24" -> n24FieldCoverage()
                        "HOLD" -> hold()
                        else -> Hem96.log("ROW $row SKIP unknown row")
                    }
                } catch (error: Throwable) {
                    Hem96.log("ROW $row FAIL exception ${error.label()}")
                }
            }
            Hem96.log("AUTORUN COMPLETE")
        }
    }

    private fun ext(key: String, default: String = ""): String = extras?.getString(key) ?: config[key] ?: default
    private fun extInt(key: String, default: Int): Int = extras?.getString(key)?.toIntOrNull() ?: default

    private fun client(token: String = config.accessToken, apiKey: String = config.apiKey, withContext: Boolean = true) =
        if (withContext) HotMicClient(app, apiKey, token, LogLevel.DEBUG) else HotMicClient(apiKey, token, LogLevel.DEBUG)

    /** Records every event of a session with a wall-clock stamp and counts them. */
    private class Recorder(val session: HotMicStreamSession, val tag: String, val verbose: Boolean = true) {
        val events = mutableListOf<Pair<Long, HotMicStreamSession.Event>>()
        val chatIdsSeen = mutableMapOf<String, Int>()
        var duplicates = 0
        val job: Job = scope.launch {
            session.events.collect { event ->
                events += System.currentTimeMillis() to event
                if (event is HotMicStreamSession.Event.ChatBatchReceived) {
                    event.batch.messages.forEach { m ->
                        val n = (chatIdsSeen[m.id] ?: 0) + 1
                        chatIdsSeen[m.id] = n
                        if (n > 1) duplicates++
                        if (verbose) Hem96.log("$tag CHAT id=${m.id} user=${m.userId.take(8)} created=${m.createdAt} received=${Hem96.nowIso()} dup=${n > 1} text=${m.message.take(40)}")
                    }
                }
                if (verbose) Hem96.log("$tag EVENT ${Hem96.nowIso()} ${event.label()}")
            }
        }
        fun count(predicate: (HotMicStreamSession.Event) -> Boolean) = events.count { predicate(it.second) }
        fun close() = job.cancel()
    }

    private suspend fun startRecorded(c: HotMicClient, streamId: String, tag: String, verbose: Boolean = true): Pair<Recorder, HotMicStreamSession.Snapshot> {
        val session = c.makeStreamSession(streamId)
        val rec = Recorder(session, tag, verbose)
        val t0 = System.currentTimeMillis()
        val snapshot = session.start()
        Hem96.log("$tag start_ms=${System.currentTimeMillis() - t0} state=${session.state.value.label()} stream=${snapshot.stream.state.rawValue} user=${snapshot.user.displayName} chats=${snapshot.chatMessages.size} polls=${snapshot.polls.size} room=${snapshot.participants.room.size}")
        return rec to snapshot
    }

    // ---------------------------------------------------------------- N4
    private suspend fun n4ListFilterPage() {
        val c = client()
        suspend fun q(label: String, query: HotMicStreamQuery) {
            try {
                val page = c.fetchStreams(query)
                val states = page.streams.groupingBy { it.state.rawValue }.eachCount()
                Hem96.log("N4 $label → n=${page.streams.size} states=$states page=${page.pagination.currentPage}/${page.pagination.totalPages} total=${page.pagination.totalCount} perPage=${page.pagination.perPage} hasNext=${page.pagination.hasNext} hasPrev=${page.pagination.hasPrev} firstId=${page.streams.firstOrNull()?.id?.take(8)}")
            } catch (e: HotMicError) { Hem96.log("N4 $label → ${e.label()}") }
        }
        q("default", HotMicStreamQuery())
        q("live-only", HotMicStreamQuery(includesLive = true, includesScheduled = false, includesVOD = false))
        q("scheduled-only", HotMicStreamQuery(includesLive = false, includesScheduled = true, includesVOD = false))
        q("vod-only", HotMicStreamQuery(includesLive = false, includesScheduled = false, includesVOD = true))
        q("all-off", HotMicStreamQuery(includesLive = false, includesScheduled = false, includesVOD = false))
        q("limit1", HotMicStreamQuery(limit = 1))
        q("limit100", HotMicStreamQuery(limit = 100))
        q("page2-limit1", HotMicStreamQuery(limit = 1, page = 2))
        q("page9999", HotMicStreamQuery(page = 9999))
        // limit=7 twice with different state to expose CDN caching keyed on limit only (iOS F5).
        q("vod-limit7", HotMicStreamQuery(includesLive = false, includesScheduled = false, includesVOD = true, limit = 7))
        q("live-limit7", HotMicStreamQuery(includesLive = true, includesScheduled = false, includesVOD = false, limit = 7))
        val owner = ext("HEM96_OWNER_USER_ID")
        if (owner.isNotEmpty()) q("userId=owner", HotMicStreamQuery(userId = owner))
        q("userId=bogus", HotMicStreamQuery(userId = "bogus-user"))
        q("limit0", HotMicStreamQuery(limit = 0))
        q("page0", HotMicStreamQuery(page = 0))
        Hem96.log("ROW N4 INFO see lines above (CDN caching verdict is computed from them)")
    }

    // ---------------------------------------------------------------- N5
    private suspend fun n5Detail() {
        val c = client()
        suspend fun s(label: String, id: String) {
            try {
                val x = c.fetchStream(id)
                Hem96.log("N5 fetchStream $label → id=${x.id.take(8)} state=${x.state.rawValue} type=${x.type.rawValue} hls=${x.hlsUrl != null} vod=${x.vodUrl != null} thumb=${x.thumbnail != null} viewers=${x.viewers} dur=${x.duration} live=${x.liveDate} sched=${x.scheduledDate} title=${x.title}")
            } catch (e: HotMicError) { Hem96.log("N5 fetchStream $label → ${e.label()}") }
        }
        suspend fun u(label: String, id: String) {
            try {
                val x = c.fetchUser(id)
                Hem96.log("N5 fetchUser $label → id=${x.id.take(8)} name=${x.name} display=${x.displayName} pic=${x.profilePic != null} badge=${x.badge != null} bio=${x.bio != null} tw=${x.twitterHandle} role=${x.role?.rawValue} restr=${x.userRestrictions?.rawValue} followers=${x.followers} blockedUsers=${x.blockedUsers.size} moderates=${x.moderates.size}")
            } catch (e: HotMicError) { Hem96.log("N5 fetchUser $label → ${e.label()}") }
        }
        s("live", config.liveStreamId)
        if (config.vodStreamId.isNotEmpty()) s("vod", config.vodStreamId)
        s("bogus", "bogus-stream-id")
        s("empty", "")
        u("self", config.testUserId)
        val owner = ext("HEM96_OWNER_USER_ID"); if (owner.isNotEmpty()) u("owner", owner)
        u("bogus", "bogus-user-id")
        u("empty", "")
        // Compare list summary vs fetchStream for the same id (iOS F12: list rows lack playback URLs).
        val page = c.fetchStreams(HotMicStreamQuery(includesScheduled = false, includesVOD = false, limit = 50))
        val row = page.streams.firstOrNull { it.id == config.liveStreamId }
        Hem96.log("N5 list-row live: present=${row != null} hls=${row?.hlsUrl != null} vod=${row?.vodUrl != null} thumb=${row?.thumbnail != null}")
        Hem96.log("ROW N5 INFO see lines above")
    }

    // ---------------------------------------------------------------- N6
    private suspend fun n6StartLatency() {
        val c = client()
        val samples = mutableListOf<Long>()
        repeat(3) { i ->
            val (rec, snap) = startRecorded(c, config.liveStreamId, "N6[$i]")
            val t0 = System.currentTimeMillis()
            samples += (rec.events.firstOrNull { it.second is HotMicStreamSession.Event.ConnectionChanged }?.first ?: t0) - (rec.events.firstOrNull()?.first ?: t0)
            delay(4_000)
            Hem96.log("N6[$i] snapshot stream=${snap.stream.state.rawValue} title=${snap.stream.title} hls=${snap.stream.hlsUrl != null} user=${snap.user.displayName} chats=${snap.chatMessages.size} polls=${snap.polls.size} host=${snap.participants.host.name} cohosts=${snap.participants.cohosts.size} guests=${snap.participants.guests.size} waiting=${snap.participants.waiting.size} room=${snap.participants.room.size}")
            rec.session.stop()
            delay(500)
            Hem96.log("N6[$i] events=${rec.events.map { it.second.label() }}")
            rec.close()
        }
        Hem96.log("ROW N6 PASS start_ms reported per iteration above")
    }

    // ---------------------------------------------------------------- N8
    private suspend fun n8Chat() {
        val c = client()
        val (rec, snap) = startRecorded(c, config.liveStreamId, "N8", verbose = false)
        val chat = rec.session.chat
        suspend fun t(label: String, block: suspend () -> String) {
            try { Hem96.log("N8 $label → ok ${block()}") } catch (e: HotMicError) { Hem96.log("N8 $label → ${e.label()}") }
        }
        var own: HotMicChatMessage? = null
        t("send") { chat.sendChatMessage("N8 hello ${Hem96.nowIso()}").also { own = it }.let { "id=${it.id.take(8)} user=${it.userId.take(8)} name=${it.userName} created=${it.createdAt} device=${it.deviceId?.take(8)}" } }
        t("send-empty") { chat.sendChatMessage("").id.take(8) }
        t("send-spaces") { chat.sendChatMessage("   ").id.take(8) }
        t("send-emoji") { chat.sendChatMessage("🔥🎉 émoji ✓").id.take(8) }
        t("send-2000") { chat.sendChatMessage("x".repeat(2000)).id.take(8) }
        t("send-10000") { chat.sendChatMessage("y".repeat(10000)).id.take(8) }
        val ownId = own?.id ?: return
        val types = listOf(
            HotMicChatMessage.Reaction.ReactionType.Like, HotMicChatMessage.Reaction.ReactionType.Fire,
            HotMicChatMessage.Reaction.ReactionType.Laugh, HotMicChatMessage.Reaction.ReactionType.Anger,
            HotMicChatMessage.Reaction.ReactionType.Sadness,
        )
        for (type in types) t("add-${type.rawValue}") { chat.addChatMessageReaction(type, ownId).type.rawValue }
        t("add-like-twice") { chat.addChatMessageReaction(types[0], ownId).type.rawValue }
        t("fetch-reactions") { chat.fetchChatMessageReactions(ownId).joinToString { "${it.type.rawValue}:${it.user.id.take(8)}" } }
        t("remove-like") { chat.removeChatMessageReaction(types[0], ownId).type.rawValue }
        t("remove-like-twice") { chat.removeChatMessageReaction(types[0], ownId).type.rawValue }
        t("add-bogus-chat-id") { chat.addChatMessageReaction(types[0], "bogus-chat-id").type.rawValue }
        t("add-type-banana") { chat.addChatMessageReaction(HotMicChatMessage.Reaction.ReactionType("banana"), ownId).type.rawValue }
        t("report-own") { chat.reportChatMessage(ownId); "reported" }
        t("report-bogus") { chat.reportChatMessage("bogus-chat-id"); "reported" }
        val other = snap.chatMessages.lastOrNull { it.userId != snap.user.id }
        if (other != null) {
            t("report-other ${other.id.take(8)}") { chat.reportChatMessage(other.id); "reported" }
            t("react-other") { chat.addChatMessageReaction(types[1], other.id).type.rawValue }
            t("unreact-other") { chat.removeChatMessageReaction(types[1], other.id).type.rawValue }
            if (ext("HEM96_DELETE_OTHER") == "1") t("delete-other ${other.id.take(8)} by ${other.userId.take(8)}") { chat.deleteChatMessage(other.id); "deleted" }
        } else Hem96.log("N8 no other-user message in snapshot; skipping other-user ops")
        t("delete-own") { chat.deleteChatMessage(ownId); "deleted" }
        t("delete-own-twice") { chat.deleteChatMessage(ownId); "deleted" }
        delay(8_000)
        val echoed = rec.chatIdsSeen.containsKey(ownId)
        val deletedEvent = rec.count { it is HotMicStreamSession.Event.ChatMessageDeleted && it.id == ownId }
        Hem96.log("N8 own message echoed via events=$echoed ownDeleteEvent=$deletedEvent reactionDeletedEvents=${rec.count { it is HotMicStreamSession.Event.ChatMessageReactionDeleted }} batches=${rec.count { it is HotMicStreamSession.Event.ChatBatchReceived }}")
        rec.session.stop(); rec.close()
        Hem96.log("ROW N8 INFO see lines above")
    }

    // ---------------------------------------------------------------- N10
    private suspend fun n10Polls() {
        val c = client()
        val (rec, snap) = startRecorded(c, config.liveStreamId, "N10")
        snap.polls.forEach { p ->
            Hem96.log("N10 poll id=${p.id.take(8)} q=${p.question} closed=${p.closed} my=${p.myAnswer?.take(8)} responses=${p.responses} logo=${p.logo != null} sponsor=${p.sponsorUrl != null} correct=${p.correctAnswers.size} options=${p.options.map { "${it.answer}(${it.responses},pts=${it.points},pics=${it.profilePics.size})" }}")
        }
        val polls = rec.session.polls
        suspend fun t(label: String, block: suspend () -> Unit) {
            try { block(); Hem96.log("N10 $label → ok") } catch (e: HotMicError) { Hem96.log("N10 $label → ${e.label()}") }
        }
        val open = snap.polls.firstOrNull { !it.closed && it.options.size >= 2 }
        if (open != null) {
            t("submit first option") { polls.submitResponse(open.id, open.options[0].id) }
            t("submit second option (change)") { polls.submitResponse(open.id, open.options[1].id) }
            t("submit bogus option") { polls.submitResponse(open.id, "bogus-option") }
        } else Hem96.log("N10 no open poll with ≥2 options")
        val closed = snap.polls.firstOrNull { it.closed && it.options.isNotEmpty() }
        if (closed != null) t("submit on closed poll") { polls.submitResponse(closed.id, closed.options[0].id) }
        t("submit bogus poll") { polls.submitResponse("bogus-poll", "bogus-option") }
        delay(8_000)
        Hem96.log("N10 events pollCreated=${rec.count { it is HotMicStreamSession.Event.PollCreated }} pollUpdated=${rec.count { it is HotMicStreamSession.Event.PollUpdated }} pollDeleted=${rec.count { it is HotMicStreamSession.Event.PollDeleted }}")
        rec.session.stop(); rec.close()
        Hem96.log("ROW N10 INFO see lines above")
    }

    // ---------------------------------------------------------------- N11
    private suspend fun n11Participants() {
        val c = client()
        val (rec, snap) = startRecorded(c, config.liveStreamId, "N11")
        Hem96.log("N11 snapshot host=${snap.participants.host.id.take(8)}/${snap.participants.host.name} cohosts=${snap.participants.cohosts.size} guests=${snap.participants.guests.size} waiting=${snap.participants.waiting.size} room=${snap.participants.room.map { "${it.id.take(8)}/${it.name}" }}")
        val wait = extInt("HEM96_HOLD_SECONDS", 150)
        Hem96.log("N11 waiting ${wait}s for ParticipantsUpdated (client B should join now)")
        val t0 = System.currentTimeMillis()
        withTimeoutOrNull(wait * 1000L) {
            while (rec.count { it is HotMicStreamSession.Event.ParticipantsUpdated } == 0) delay(1_000)
        }
        val n = rec.count { it is HotMicStreamSession.Event.ParticipantsUpdated }
        Hem96.log("ROW N11 ${if (n > 0) "PASS" else "FAIL"} participantsUpdated=$n after ${(System.currentTimeMillis() - t0) / 1000}s")
        rec.session.stop(); rec.close()
    }

    // ---------------------------------------------------------------- N12
    private suspend fun n12Block() {
        val c = client()
        val (rec, snap) = startRecorded(c, config.liveStreamId, "N12")
        val s = rec.session
        suspend fun t(label: String, block: suspend () -> Unit) {
            try { block(); Hem96.log("N12 $label → ok") } catch (e: HotMicError) { Hem96.log("N12 $label → ${e.label()}") }
        }
        val b = config.clientBUserId
        if (b.isNotEmpty()) {
            t("blockUser(B)") { s.blockUser(b) }
            Hem96.log("N12 blocked B; holding ${extInt("HEM96_HOLD_SECONDS", 40)}s — B messages must NOT arrive")
            val before = rec.chatIdsSeen.size
            delay(extInt("HEM96_HOLD_SECONDS", 40) * 1000L)
            val fromB = rec.events.count { (_, e) -> e is HotMicStreamSession.Event.ChatBatchReceived && e.batch.messages.any { it.userId == b } }
            Hem96.log("N12 while blocked: batches with B messages=$fromB (new ids ${rec.chatIdsSeen.size - before})")
            t("unblockUser(B)") { s.unblockUser(b) }
            delay(20_000)
            val after = rec.events.count { (ts, e) -> e is HotMicStreamSession.Event.ChatBatchReceived && e.batch.messages.any { it.userId == b } && ts > System.currentTimeMillis() - 20_000 }
            Hem96.log("N12 after unblock: batches with B messages in last 20s=$after")
            val me = c.fetchUser(snap.user.id)
            Hem96.log("N12 fetchUser(me).blockedUsers=${me.blockedUsers.size}")
        } else Hem96.log("N12 HEM96_CLIENT_B_USER_ID unset — skipping B block")
        t("blockUser(self)") { s.blockUser(snap.user.id) }
        t("blockUser(bogus)") { s.blockUser("bogus-user-id") }
        t("unblockUser(bogus)") { s.unblockUser("bogus-user-id") }
        t("makeUserModerator(B) as viewer") { s.makeUserModerator(b.ifEmpty { "bogus-user-id" }) }
        t("blockUserFromStreamChat(B) as viewer") { s.blockUserFromStreamChat(b.ifEmpty { "bogus-user-id" }) }
        s.stop(); rec.close()
        Hem96.log("ROW N12 INFO see lines above")
    }

    // ---------------------------------------------------------------- N15
    private suspend fun n15BadCredentials() {
        suspend fun probe(label: String, c: HotMicClient) {
            suspend fun t(op: String, block: suspend () -> String) {
                try { Hem96.log("N15 $label $op → ok ${block()}") } catch (e: HotMicError) { Hem96.log("N15 $label $op → ${e.label()}") }
            }
            t("fetchStreams") { c.fetchStreams(HotMicStreamQuery(limit = 1)).streams.size.toString() }
            t("fetchStream") { c.fetchStream(config.liveStreamId).state.rawValue }
            t("fetchUser") { c.fetchUser(config.testUserId).displayName.toString() }
            t("session.start") {
                val s = c.makeStreamSession(config.liveStreamId)
                val snap = s.start()
                val r = "user=${snap.user.id.take(8)}/${snap.user.displayName} restr=${snap.user.userRestrictions?.rawValue} state=${s.state.value.label()}"
                try { s.chat.sendChatMessage("N15 $label"); Hem96.log("N15 $label chat → ok") } catch (e: HotMicError) { Hem96.log("N15 $label chat → ${e.label()}") }
                s.stop(); r
            }
        }
        probe("wrong-key", client(apiKey = "wrong-api-key"))
        probe("empty-key", client(apiKey = ""))
        ext("HEM96_EXPIRED_TOKEN").takeIf { it.isNotEmpty() }?.let { probe("expired-jwt", client(token = it)) }
        probe("malformed-jwt", client(token = "not.a.jwt"))
        probe("empty-jwt", client(token = ""))
        ext("HEM96_WRONG_SECRET_TOKEN").takeIf { it.isNotEmpty() }?.let { probe("wrong-secret-jwt", client(token = it)) }
        Hem96.log("ROW N15 INFO see lines above (no crash)")
    }

    private suspend fun n15MidSessionExpiry() {
        val token = ext("HEM96_SHORT_TOKEN")
        if (token.isEmpty()) { Hem96.log("ROW N15MID SKIP HEM96_SHORT_TOKEN unset"); return }
        val hold = extInt("HEM96_HOLD_SECONDS", 200)
        val c = client(token = token)
        val (rec, _) = startRecorded(c, config.liveStreamId, "N15MID")
        val t0 = System.currentTimeMillis()
        var chatAfter = "not tried"
        while (System.currentTimeMillis() - t0 < hold * 1000L) {
            delay(30_000)
            val t = (System.currentTimeMillis() - t0) / 1000
            Hem96.log("N15MID t=${t}s state=${rec.session.state.value.label()} connectionEvents=${rec.count { it is HotMicStreamSession.Event.ConnectionChanged }} reconnecting=${rec.count { it is HotMicStreamSession.Event.ConnectionChanged && it.connection is HotMicStreamSession.ConnectionState.Reconnecting }}")
            if (t >= 150 && chatAfter == "not tried") {
                chatAfter = try { rec.session.chat.sendChatMessage("N15MID after expiry").id.take(8) } catch (e: HotMicError) { e.label() }
                Hem96.log("N15MID chat at t=${t}s → $chatAfter")
            }
        }
        Hem96.log("ROW N15MID INFO final state=${rec.session.state.value.label()} events=${rec.events.map { it.second.label() }.filter { !it.startsWith("PollUpdated") && !it.startsWith("ChatBatch") }}")
        rec.session.stop(); rec.close()
    }

    // ---------------------------------------------------------------- N16
    private suspend fun n16Rotation() {
        val rotated = ext("HEM96_ROTATED_TOKEN")
        if (rotated.isEmpty()) { Hem96.log("ROW N16 SKIP HEM96_ROTATED_TOKEN unset"); return }
        val old = client()
        val (rec1, _) = startRecorded(old, config.liveStreamId, "N16-old")
        delay(3_000)
        rec1.session.stop(); rec1.close()
        Hem96.log("N16 old session stopped state=${rec1.session.state.value.label()}")
        val fresh = client(token = rotated)
        val (rec2, snap) = startRecorded(fresh, config.liveStreamId, "N16-new")
        val chat = try { rec2.session.chat.sendChatMessage("N16 rotated hello").id.take(8) } catch (e: HotMicError) { e.label() }
        val oldList = try { old.fetchStreams(HotMicStreamQuery(limit = 1)).streams.size.toString() } catch (e: HotMicError) { e.label() }
        Hem96.log("ROW N16 ${if (snap.user.displayName != null && !chat.contains("(")) "PASS" else "FAIL"} newUser=${snap.user.displayName} chat=$chat oldClientFetchStreams=$oldList")
        rec2.session.stop(); rec2.close()
    }

    // ---------------------------------------------------------------- N17
    private suspend fun n17Lifecycle() {
        val c = client()
        val results = mutableListOf<String>()
        // stop → start
        run {
            val s = c.makeStreamSession(config.liveStreamId); s.start(); s.stop()
            results += "start-after-stop=" + try { s.start(); "succeeded" } catch (e: HotMicError) { e.label() }
            s.stop(); results += "stop-twice=ok state=${s.state.value.label()}"
        }
        // concurrent starts
        run {
            val s = c.makeStreamSession(config.liveStreamId)
            val a = scope.async { try { s.start(); "ok" } catch (e: HotMicError) { e.label() } }
            val b = scope.async { try { s.start(); "ok" } catch (e: HotMicError) { e.label() } }
            results += "concurrent-start=${a.await()}/${b.await()} state=${s.state.value.label()}"
            s.stop()
        }
        // stop during starting
        run {
            val s = c.makeStreamSession(config.liveStreamId)
            val a = scope.async { try { s.start(); "ok" } catch (e: HotMicError) { e.label() } }
            delay(50); s.stop()
            results += "stop-during-starting: start=${a.await()} state=${s.state.value.label()}"
        }
        // cancel the start job
        run {
            val s = c.makeStreamSession(config.liveStreamId)
            val job = scope.launch { try { s.start(); Hem96.log("N17 cancelled start returned ok") } catch (e: HotMicError) { Hem96.log("N17 cancelled start → ${e.label()}") } }
            delay(50); job.cancel(); delay(1_000)
            results += "cancel-start-job: state=${s.state.value.label()}"
            s.stop()
        }
        // release without stop
        run {
            var s: HotMicStreamSession? = c.makeStreamSession(config.liveStreamId)
            s!!.start()
            val weak = WeakReference(s)
            s = null
            var collected = false
            repeat(10) { if (!collected) { System.gc(); Runtime.getRuntime().gc(); delay(1_000); collected = weak.get() == null } }
            results += "release-without-stop: collected=$collected (polling coroutines hold the controller; see notes)"
        }
        results.forEach { Hem96.log("N17 $it") }
        Hem96.log("ROW N17 INFO see lines above")
    }

    // ---------------------------------------------------------------- N19
    private suspend fun n19MemoryCycles() {
        val c = client()
        fun pss(): Int { val mi = Debug.MemoryInfo(); Debug.getMemoryInfo(mi); return mi.totalPss }
        fun heap() = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024
        System.gc(); delay(500)
        Hem96.log("N19 before pss_kb=${pss()} javaHeap_kb=${heap()} native_kb=${Debug.getNativeHeapAllocatedSize() / 1024} threads=${Thread.activeCount()}")
        repeat(10) { i ->
            val s = c.makeStreamSession(config.liveStreamId)
            val job = scope.launch { s.events.collect { } }
            s.start(); delay(5_000); s.stop(); job.cancel()
            System.gc(); delay(1_000)
            Hem96.log("N19 cycle=${i + 1} pss_kb=${pss()} javaHeap_kb=${heap()} native_kb=${Debug.getNativeHeapAllocatedSize() / 1024} threads=${Thread.activeCount()}")
        }
        delay(5_000); System.gc(); delay(1_000)
        Hem96.log("N19 after pss_kb=${pss()} javaHeap_kb=${heap()} native_kb=${Debug.getNativeHeapAllocatedSize() / 1024} threads=${Thread.activeCount()}")
        Hem96.log("ROW N19 INFO compare cycle 2..10 growth; dumpsys meminfo captured from the host")
    }

    // ---------------------------------------------------------------- N20 / HOLD
    private suspend fun n20Soak() = holdSession("N20", extInt("HEM96_SOAK_MIN", 30) * 60, sample = 30)
    private suspend fun hold() = holdSession("HOLD", extInt("HEM96_HOLD_SECONDS", 120), sample = 10)

    private suspend fun holdSession(tag: String, seconds: Int, sample: Int) {
        val c = client()
        val (rec, _) = startRecorded(c, ext("HEM96_STREAM_ID").ifEmpty { config.liveStreamId }, tag, verbose = tag != "N20" || ext("HEM96_VERBOSE") == "1")
        val sendEvery = extInt("HEM96_SEND_EVERY", 0)
        var sent = 0
        val t0 = System.currentTimeMillis()
        var lastSend = t0
        var everDisconnected = false
        while (System.currentTimeMillis() - t0 < seconds * 1000L) {
            delay(sample * 1000L)
            val t = (System.currentTimeMillis() - t0) / 1000
            val conn = rec.events.lastOrNull { it.second is HotMicStreamSession.Event.ConnectionChanged }?.second as? HotMicStreamSession.Event.ConnectionChanged
            if (conn?.connection == HotMicStreamSession.ConnectionState.Disconnected) everDisconnected = true
            val mi = Debug.MemoryInfo(); Debug.getMemoryInfo(mi)
            Hem96.log("$tag t=${t}s state=${rec.session.state.value.label()} conn=${conn?.connection?.label()} events=${rec.events.size} reconnecting=${rec.count { it is HotMicStreamSession.Event.ConnectionChanged && it.connection is HotMicStreamSession.ConnectionState.Reconnecting }} batches=${rec.count { it is HotMicStreamSession.Event.ChatBatchReceived }} chats=${rec.chatIdsSeen.size} dup=${rec.duplicates} streamUpdated=${rec.count { it is HotMicStreamSession.Event.StreamUpdated }} participants=${rec.count { it is HotMicStreamSession.Event.ParticipantsUpdated }} polls=${rec.count { it is HotMicStreamSession.Event.PollUpdated || it is HotMicStreamSession.Event.PollCreated || it is HotMicStreamSession.Event.PollDeleted }} pss_kb=${mi.totalPss}")
            if (sendEvery > 0 && System.currentTimeMillis() - lastSend >= sendEvery * 1000L && rec.session.state.value == HotMicStreamSession.State.Active) {
                lastSend = System.currentTimeMillis()
                try {
                    val m = rec.session.chat.sendChatMessage("A#${sent + 1} ${Hem96.nowIso()}")
                    sent++; Hem96.log("$tag SENT id=${m.id} n=$sent created=${m.createdAt}")
                } catch (e: HotMicError) { Hem96.log("$tag SENT-FAIL ${e.label()}") }
            }
        }
        val reconnects = rec.count { it is HotMicStreamSession.Event.ConnectionChanged && it.connection is HotMicStreamSession.ConnectionState.Reconnecting }
        Hem96.log("ROW $tag ${if (rec.session.state.value == HotMicStreamSession.State.Active && !everDisconnected) "PASS" else "FAIL"} seconds=$seconds finalState=${rec.session.state.value.label()} everDisconnected=$everDisconnected reconnectingEvents=$reconnects chats=${rec.chatIdsSeen.size} dup=${rec.duplicates} sent=$sent events=${rec.events.size}")
        rec.session.stop(); rec.close()
    }

    // ---------------------------------------------------------------- N21
    private suspend fun n21EventListener() {
        val c = client(withContext = false) // the Context-free constructor, README "Create a Client"
        val session = c.makeStreamSession(config.liveStreamId)
        val seen = mutableListOf<String>()
        session.eventListener = { event -> seen += event.label() }
        val snap = session.start()
        delay(6_000)
        session.stop()
        delay(500)
        Hem96.log("ROW N21 ${if (seen.any { it.startsWith("StateChanged(Active") } && seen.any { it.startsWith("StateChanged(Stopped") }) "PASS" else "FAIL"} user=${snap.user.displayName} listenerEvents=${seen.size} kinds=${seen.map { it.substringBefore('(') }.distinct()} listenerAfterStop=${session.eventListener == null}")
    }

    // ---------------------------------------------------------------- N24
    private suspend fun n24FieldCoverage() {
        val c = client()
        val (rec, snap) = startRecorded(c, config.liveStreamId, "N24", verbose = false)
        delay(10_000)
        val objects = mutableListOf<Any>(snap.stream, snap.user, snap.participants, snap.participants.host)
        objects.addAll(snap.chatMessages); objects.addAll(snap.polls); objects.addAll(snap.polls.flatMap { it.options })
        objects.addAll(snap.participants.room)
        rec.events.forEach { (_, e) -> when (e) {
            is HotMicStreamSession.Event.ChatBatchReceived -> { objects.addAll(e.batch.messages); objects.addAll(e.batch.reactions) }
            is HotMicStreamSession.Event.StreamUpdated -> objects += e.stream
            is HotMicStreamSession.Event.PollUpdated -> objects += e.poll
            else -> Unit
        } }
        objects.addAll(c.fetchStreams(HotMicStreamQuery(limit = 20)).let { listOf<Any>(it.pagination) + it.streams })
        objects += c.fetchUser(snap.user.id)
        FieldCoverage.report(objects).forEach { Hem96.log("N24 $it") }
        rec.session.stop(); rec.close()
        Hem96.log("ROW N24 INFO coverage lines above")
    }
}

/** Reflection-based field population report (Android mirror of the iOS Mirror-based `FieldCoverage`). */
object FieldCoverage {
    fun report(objects: List<Any>): List<String> {
        val populated = mutableMapOf<String, MutableSet<String>>()
        val all = mutableMapOf<String, MutableSet<String>>()
        for (obj in objects) {
            val type = obj.javaClass.name.removePrefix("io.hotmic.core.models.").removePrefix("io.hotmic.core.")
            if (!obj.javaClass.name.startsWith("io.hotmic.core")) continue
            for (m in obj.javaClass.methods) {
                if (m.parameterCount != 0 || !m.name.startsWith("get") || m.name == "getClass" || m.name.startsWith("component")) continue
                val field = m.name.removePrefix("get").replaceFirstChar { it.lowercase() }.substringBefore('-')
                all.getOrPut(type) { mutableSetOf() } += field
                val value = runCatching { m.invoke(obj) }.getOrNull()
                val isSet = when (value) {
                    null -> false
                    is String -> value.isNotEmpty()
                    is Collection<*> -> value.isNotEmpty()
                    is Number -> value.toDouble() != 0.0
                    is Boolean -> value
                    else -> true
                }
                if (isSet) populated.getOrPut(type) { mutableSetOf() } += field
            }
        }
        return all.keys.sorted().map { type ->
            val missing = all[type]!! - populated[type].orEmpty()
            "$type populated=${populated[type].orEmpty().sorted()} never=${missing.sorted()}"
        }
    }
}
