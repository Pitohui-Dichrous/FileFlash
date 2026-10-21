package app.fileflash

import org.junit.Assert.*
import org.junit.Test

class HostReceiveFeedbackTest {
    private fun upload(id: String = "one", offset: Long = 0, size: Long = 100, status: String = "uploading", known: Boolean = true) =
        UploadEntry(id = id, name = "$id.mov", offset = offset, size = size, status = status, lengthKnown = known)
    private fun file(id: String = "one", size: Long = 100) = RemoteFile(id = id, name = "$id.mov", size = size, received = true, temporary = true)
    private fun state(vararg uploads: UploadEntry, files: List<RemoteFile> = emptyList()) = ServerState(uploads = uploads.toList(), files = files)
    private fun tracker(baseline: ServerState = ServerState()) = HostReceiveFeedbackTracker().also { it.start(1, baseline, 0) }

    @Test fun recoveredBaselineDoesNotPretendToBeNewTraffic() {
        val baseline = state(upload("paused", 50), upload("complete", 100, status = "complete"), files = listOf(file("complete")))
        val tracker = tracker(baseline)
        assertNull(tracker.update(1, baseline, 900))
        assertNull(tracker.update(1, baseline, 10_000))
        assertNull(tracker.tick(1, 20_000))
    }

    @Test fun resumedBaselineStartsOnlyWhenItsOffsetOrStatusChanges() {
        val tracker = tracker(state(upload(offset = 20)))
        assertNull(tracker.update(1, state(upload(offset = 20)), 900))
        val result = tracker.update(1, state(upload(offset = 60)), 1_900)!!
        assertEquals("接收中", result.phase)
        assertEquals(60L, result.receivedBytes)
        assertEquals(40L, result.speedBytesPerSecond)
        assertTrue(result.active)
    }

    @Test fun fastCompletionIsVisibleOnceAndExpiresWithoutBeingReplayed() {
        val tracker = tracker()
        val completed = state(upload(offset = 100, status = "complete"), files = listOf(file()))
        val result = tracker.update(1, completed, 900)!!
        assertEquals("已接收", result.phase)
        assertFalse(result.active)
        assertEquals(100L, result.receivedBytes)
        assertEquals(100L, result.totalBytes)
        assertNotNull(tracker.update(1, completed, 4_899))
        assertNull(tracker.update(1, completed, 4_900))
        assertNull(tracker.update(1, completed, 9_000))
    }

    @Test fun aFastReceivedFileWithoutAnUploadRecordStillShowsCompletion() {
        val tracker = tracker(ServerState(files = listOf(file("old"))))
        val current = ServerState(files = listOf(file("old"), file("fast", 9)))
        val result = tracker.update(1, current, 900)!!
        assertEquals("fast.mov", result.name)
        assertEquals(1, result.fileCount)
        assertEquals(9L, result.receivedBytes)
        assertEquals("已接收", result.phase)
        assertNull(tracker.update(1, current, 5_000))
    }

    @Test fun unknownLengthNeverPublishesAFalseTotalOrProcessingPercentage() {
        val tracker = tracker()
        val unknown = tracker.update(1, state(upload(offset = 2_000, size = 2_000, known = false)), 900)!!
        assertNull(unknown.totalBytes)
        assertEquals("接收中", unknown.phase)
        assertEquals(2_000L, unknown.receivedBytes)
        val complete = tracker.update(1, state(upload(offset = 4_000, size = 4_000, status = "complete")), 1_800)!!
        assertEquals(4_000L, complete.totalBytes)
        assertEquals(4_000L, complete.receivedBytes)
        assertEquals("已接收", complete.phase)
    }

    @Test fun anUnchangedNativeCheckpointWaitsWithoutClaimingFailureOrPause() {
        val tracker = tracker()
        tracker.update(1, state(upload(offset = 20)), 900)
        assertEquals("接收中", tracker.update(1, state(upload(offset = 20)), 8_899)!!.phase)
        val waiting = tracker.update(1, state(upload(offset = 20)), 8_900)!!
        assertEquals("等待数据", waiting.phase)
        assertTrue(waiting.active)
        assertEquals(0L, waiting.speedBytesPerSecond)
        assertEquals("接收中", tracker.update(1, state(upload(offset = 30)), 9_800)!!.phase)
    }

    @Test fun allKnownBytesReceivedIsProcessingUntilTheServerCompletes() {
        val tracker = tracker()
        val pending = tracker.update(1, state(upload(offset = 100)), 900)!!
        assertEquals("处理中", pending.phase)
        assertTrue(pending.active)
        assertEquals("处理中", tracker.update(1, state(upload(offset = 100)), 20_000)!!.phase)
        assertEquals("已接收", tracker.update(1, state(upload(offset = 100, status = "complete")), 20_900)!!.phase)
    }

    @Test fun aDisappearingUploadWithAReceivedFileIsComplete() {
        val tracker = tracker()
        tracker.update(1, state(upload(offset = 40)), 900)
        val result = tracker.update(1, ServerState(files = listOf(file())), 1_800)!!
        assertEquals("已接收", result.phase)
        assertEquals(100L, result.receivedBytes)
        assertFalse(result.active)
        assertNull(tracker.update(1, ServerState(files = listOf(file())), 5_800))
    }

    @Test fun aDisappearingUploadWithoutAFileIsInterruptedAndExpires() {
        val tracker = tracker()
        tracker.update(1, state(upload(offset = 40)), 900)
        val result = tracker.update(1, ServerState(), 1_800)!!
        assertEquals("接收中断", result.phase)
        assertEquals(40L, result.receivedBytes)
        assertFalse(result.active)
        assertNull(tracker.tick(1, 5_800))
    }

    @Test fun failedPollingDoesNotImplyThatAnUploadDisappeared() {
        val tracker = tracker()
        tracker.update(1, state(upload(offset = 40)), 900)
        assertEquals("接收中", tracker.tick(1, 1_800)!!.phase)
        assertEquals("等待数据", tracker.tick(1, 8_900)!!.phase)
        assertEquals("接收中", tracker.update(1, state(upload(offset = 50)), 9_800)!!.phase)
    }

    @Test fun concurrentFilesAggregateKnownAndUnknownLengthsAndSpeeds() {
        val tracker = tracker()
        tracker.update(1, state(upload("a", 10, 100), upload("b", 20, 20, known = false)), 900)
        val result = tracker.update(1, state(upload("a", 60, 100), upload("b", 70, 70, known = false)), 1_900)!!
        assertEquals(2, result.fileCount)
        assertEquals(130L, result.receivedBytes)
        assertNull(result.totalBytes)
        assertEquals(100L, result.speedBytesPerSecond)
        val done = tracker.update(1, state(upload("a", 100, 100, "complete"), upload("b", 80, 80, "complete")), 2_800)!!
        assertEquals("已接收", done.phase)
        assertEquals(180L, done.totalBytes)
        assertFalse(done.active)
    }

    @Test fun byteAndSpeedAggregationCannotOverflowLong() {
        val tracker = tracker()
        tracker.update(1, state(upload("a", 0, Long.MAX_VALUE), upload("b", 0, Long.MAX_VALUE)), 900)
        val result = tracker.update(1, state(upload("a", Long.MAX_VALUE - 1, Long.MAX_VALUE), upload("b", Long.MAX_VALUE - 1, Long.MAX_VALUE)), 901)!!
        assertEquals(Long.MAX_VALUE, result.receivedBytes)
        assertEquals(Long.MAX_VALUE, result.totalBytes)
        assertEquals(Long.MAX_VALUE, result.speedBytesPerSecond)
    }

    @Test fun failureCanResumeAndCancellationDoesNotBecomeSuccessfulCompletion() {
        val tracker = tracker()
        tracker.update(1, state(upload(offset = 20)), 900)
        assertEquals("失败", tracker.update(1, state(upload(offset = 20, status = "failed")), 1_800)!!.phase)
        assertEquals("接收中", tracker.update(1, state(upload(offset = 30)), 2_700)!!.phase)
        val cancelled = tracker.update(1, state(upload(offset = 30, status = "cancelled")), 3_600)!!
        assertEquals("接收中断", cancelled.phase)
        assertFalse(cancelled.active)
        assertNull(tracker.update(1, state(upload(offset = 30, status = "cancelled")), 7_600))
    }

    @Test fun stoppingClearsFeedbackAndStaleGenerationsCannotRestoreIt() {
        val tracker = tracker()
        tracker.update(1, state(upload(offset = 40)), 900)
        assertNull(tracker.stop(2, 1_000))
        assertNull(tracker.update(1, state(upload(offset = 100, status = "complete")), 1_100))
        assertNull(tracker.update(2, state(upload(offset = 100, status = "complete")), 1_100))
        assertNull(tracker.tick(2, 2_000))
        val baseline = state(upload(offset = 40))
        tracker.start(3, baseline, 3_000)
        assertNull(tracker.update(1, state(upload("late", 100, status = "complete")), 3_100))
        assertNull(tracker.update(3, baseline, 3_900))
        assertEquals("接收中", tracker.update(3, state(upload(offset = 50)), 4_800)!!.phase)
    }

    @Test fun terminalExpiryIsNotExtendedByRepeatedSnapshotsOrPollFailures() {
        val tracker = tracker()
        tracker.update(1, state(upload(offset = 100, status = "complete")), 900)
        assertNotNull(tracker.tick(1, 4_800))
        assertNull(tracker.tick(1, 4_900))
        assertNull(tracker.update(1, state(upload(offset = 100, status = "complete")), 5_800))
    }

    @Test fun aNewUploadReplacesThePreviousInterruptedBatchImmediately() {
        val tracker = tracker()
        tracker.update(1, state(upload("old", 40)), 900)
        assertEquals("接收中断", tracker.update(1, ServerState(), 1_800)!!.phase)
        val receiving = tracker.update(1, state(upload("new", 10)), 2_000)!!
        assertEquals("接收中", receiving.phase)
        assertEquals(1, receiving.fileCount)
        assertEquals(10L, receiving.receivedBytes)
        val complete = tracker.update(1, state(upload("new", 100, status = "complete")), 2_900)!!
        assertEquals("已接收", complete.phase)
        assertEquals(1, complete.fileCount)
    }

    @Test fun resumingTheSameFailedIdBeginsANewBatchWithoutOtherOldTerminals() {
        val tracker = tracker()
        tracker.update(1, state(upload("retry", 30), upload("other", 10)), 900)
        tracker.update(1, state(upload("retry", 30, status = "failed"), upload("other", 10, status = "cancelled")), 1_800)
        val resumed = tracker.update(1, state(upload("retry", 40), upload("other", 10, status = "cancelled")), 2_000)!!
        assertEquals("接收中", resumed.phase)
        assertEquals(1, resumed.fileCount)
        assertEquals(40L, resumed.receivedBytes)
        assertEquals("已接收", tracker.update(1, state(upload("retry", 100, status = "complete"), upload("other", 10, status = "cancelled")), 2_900)!!.phase)
    }

    @Test fun aFastNewCompletionReplacesAnEarlierFailureBeforeItExpires() {
        val tracker = tracker()
        tracker.update(1, state(upload("old", 20, status = "failed")), 900)
        val complete = tracker.update(1, state(upload("old", 20, status = "failed"), upload("fast", 7, 7, "complete")), 1_100)!!
        assertEquals("已接收", complete.phase)
        assertEquals("fast.mov", complete.name)
        assertEquals(1, complete.fileCount)
        assertEquals(7L, complete.receivedBytes)
        assertEquals(7L, complete.totalBytes)
    }

    @Test fun aFailureWithinAnActuallyConcurrentBatchRemainsPartOfItsOutcome() {
        val tracker = tracker()
        tracker.update(1, state(upload("failed", 20), upload("finishing", 10)), 900)
        val active = tracker.update(1, state(upload("failed", 20, status = "failed"), upload("finishing", 50)), 1_800)!!
        assertEquals("接收中", active.phase)
        assertEquals(2, active.fileCount)
        val completed = tracker.update(1, state(upload("failed", 20, status = "failed"), upload("finishing", 100, status = "complete")), 2_700)!!
        assertEquals("失败", completed.phase)
        assertEquals(2, completed.fileCount)
        assertFalse(completed.active)
    }

    @Test fun largeSnapshotsRemainStrictlyBounded() {
        val tracker = tracker()
        val huge = ServerState(uploads = (0..5_000).map { upload("u$it") }, files = (0..5_000).map { file("f$it") })
        val result = tracker.update(1, huge, 900)!!
        assertTrue(result.fileCount <= 4_096)
        for (name in listOf("entries", "observed", "knownFiles")) {
            val field = tracker.javaClass.getDeclaredField(name).also { it.isAccessible = true }
            val value = field.get(tracker)
            val size = if (value is Map<*, *>) value.size else (value as Set<*>).size
            assertTrue("$name must be bounded", size <= 4_096)
        }
    }
}
