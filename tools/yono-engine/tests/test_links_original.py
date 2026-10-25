"""Share URL recognition is a pure parser; transport and audio remain validated."""
import unittest
from unittest.mock import patch, Mock
from yono_core.inputs import normalize_link, entry, youtube_target, spotify_playlist
from yono_core.control import Control
from yono_core.sources import Sources
from yono_core.catalogue import Catalogue
from yono_core.model import Song, EngineError

class ShareLinksTests(unittest.TestCase):
    def test_share_caption_markdown_and_schemeless(self):
        for value in ['Dengarkan https://youtu.be/abcdefghijk?si=abc.', '[Lagu](https://youtu.be/abcdefghijk?si=abc)', 'youtu.be/abcdefghijk?si=abc', 'www.youtube.com/watch?v=abcdefghijk&amp;si=abc', 'https://music.youtube.com/watch?v=abcdefghijk#t=10']:
            with self.subTest(value=value):
                link=entry(value)['url']
                self.assertEqual(youtube_target(link),('track','abcdefghijk'))
    def test_live_embed_shorts_v_routes(self):
        for path in ['live','embed','shorts','v']:
            self.assertEqual(youtube_target(normalize_link('https://www.youtube.com/'+path+'/abcdefghijk?feature=share')),('track','abcdefghijk'))
    def test_attribution_wrapper(self):
        link=normalize_link('https://www.youtube.com/attribution_link?u=%2Fwatch%3Fv%3Dabcdefghijk%26feature%3Dshare')
        self.assertEqual(youtube_target(link),('track','abcdefghijk'))
        with self.assertRaises(EngineError):normalize_link('https://youtube.com/attribution_link?u=%2F%2Fevil.test')
    def test_signed_audio_query_and_balanced_filename_unchanged(self):
        url='https://example.test/song_(live).opus?sig=a%2Bb%3D&expires=42'
        self.assertEqual(normalize_link(url),url)
    def test_multiple_links_not_silently_dropped(self):
        self.assertIsNone(normalize_link('https://youtu.be/abcdefghijk https://youtu.be/12345678901'))
    def test_fake_host_is_not_youtube(self):
        self.assertIsNone(youtube_target(normalize_link('https://youtube.com.attacker.test/watch?v=abcdefghijk')))
        with self.assertRaises(EngineError):normalize_link('https://user:pass@youtube.com/watch?v=abcdefghijk')
    def test_spotify_embed_locale_and_uri(self):
        identity='A'*22
        for link in ['https://open.spotify.com/embed/playlist/'+identity, 'https://open.spotify.com/intl-id/playlist/'+identity, 'spotify:playlist:'+identity]:
            self.assertEqual(spotify_playlist(normalize_link(link)),identity)
    def test_explicit_webm_link_uses_original_source_not_catalogue_search(self):
        token=Control('test');catalogue=Catalogue(token,client=Mock())
        with patch('yono_core.network.public_https',return_value='https://example.test/own.webm'):
            song,result=catalogue.resolve(Song('Owned',['Creator'],source_url='https://example.test/own.webm'))
        self.assertTrue(result['direct']);catalogue.client.search.assert_not_called()
    def test_extless_explicit_audio_retains_same_source(self):
        token=Control('test');catalogue=Catalogue(token,client=Mock())
        song=Song('Owned',['Creator'],source_url='https://example.test/download?id=123',direct_audio=True)
        with patch('yono_core.network.public_https'):
            self.assertIs(catalogue.resolve(song)[0],song)
        catalogue.client.search.assert_not_called()
    def test_text_list_rows_recognize_embedded_links(self):
        from yono_core.inputs import parse
        rows=parse('1. Lihat https://youtu.be/abcdefghijk\n2. youtu.be/12345678901')['entries']
        self.assertEqual(len(rows),2)
        self.assertTrue(all('url' in r for r in rows))

    def test_spotify_short_link_routes_to_authorized_metadata_reader(self):
        source=Sources(Mock(),Control('test'))
        response=Mock(status_code=200,url='https://open.spotify.com/playlist/'+'A'*22+'?si=fixture')
        with patch('yono_core.sources.session') as factory:
            factory.return_value.__enter__.return_value.head.return_value=response
            with self.assertRaises(EngineError) as error:
                source.load({'kind':'auto','content':'https://spotify.link/fixture'})
            self.assertEqual(error.exception.code,'SPOTIFY_AUTH_REQUIRED')
            factory.return_value.__enter__.return_value.head.assert_called_once_with('https://spotify.link/fixture',allow_redirects=True)
    def test_extless_audio_metadata_is_not_swapped_for_a_catalogue_recording(self):
        source=Sources(Mock(),Control('test'))
        record={'title':'Owned recording','artist':'Creator','ext':'webm','vcodec':'none','duration':5,'webpage_url':'https://example.test/play?id=owned','thumbnail':'https://example.test/cover.png'}
        with patch('yono_core.sources.public_https'),patch('yono_core.sources.youtube_dl') as factory:
            factory.return_value.__enter__.return_value.extract_info.return_value=record
            result=source.external('https://example.test/play?id=owned')
        song=result['entries'][0]['song']
        self.assertTrue(song['direct_audio'])
        self.assertEqual(song['source_url'],record['webpage_url'])
        self.assertEqual(song['artwork_url'],record['thumbnail'])

    def test_signed_query_does_not_decode_arbitrary_html_entities(self):
        for url in ['https://example.test/audio?sig=A&not=literal&copy=literal','https://example.test/audio?sig=A;','https://example.test/audio?sig=A.']:
            self.assertEqual(normalize_link(url),url)
    def test_invalid_port_returns_actionable_link_error(self):
        with self.assertRaises(EngineError) as error:
            normalize_link('https://youtube.com:invalid/watch?v=abcdefghijk')
        self.assertEqual(error.exception.code,'INVALID_LINK')

    def test_external_video_metadata_never_falls_back_to_another_recording(self):
        source=Sources(Mock(),Control('test'))
        record={'title':'Artist - Requested live version','artist':'Artist','vcodec':'avc1','formats':[{'vcodec':'avc1'}]}
        with patch('yono_core.sources.public_https'),patch('yono_core.sources.youtube_dl') as factory:
            factory.return_value.__enter__.return_value.extract_info.return_value=record
            result=source.external('https://example.test/video')
        row=result['entries'][0]
        self.assertEqual(row['code'],'SOURCE_AUDIO_METADATA')
        self.assertNotIn('song',row)
