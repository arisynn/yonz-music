package com.yonzmusic.app

import com.yonzmusic.app.data.*
import org.junit.Assert.*
import org.junit.Test

class MetadataAndMixTest {
 @Test fun fallbackOnlyWhenTagsAreMissing() {
  val f=MetadataPolicy.choose("Adele - All I Ask.webm",MetadataPolicy.Fields())
  assertEquals("All I Ask",f.title);assertEquals("Adele",f.artist);assertEquals("",f.album)
  val tagged=MetadataPolicy.choose("Wrong Artist - Wrong.mp3",MetadataPolicy.Fields("Right","Actual; Featured","Verified album"))
  assertEquals("Right",tagged.title);assertEquals("Actual; Featured",tagged.artist)
 }
 @Test fun sourceWinsFilenameButMissingSourceFieldsKeepGoodTags() {
  val f=MetadataPolicy.choose("file.webm",MetadataPolicy.Fields("Tagged","Real artist","Real album"),MetadataPolicy.Fields("Verified","","","source"))
  assertEquals("Verified",f.title);assertEquals("Real artist",f.artist);assertEquals("Real album",f.album)
 }
 @Test fun historicalFilenameFallbackNeverOverwritesCorrectTags() {
  val f=MetadataPolicy.choose("renamed.mp3",MetadataPolicy.Fields("Correct","Tagged artist","Album"),MetadataPolicy.Fields("Guess","Old name","","filename"))
  assertEquals("Correct",f.title);assertEquals("Tagged artist",f.artist)
 }
 @Test fun filenameMetadataSurvivesRenameWhenTagsAbsent() {
  val f=MetadataPolicy.choose("random.webm",MetadataPolicy.Fields(origin="filename"),MetadataPolicy.Fields("All I Ask","Adele","","filename"))
  assertEquals("All I Ask",f.title);assertEquals("Adele",f.artist);assertEquals("filename",f.origin)
 }
 @Test fun hashesNotNamesDecideRelocation() {
  val a=Track("old","content://old","Same",sha256="a".repeat(64));val b=Track("different","content://b","Same",sha256="b".repeat(64))
  assertEquals(listOf("old"),MetadataPolicy.relocationCandidates("a".repeat(64),listOf(a,b)))
  assertTrue(MetadataPolicy.relocationCandidates("",listOf(a,b)).isEmpty())
  assertEquals(2,MetadataPolicy.relocationCandidates("a".repeat(64),listOf(a,a.copy(id="copy"))).size)
 }
 @Test fun shortTapAndSeekDoNotBecomeAPlay() {
  val c=ListenAccumulator();assertNull(c.tick("a",240000,true,0));assertNull(c.tick("a",240000,false,1000));assertNull(c.tick("a",240000,false,900000))
 }
 @Test fun pausedTimeDoesNotCountAndQualificationOccursOnce() {
  val c=ListenAccumulator();c.tick("a",240000,true,0);assertNull(c.tick("a",240000,false,15000));assertNull(c.tick("a",240000,true,100000))
  val q=c.tick("a",240000,true,115000);assertEquals("a",q!!.trackId);assertEquals(30000,q.listenedMs);assertNull(c.tick("a",240000,true,125000))
 }
 @Test fun transitionQualifiesOldTrackWithoutCreditingNewTrack() {
  val c=ListenAccumulator();c.tick("a",240000,true,0)
  assertEquals("a",c.tick("b",200000,true,31000)!!.trackId);assertNull(c.tick("b",200000,false,32000))
 }
 @Test fun mixRequiresRealEnoughDataAndNeverChangesAlbums() {
  val tracks=(1..6).map { Track("$it","content://$it","Song $it",artist="Artist",album="",added=1) }
  assertTrue(SmartMix.build(tracks,emptySet(),emptyList(),100000).isEmpty())
  val stats=(1..3).map { ListenStats("$it",2,50000) }
  val mixes=SmartMix.build(tracks,setOf("4","5","6"),stats,100000)
  assertTrue(mixes.any { it.key=="frequent" });assertTrue(mixes.any { it.key=="favorites" });assertTrue(tracks.all { it.album=="" })
 }
 @Test fun tinyClipStillNeedsActualFullDurationAtMinimum() {
  val c=ListenAccumulator();c.tick("short",2000,true,0);assertNull(c.tick("short",2000,true,1000));assertNotNull(c.tick("short",2000,false,2000))
 }
}
