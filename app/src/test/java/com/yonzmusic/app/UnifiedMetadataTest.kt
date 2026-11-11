package com.yonzmusic.app

import com.yonzmusic.app.search.*
import com.yonzmusic.app.metadata.*
import com.yonzmusic.app.playback.StreamRepository
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class UnifiedMetadataTest {
    private fun song()=OnlineSong("engine:QAMD0000001",SearchProvider.ENGINE,"Owned title","Credited artist",page="https://www.youtube.com/watch?v=QAMD0000001",videoId="QAMD0000001",artistVerified=true)
    @Test fun genericChannelCannotReplaceCreditedArtistAndMissingFieldsDoNotErase() {
        val old=song().copy(album="Verified album",metadataSource=MetadataSource.CATALOG)
        val next=old.copy(title="Full title",credit="Generic channel",artistVerified=false,album="",metadataSource=MetadataSource.EXTRACTION)
        val result=MetadataPolicy.merge(old,next)
        assertEquals("Credited artist",result.credit);assertEquals("Verified album",result.album);assertEquals(old.title,result.title)
    }
    @Test fun extractionImprovesSearchWithoutChangingSourceIdentity() {
        val old=song().copy(artistVerified=false,credit="Uploader")
        val result=MetadataPolicy.merge(old,old.copy(title="Full title",artistVerified=true,credit="Real credits",album="Album",metadataSource=MetadataSource.EXTRACTION))
        assertEquals(old.id,result.id);assertEquals(old.videoId,result.videoId);assertEquals("Real credits",result.credit);assertEquals("Album",result.album)
    }
    @Test fun identityMismatchIsRejectedAndArtworkVersionClearsOldLocalImage() {
        val old=song().copy(artwork="https://i.ytimg.com/a.jpg",artworkUri="file:/cached-a.jpg")
        assertEquals("",MetadataPolicy.merge(old,old.copy(artwork="https://i.ytimg.com/b.jpg",artworkUri="")).artworkUri)
        try {MetadataPolicy.merge(old,old.copy(videoId="QAMD0000002"));fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun officialEvidenceAndVariantAreSeparateFromArtistCreditsAndTitle() {
        val unverified=song().copy(title="Official Music Video",artistVerified=true)
        assertFalse(OfficialFilter.OFFICIAL.accepts(unverified))
        val official=unverified.copy(officialStatus=OfficialStatus.PLATFORM_OFFICIAL,contentKind=ContentKind.VIDEO)
        assertTrue(OfficialFilter.VIDEO.accepts(official));assertFalse(OfficialFilter.AUDIO.accepts(official))
        for(kind in listOf(ContentKind.LIVE,ContentKind.REMIX,ContentKind.COVER,ContentKind.KARAOKE,ContentKind.ALTERED))assertFalse(OfficialFilter.OFFICIAL.accepts(official.copy(contentKind=kind)))
    }
    @Test fun persistedMetadataRoundTripsWithoutLocalArtworkOrAudioUrls() {
        val old=song().copy(albumArtist="Album credit",channelId="UCexact",releaseYear=2024,genre="Pop",officialStatus=OfficialStatus.PLATFORM_OFFICIAL,artworkUri="file:/private-cache.jpg",metadataSource=MetadataSource.EXTRACTION)
        val json=StreamRepository.encode(old);val found=StreamRepository.decode(json)
        assertEquals(old.copy(artworkUri=""),found);assertFalse(json.toString().contains("private-cache"));assertFalse(json.has("url"))
    }
    @Test fun legacyCanonicalReferencesKeepCatalogueCreditPriorityWithoutMigration() {
        val id="mb:recording:12345678-1234-1234-1234-123456789012"
        val old=JSONObject().put("id","").put("mediaId",id).put("catalog",JSONObject().put("id",id).toString()).put("title","Catalogue title").put("credit","Catalogue artist").put("artist",true).put("album","Catalogue album")
        val restored=StreamRepository.decode(old)
        assertEquals(MetadataSource.CATALOG,restored.metadataSource)
        assertEquals(ArtworkSource.CATALOG,restored.artworkSource)
        val merged=MetadataPolicy.merge(restored,restored.copy(credit="Source credit",album="Source album",metadataSource=MetadataSource.EXTRACTION))
        assertEquals("Catalogue artist",merged.credit);assertEquals("Catalogue album",merged.album)
    }
    @Test fun chartParserNeverInventsRankDateOrSourceStatistics() {
        val row=JSONObject().put("video_id","QAMD0000001").put("title","Owned fixture").put("page",song().page)
        val root=JSONObject().put("status","available").put("entries",JSONArray().put(JSONObject().put("song",row))).put("source","YouTube Music").put("sourceUrl","https://evil.test/chart")
        val page=MusicCharts.parse(root,123,true,false)
        assertNull(page.entries.single().rank);assertNull(page.entries.single().peak);assertEquals("",page.sourceUpdated)
        assertEquals("https://artists.youtube.com/charts/tracks",page.sourceUrl);assertEquals(123L,page.fetchedAt)
    }
    @Test fun unavailableSourcesHaveExplicitExternalFallbacks() {
        for(category in ChartCategory.entries.filter {it.externalOnly}) {
            val page=MusicCharts.fallback(category,"ID")
            assertFalse(page.available);assertTrue(page.entries.isEmpty());assertTrue(page.sourceUrl.startsWith("https://"));assertTrue(page.message.isNotBlank())
        }
        assertTrue(MusicCharts.fallback(ChartCategory.SEA,"ZZ").message.contains("tidak tersedia"))
        assertTrue(MusicCharts.fallback(ChartCategory.SPOTIFY_50,"MY").message.contains("tidak tersedia"))
    }
}
