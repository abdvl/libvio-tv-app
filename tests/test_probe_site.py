import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("probe", Path(__file__).parents[1] / "scripts/probe_site.py")
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)
HOST = "https://www.libvio.lat"


class ContractTest(unittest.TestCase):
    def test_discovery_scoped_to_current_site_section(self):
        html = '<a href="https://www.libvio.old">old</a><h2>目前可用地址</h2>'
        html += '<a href="https://www.libvio.new">new</a><a href="https://www.libvio.new/">duplicate</a>'
        html += '<a href="javascript:alert(1)">ad</a><a href="https://libvio.lat.evil.test">ad</a>'
        html += '<a href="https://www.libvio.lat@evil.test">ad</a><a href="http://www.libvio.bad">bad</a>'
        html += '<h2>其他链接</h2><a href="https://www.libvio.other">other</a>'
        self.assertEqual(probe.discover_hosts(html), ["https://www.libvio.new"])

    def test_discovery_failure_is_explicit(self):
        with self.assertRaises(ValueError):
            probe.discover_hosts('<h1>Just a moment</h1>')

    def test_catalog_deduplicates_and_uses_observed_next_link(self):
        card = '<a class="stui-vodlist__thumb" href="/detail/5811477.html" title="样本 &amp; 标题"></a>'
        html = card + card + '<a href="/show/1--------2---.html">下一页</a>'
        data = probe.parse_catalog(html, HOST)
        self.assertEqual(len(data["cards"]), 1)
        self.assertEqual(data["cards"][0]["title"], "样本 & 标题")
        self.assertEqual(data["next_path"], "/show/1--------2---.html")

    def test_cross_origin_cards_are_rejected(self):
        with self.assertRaises(ValueError):
            probe.parse_catalog('<a class="stui-vodlist__thumb" href="https://evil.test/detail/1.html" title="ad"></a>', HOST)

    def test_source_order_is_not_sid_order_and_downloads_are_distinct(self):
        html = '<h1>样本剧集</h1><div class="stui-vodlist__head"><h3>HD7播放</h3></div>'
        html += '<ul class="stui-content__playlist"><li><a href="/play/5813548-4-1.html">第01集</a></li></ul>'
        html += '<div class="stui-vodlist__head"><h3>视频下载 (夸克)</h3></div>'
        html += '<ul class="stui-content__playlist"><li><a href="/play/5813548-1-1.html">合集</a></li></ul>'
        data = probe.parse_detail(html, HOST)
        self.assertEqual([s["sid"] for s in data["sources"]], [4, 1])
        self.assertEqual([s["kind"] for s in data["sources"]], ["online_candidate", "external_download"])

    def test_film_label_is_not_interpreted_as_episode_number(self):
        html = '<h1>电影</h1><div class="stui-vodlist__head"><h3>BD2播放</h3></div>'
        html += '<ul class="stui-content__playlist"><li><a href="/play/5811477-1-1.html">1080p</a></li></ul>'
        episode = probe.parse_detail(html, HOST)["sources"][0]["episodes"][0]
        self.assertEqual(episode["nid"], 1)
        self.assertEqual(episode["label"], "1080p")

    def test_mixed_source_ids_are_not_silently_merged(self):
        html = '<h1>电影</h1><div class="stui-vodlist__head"><h3>HD</h3></div>'
        html += '<ul class="stui-content__playlist"><a href="/play/20-1-1.html">1</a><a href="/play/20-2-2.html">2</a></ul>'
        with self.assertRaises(ValueError):
            probe.parse_detail(html, HOST)

    def test_route_and_internal_id_are_kept_separate_and_url_is_not_logged(self):
        html = '<script>var player_aaaa={"id":"3548","sid":4,"nid":1,"from":"BBA","encrypt":3,"url":"secret-signed-url"};</script>'
        data = probe.player_summary(html)
        self.assertEqual(data["internal_id"], "3548")
        self.assertFalse(data["url_is_https"])
        self.assertNotIn("url", data)
        self.assertNotIn("secret-signed-url", str(data))

    def test_script_is_never_evaluated(self):
        with self.assertRaises(ValueError):
            probe.player_summary('<script>var player_aaaa=runCode()</script>')

    def test_source_parse_failure_is_explicit(self):
        with self.assertRaises(ValueError):
            probe.parse_detail('<h1>Cloudflare challenge</h1>', HOST)

    def test_non_https_and_credentials_are_rejected(self):
        for value in ['http://www.libvio.lat', 'https://user:password@www.libvio.lat', 'https://www.libvio.lat:8443']:
            with self.assertRaises(ValueError):
                probe.origin(value)


if __name__ == "__main__":
    unittest.main()
