#!/usr/bin/env python3
"""Small, read-only LIBVIO contract probe; this is not the Android adapter.

Uses only Python's standard library. Does not execute site JavaScript, fetch
media, log cookies, or persist signed video URLs. Run with --help for limits.
"""

import argparse
from datetime import datetime, timezone
from html.parser import HTMLParser
import json
from pathlib import Path
import re
from urllib.parse import urlencode, urljoin, urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler


class Node:
    def __init__(self, tag="root", attrs=()):
        self.tag = tag
        self.attrs = dict(attrs)
        self.children = []

    @property
    def text(self):
        return "".join(c.text if isinstance(c, Node) else c for c in self.children).strip()

    def has_class(self, name):
        return name in self.attrs.get("class", "").split()

    def all(self, tag=None, css_class=None):
        result = []
        for child in self.children:
            if isinstance(child, Node):
                if (tag is None or child.tag == tag) and (css_class is None or child.has_class(css_class)):
                    result.append(child)
                result.extend(child.all(tag, css_class))
        return result


class Document(HTMLParser):
    VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"}

    def __init__(self, html):
        super().__init__(convert_charrefs=True)
        self.root = Node()
        self.stack = [self.root]
        self.feed(html)
        self.close()

    def handle_starttag(self, tag, attrs):
        node = Node(tag, attrs)
        self.stack[-1].children.append(node)
        if tag not in self.VOID:
            self.stack.append(node)

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        if tag not in self.VOID:
            self.handle_endtag(tag)

    def handle_endtag(self, tag):
        for i in range(len(self.stack) - 1, 0, -1):
            if self.stack[i].tag == tag:
                self.stack = self.stack[:i]
                return

    def handle_data(self, data):
        self.stack[-1].children.append(data)


def origin(url):
    parts = urlsplit(url)
    if parts.scheme != "https" or not parts.hostname or parts.username or parts.password:
        raise ValueError("Only HTTPS URLs without credentials are supported")
    if parts.port not in (None, 443):
        raise ValueError("Unexpected site port")
    return "https://" + parts.hostname


def site_path(href, host, pattern):
    resolved = urljoin(host + "/", href)
    try:
        if origin(resolved) != origin(host):
            return None
    except ValueError:
        return None
    p = urlsplit(resolved)
    return p.path if not p.query and not p.fragment and re.fullmatch(pattern, p.path) else None


def discover_hosts(html):
    doc = Document(html).root
    active = False
    hosts = []
    for node in doc.all():
        if node.tag == "h2":
            active = "目前可用地址" in node.text
        if not active or node.tag != "a":
            continue
        value = node.attrs.get("href", "")
        try:
            host = origin(value)
        except ValueError:
            continue
        parts = urlsplit(value)
        if (re.fullmatch(r"(?:www\.)?libvio\.[a-z]+", parts.hostname or "")
                and parts.hostname not in {"libvio.lol", "www.libvio.lol"}
                and parts.path in {"", "/"} and not parts.query and not parts.fragment
                and host not in hosts):
            hosts.append(host)
    if not hosts:
        raise ValueError("Entry page did not contain recognized current-site links")
    return hosts


def parse_catalog(html, host):
    doc = Document(html).root
    cards = []
    seen = set()
    for a in doc.all("a", "stui-vodlist__thumb"):
        path = site_path(a.attrs.get("href", ""), host, r"/detail/\d+\.html")
        if path and path not in seen and a.attrs.get("title"):
            seen.add(path)
            cards.append({"route_id": re.search(r"\d+", path).group(), "title": a.attrs["title"], "detail_path": path})
    if not cards:
        raise ValueError("No recognized catalog cards; check for a challenge, empty results, or changed markup")
    next_paths = [site_path(a.attrs.get("href", ""), host, r"/(?:show|search)/[^/]+\.html")
                  for a in doc.all("a") if a.text == "下一页"]
    return {"cards": cards, "next_path": next((p for p in next_paths if p), None)}


def parse_detail(html, host):
    doc = Document(html).root
    headings = doc.all("h1")
    title = headings[0].text if headings else ""
    sources = []
    current_name = ""
    for node in doc.all():
        if node.has_class("stui-vodlist__head"):
            hs = node.all("h3")
            current_name = hs[0].text if hs else ""
        if node.tag != "ul" or not node.has_class("stui-content__playlist"):
            continue
        episodes = []
        for a in node.all("a"):
            path = site_path(a.attrs.get("href", ""), host, r"/play/\d+-\d+-\d+\.html")
            if path:
                route_id, sid, nid = re.findall(r"\d+", path)
                episodes.append({"route_id": route_id, "sid": int(sid), "nid": int(nid), "label": a.text, "path": path})
        if not episodes:
            continue
        if not current_name or len({(e["route_id"], e["sid"]) for e in episodes}) != 1:
            raise ValueError("Playlist has missing heading or inconsistent identities")
        external = any(word in current_name for word in ("下载", "网盘", "夸克", "百度", "UC"))
        sources.append({"name": current_name, "kind": "external_download" if external else "online_candidate",
                        "sid": episodes[0]["sid"], "episodes": episodes})
    if not title or not sources:
        raise ValueError("No recognized detail title/playlists; refusing to report a playable movie")
    return {"title": title, "sources": sources}


def player_summary(html):
    for script in Document(html).root.all("script"):
        match = re.search(r"\bvar\s+player_[A-Za-z0-9_]+\s*=\s*", script.text)
        if not match:
            continue
        data, _ = json.JSONDecoder().raw_decode(script.text[match.end():])
        if not isinstance(data, dict):
            raise ValueError("Player configuration is not an object")
        return {"internal_id": str(data["id"]), "sid": int(data["sid"]), "nid": int(data["nid"]),
                "provider": data.get("from", ""), "encrypt": data.get("encrypt"),
                "has_url": bool(data.get("url")), "url_is_https": str(data.get("url", "")).startswith("https://")}
    raise ValueError("No JSON player configuration; do not execute arbitrary JavaScript as a fallback")


class SiteRedirects(HTTPRedirectHandler):
    def __init__(self, allowed):
        self.allowed = set(allowed)

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if origin(newurl) not in self.allowed:
            raise ValueError("Site redirected outside the discovered HTTPS site list")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def fetch(url, allowed, data=None):
    request = Request(url, data=data, headers={"User-Agent": "Mozilla/5.0 LIBVIO-TV-contract-probe/0.1"})
    with build_opener(SiteRedirects(allowed)).open(request, timeout=20) as response:
        if "text/html" not in response.headers.get("Content-Type", ""):
            raise ValueError("Expected an HTML page")
        payload = response.read(2_000_001)
        if len(payload) > 2_000_000:
            raise ValueError("HTML page exceeds probe size limit")
        return payload.decode("utf-8-sig"), origin(response.url)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live", action="store_true", help="Read public pages (required; no network by default)")
    parser.add_argument("--host", default="https://www.libvio.lat", help="Must appear on the current entry page")
    parser.add_argument("--detail-path", help="Optional /detail/ID.html; otherwise first real catalog card")
    parser.add_argument("--query", help="Optional keyword; checks the observed POST search form once")
    parser.add_argument("--limit", type=int, default=1, choices=range(1, 4), help="Maximum online source pages to inspect (1–3)")
    parser.add_argument("--output", type=Path, help="Write sanitized JSON report")
    args = parser.parse_args()
    if not args.live:
        parser.error("No network requested. Pass --live to inspect public pages, or run the offline unit tests.")
    entry, _ = fetch("https://libvio.lol/", ["https://libvio.lol"])
    hosts = discover_hosts(entry)
    selected = origin(args.host)
    if selected not in hosts:
        parser.error("Requested host is not in the current entry page")
    home_html, selected = fetch(selected + "/", hosts)
    catalog = parse_catalog(home_html, selected)
    detail_path = args.detail_path or catalog["cards"][0]["detail_path"]
    if not re.fullmatch(r"/detail/\d+\.html", detail_path):
        parser.error("Invalid detail path")
    detail_html, detail_origin = fetch(selected + detail_path, hosts)
    detail = parse_detail(detail_html, detail_origin)
    report = {"observed_at_utc": datetime.now(timezone.utc).isoformat(), "hosts": hosts,
              "selected_host": selected, "home_card_count": len(catalog["cards"]),
              "detail_host": detail_origin, "detail_path": detail_path, "detail": detail, "players": [],
              "scope": "HTML contract only; does not validate media resolution or Android playback"}
    if args.query:
        search_html, search_origin = fetch(selected + "/search/-------------.html", hosts,
                                           urlencode({"wd": args.query}).encode("utf-8"))
        report["search"] = {"query": args.query, "host": search_origin,
                              **parse_catalog(search_html, search_origin)}
    for source in [s for s in detail["sources"] if s["kind"] == "online_candidate"][:args.limit]:
        episode = source["episodes"][0]
        try:
            html, actual_origin = fetch(detail_origin + episode["path"], hosts)
            report["players"].append({"name": source["name"], "path": episode["path"],
                                       "host": actual_origin, **player_summary(html)})
        except Exception as exc:
            report["players"].append({"name": source["name"], "error_type": type(exc).__name__})
    result = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(result, encoding="utf-8")
    print(result, end="")


if __name__ == "__main__":
    main()
