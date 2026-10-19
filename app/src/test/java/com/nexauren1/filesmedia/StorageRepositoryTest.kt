package com.nexauren1.filesmedia

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageRepositoryTest {
    @Test
    fun formatSizeUsesBytesBelowOneKilobyte() {
        assertEquals("512 B", StorageRepository.formatSize(512L))
    }

    @Test
    fun formatSizeUsesKilobytesForLargerValues() {
        assertEquals("1.0 KB", StorageRepository.formatSize(1024L))
    }

    @Test
    fun formatSizeUsesMegabytesForLargeValues() {
        assertEquals("1.0 MB", StorageRepository.formatSize(1024L * 1024L))
    }
}
