import unittest
from yono_metadata import content, artwork
from yono_charts import run, entry
from yono_search import normalize


class FakeCatalogue:
    def __init__(self, data):
        self.data, self.calls = data, []

    def call(self, method, *args, **kwargs):
        self.calls.append(method)
        return self.data.get(method, {})


class ChartsMetadataTests(unittest.TestCase):
    def row(self):
        return {'videoId': 'QAMD0000001', 'title': 'Owned fixture', 'videoType': 'MUSIC_VIDEO_TYPE_OMV', 'artists': [{'name': 'QA artist'}]}

    def test_title_or_generic_verified_badge_never_proves_official(self):
        self.assertEqual('UNVERIFIED', content({'title': 'Official video', 'channel_is_verified': True})['official_status'])
        self.assertEqual('PLATFORM_OFFICIAL', content(self.row())['official_status'])

    def test_authorized_live_and_remix_are_classified_separately(self):
        self.assertEqual('LIVE', content({**self.row(), 'was_live': True})['content_kind'])
        for title, kind in [('Owned Live', 'LIVE'), ('Owned DJ Remix', 'REMIX'), ('Owned nightcore', 'ALTERED'), ('Owned cover', 'COVER')]:
            self.assertEqual(kind, content({**self.row(), 'title': title})['content_kind'])

    def test_thumbnail_is_supplied_bounded_choice_not_guessed(self):
        images = [{'url': 'https://i.ytimg.com/huge.jpg', 'width': 4000}, {'url': 'https://i.ytimg.com/small.jpg', 'width': 480}]
        self.assertEqual('https://i.ytimg.com/small.jpg', artwork(images))
        self.assertEqual('', artwork([{'url': 'http://127.0.0.1/private'}]))

    def test_daily_and_weekly_never_fall_back_to_first_unrelated_playlist(self):
        client = FakeCatalogue({'get_charts': {'videos': [{'title': 'Weekly Top Music Videos - Indonesia', 'playlistId': 'PL1234567890'}]}, 'get_playlist': {'tracks': [self.row()]}})
        self.assertEqual('unavailable', run({'category': 'daily_videos', 'country': 'ID'}, client)['status'])
        self.assertNotIn('get_playlist', client.calls)
        result = run({'category': 'weekly_videos', 'country': 'ID'}, client)
        self.assertEqual('available', result['status'])
        self.assertIsNone(result['entries'][0]['rank'])

    def test_indonesia_and_global_region_reach_source_without_fake_stats(self):
        for region in ['ID', 'ZZ']:
            client = FakeCatalogue({'get_charts': {'artists': [{'title': 'QA', 'browseId': 'UC123', 'rank': None}]}})
            result = run({'category': 'weekly_artists', 'country': region}, client)
            self.assertEqual(region, result['country']); self.assertIsNone(result['items'][0]['rank']); self.assertEqual('', result['sourceUpdated'])

    def test_aggregate_song_chart_is_not_automatically_an_official_video(self):
        result = entry({**self.row(), 'videoType': None, 'rank': '3', 'trend': 'up'})
        self.assertEqual(3, result['rank']); self.assertEqual('UNVERIFIED', result['song']['official_status'])

    def test_explore_does_not_claim_regional_chart_and_missing_data_is_unavailable(self):
        result = run({'category': 'trending', 'country': 'ID'}, FakeCatalogue({'get_explore': {'trending': {'items': [self.row()]}}}))
        self.assertEqual('', result['country']); self.assertIn('Explore', result['source'])
        self.assertEqual('unavailable', run({'category': 'weekly_shorts', 'country': 'ID'}, FakeCatalogue({}))['status'])

    def test_search_preserves_exact_identity_and_optional_fields(self):
        self.assertEqual('Supplied full title', normalize({'id': 'QAMD0000001', 'fulltitle': 'Supplied full title'})['title'])
        result = normalize({'id': 'QAMD0000001', 'title': 'Owned fixture', 'thumbnail': 'https://i.ytimg.com/t.jpg', 'channel_id': 'UCexact', 'release_year': 2024})
        self.assertEqual('UCexact', result['channel_id']); self.assertEqual('https://i.ytimg.com/t.jpg', result['artwork'])
        self.assertEqual([], result['artists']); self.assertEqual('UNVERIFIED', result['official_status'])
