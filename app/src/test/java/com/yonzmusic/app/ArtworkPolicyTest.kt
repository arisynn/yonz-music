package com.yonzmusic.app

import com.yonzmusic.app.artwork.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ArtworkPolicyTest {
    @Test fun denseCardsSelect512AndPlayerSelects1024WithoutUsingSmallVariant() {
        val images=listOf(128,512,1024,2048).map {ArtworkCandidate("https://i.ytimg.com/$it.jpg",it,it)}
        assertEquals(512,ArtworkPolicy.ordered(images,456).first().width)
        assertEquals(1024,ArtworkPolicy.ordered(images,960).first().width)
        assertEquals(1,ArtworkPolicy.sample(512,512,456))
        assertEquals(2,ArtworkPolicy.sample(2048,2048,1024))
        assertEquals(512,ArtworkPolicy.bucket(408))
    }
    @Test fun onlySuppliedArtworkIsUsedAndLowResolutionIsNotUpscaled() {
        val small=ArtworkCandidate("https://lh3.googleusercontent.com/provided",128,128)
        assertEquals(small,ArtworkPolicy.ordered(listOf(small),1024).single())
        assertEquals(1,ArtworkPolicy.sample(128,128,1024))
        assertTrue(ArtworkPolicy.ordered(listOf(ArtworkCandidate("https://i.ytimg.com.evil.test/a",2048,2048)),1024).isEmpty())
    }
    @Test fun diskCacheEvictsLeastRecentlyUsedAndClearPreservesOtherFiles() {
        val root=Files.createTempDirectory("artwork-test").toFile()
        try {
            val audio=root.resolve("song.opus").apply {writeText("audio")};var time=1000L
            val cache=ImageDiskCache(root.resolve("images"),8){++time}
            cache.put("a",byteArrayOf(1,2,3,4));cache.put("b",byteArrayOf(5,6,7,8));assertNotNull(cache.get("a"))
            cache.put("c",byteArrayOf(9,10,11,12));assertNull(cache.get("b"));assertNotNull(cache.get("a"))
            cache.clear();assertNull(cache.get("a"));assertTrue(audio.exists())
        }finally {root.deleteRecursively()}
    }
}
