#!/usr/bin/env python3
"""Build similar.json: "similar titles" rows for the AniLiberty TV client.

Sources (all keyed by MAL id; for AniLiberty shikimori.id == mal.id):
  * AniList  - GraphQL recommendations, every recommendation with rating >= 1,
               service order (RATING_DESC), value = votes.
  * Shikimori - /api/animes/{id}/similar, whole list, service order.
  * MAL      - /v2/anime/{id}?fields=recommendations, needs env MAL_CLIENT_ID,
               value = num_recommendations. Skipped silently without the key.

Only titles present in the AniLiberty catalog can be opened on TV, so each row
holds every catalog match in service order (no top-N, no thresholds). "<src>_total"
is how many titles the service returned, so the UI may show "N of M".

Several releases may share one MAL id (split cours, movie packs, re-uploads).
Rows get *all* of them (ascending release id): each one is a separate watchable
release and the TV cannot know which is "the" one. The release itself and every
release sharing its MAL id are excluded from its own rows.

Output (compact JSON):
  {"v":1,"generated_at":"...","sources":{"anilist":{"ok":true,...},...},
   "items":{"<release_id>":{"al":[[rid,votes],...],"al_total":N,
                            "sh":[rid,...],"sh_total":N,
                            "mal":[[rid,n],...],"mal_total":N}}}

If a source fails in this run, its data is taken from --previous (the last
published similar.json). A raw per-title cache (--cache) spreads the expensive
requests over several runs: AniList pages 2+ are refreshed at most every
--al-tail-ttl-days (28), new titles first; a --max-minutes budget per source
keeps a weekly run within about an hour.

Python 3 stdlib only.
"""

import argparse
import gzip
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

USER_AGENT = "AniLibertyTV-reco/1.0 (+https://github.com/TOSHIK13/anilibria-app)"
CATALOG_URL = "https://anilibria.top/api/v1/anime/catalog/releases"
ANILIST_URL = "https://graphql.anilist.co"
SHIKIMORI_URL = "https://shikimori.io/api/animes/{id}/similar"
MAL_URL = "https://api.myanimelist.net/v2/anime/{id}?fields=recommendations"

AL_BATCH = 50          # media per idMal_in request
AL_NESTED_PER_PAGE = 25  # nested connection hard cap
AL_ALIASES = 10        # extra pages packed into one request (aliases)
DAY = 86400

_print_lock = threading.Lock()


def log(*args):
    with _print_lock:
        print(time.strftime("%H:%M:%S"), *args, flush=True)


class SourceFailed(Exception):
    pass


class Http:
    """Rate limited HTTP client with retries (429 Retry-After, 5xx, network)."""

    def __init__(self, name, min_interval, headers=None, timeout=40, retries=5):
        self.name = name
        self.min_interval = min_interval
        self.headers = {"User-Agent": USER_AGENT, "Accept": "application/json"}
        self.headers.update(headers or {})
        self.timeout = timeout
        self.retries = retries
        self._last = 0.0
        self.requests = 0
        self.errors = 0

    def _wait(self):
        delta = time.monotonic() - self._last
        if delta < self.min_interval:
            time.sleep(self.min_interval - delta)
        self._last = time.monotonic()

    def request(self, url, body=None, not_found_ok=False):
        data = None
        headers = dict(self.headers)
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        last_error = None
        for attempt in range(self.retries):
            self._wait()
            self.requests += 1
            req = urllib.request.Request(url, data=data, headers=headers)
            try:
                with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                    limit = resp.headers.get("X-RateLimit-Limit")
                    if limit and limit.isdigit() and int(limit) > 0:
                        # AniList: limit is per minute and changes over time (90 -> 30).
                        self.min_interval = max(self.min_interval, 60.0 / int(limit) + 0.05)
                    return json.loads(resp.read().decode("utf-8"))
            except urllib.error.HTTPError as e:
                last_error = e
                if e.code == 404 and not_found_ok:
                    return None
                if e.code == 429:
                    retry = e.headers.get("Retry-After")
                    wait = int(retry) + 1 if retry and retry.isdigit() else 30
                    log(self.name, "429, sleep", wait)
                    time.sleep(wait)
                    continue
                if e.code >= 500 or e.code in (403, 408):
                    time.sleep(min(60, 3 * 2 ** attempt))
                    continue
                self.errors += 1
                raise
            except (urllib.error.URLError, TimeoutError, ConnectionError, OSError, ValueError) as e:
                last_error = e
                time.sleep(min(60, 3 * 2 ** attempt))
        self.errors += 1
        raise SourceFailed("%s: %s -> %r" % (self.name, url, last_error))


# --------------------------------------------------------------------------- catalog

def load_catalog():
    http = Http("catalog", 0.3)
    mal_to_rel = {}
    rel_to_mal = {}
    page, total_pages = 1, 1
    while page <= total_pages:
        q = urllib.parse.urlencode({"limit": 50, "page": page, "include": "id,shikimori.id,mal.id"})
        d = http.request(CATALOG_URL + "?" + q)
        total_pages = int(d["meta"]["pagination"]["total_pages"])
        for r in d["data"]:
            rid = int(r["id"])
            mal = (r.get("mal") or {}).get("id") or (r.get("shikimori") or {}).get("id")
            rel_to_mal[rid] = int(mal) if mal else None
            if mal:
                mal_to_rel.setdefault(int(mal), []).append(rid)
        page += 1
    for v in mal_to_rel.values():
        v.sort()
    log("catalog: %d releases, %d MAL ids, %d pages" % (len(rel_to_mal), len(mal_to_rel), total_pages))
    return rel_to_mal, mal_to_rel


# --------------------------------------------------------------------------- anilist

AL_BATCH_QUERY = """query($ids:[Int]){Page(perPage:50){media(idMal_in:$ids,type:ANIME){id idMal
recommendations(perPage:25,sort:RATING_DESC){pageInfo{hasNextPage}
nodes{rating mediaRecommendation{id idMal}}}}}}"""

AL_PAGE_FRAGMENT = ("p%d:Media(id:%d){recommendations(page:%d,perPage:25,sort:RATING_DESC)"
                    "{pageInfo{hasNextPage} nodes{rating mediaRecommendation{id idMal}}}}")


def _al_nodes(conn):
    """-> (list of [anilist_id, idMal or 0, votes] with votes >= 1, need_more)."""
    out = []
    nodes = conn.get("nodes") or []
    for n in nodes:
        rating = n.get("rating") or 0
        m = n.get("mediaRecommendation")
        if rating >= 1 and m:
            out.append([int(m["id"]), int(m.get("idMal") or 0), int(rating)])
    need_more = bool((conn.get("pageInfo") or {}).get("hasNextPage")) \
        and len(nodes) >= AL_NESTED_PER_PAGE and (nodes[-1].get("rating") or 0) >= 1
    return out, need_more


def _dedupe_sorted(entries):
    seen, out = set(), []
    for e in sorted(entries, key=lambda x: -x[2]):  # stable: keeps service order on ties
        if e[0] not in seen:
            seen.add(e[0])
            out.append(e)
    return out


def fetch_anilist(mal_ids, cache, args, stats):
    http = Http("anilist", 2.1)
    now = time.time()
    deadline = time.monotonic() + args.max_minutes * 60
    al_cache = cache.setdefault("al", {})
    first = {}      # mal -> (anilist_id, entries, need_more)
    ids = sorted(mal_ids)
    failed_batches = 0
    for i in range(0, len(ids), AL_BATCH):
        chunk = ids[i:i + AL_BATCH]
        try:
            d = http.request(ANILIST_URL, {"query": AL_BATCH_QUERY, "variables": {"ids": chunk}})
            if d.get("errors") and not d.get("data"):
                raise SourceFailed(str(d["errors"])[:300])
        except (SourceFailed, urllib.error.HTTPError) as e:
            failed_batches += 1
            log("anilist batch failed:", e)
            continue
        for m in ((d.get("data") or {}).get("Page") or {}).get("media") or []:
            if not m.get("idMal"):
                continue
            entries, more = _al_nodes(m.get("recommendations") or {})
            first[int(m["idMal"])] = (int(m["id"]), entries, more)
    if failed_batches * AL_BATCH > len(ids) / 2:
        raise SourceFailed("anilist: %d of %d batches failed" % (failed_batches, -(-len(ids) // AL_BATCH)))
    log("anilist: first pages for %d titles (%d requests)" % (len(first), http.requests))

    # Titles whose list continues past page 1. New ones first, then oldest tail.
    ttl = args.al_tail_ttl_days * DAY
    todo = []
    for mal, (aid, entries, more) in first.items():
        c = al_cache.get(str(mal))
        if not more:
            al_cache[str(mal)] = {"t": now, "n": entries}
            continue
        if c and c.get("tail") and now - c.get("t", 0) < ttl:
            al_cache[str(mal)] = {"t": c["t"], "tail": True, "n": _dedupe_sorted(entries + c["n"])}
            continue
        todo.append((c.get("t", 0) if c else -1, mal, aid))
    todo.sort()
    log("anilist: %d titles need pages 2+ (ttl %d days)" % (len(todo), args.al_tail_ttl_days))

    acc = {mal: list(first[mal][1]) for _, mal, _ in todo}
    aid_to_mal = {aid: mal for _, mal, aid in todo}
    pending = [(aid, 2) for _, _, aid in todo]  # keeps priority order
    done_tail = set()
    budget_hit = False
    while pending:
        nxt = []
        for i in range(0, len(pending), AL_ALIASES):
            if time.monotonic() > deadline:
                budget_hit = True
                break
            group = pending[i:i + AL_ALIASES]
            q = "query{" + " ".join(AL_PAGE_FRAGMENT % (k, aid, page) for k, (aid, page) in enumerate(group)) + "}"
            try:
                d = http.request(ANILIST_URL, {"query": q})
            except (SourceFailed, urllib.error.HTTPError) as e:
                log("anilist page request failed:", e)
                continue  # these titles keep the stale cache / first page
            data = d.get("data") or {}
            for k, (aid, page) in enumerate(group):
                media = data.get("p%d" % k)
                if not media:
                    continue
                entries, more = _al_nodes(media.get("recommendations") or {})
                mal = aid_to_mal[aid]
                acc[mal].extend(entries)
                if more:
                    nxt.append((aid, page + 1))
                else:
                    done_tail.add(mal)
        if budget_hit:
            break
        pending = nxt
    if budget_hit:
        log("anilist: time budget hit, %d titles keep previous tail" % (len(todo) - len(done_tail)))
    for _, mal, _ in todo:
        c = al_cache.get(str(mal))
        if mal in done_tail:
            al_cache[str(mal)] = {"t": now, "tail": True, "n": _dedupe_sorted(acc[mal])}
        elif c and c.get("tail"):
            al_cache[str(mal)] = {"t": c["t"], "tail": True, "n": _dedupe_sorted(first[mal][1] + c["n"])}
        else:
            al_cache[str(mal)] = {"t": 0, "n": _dedupe_sorted(first[mal][1])}
    stats["anilist"] = {"requests": http.requests, "titles": len(first), "tail_refreshed": len(done_tail),
                        "tail_pending": len(todo) - len(done_tail)}
    log("anilist: done, %d requests" % http.requests)
    # -> mal -> [(rec_mal, votes)], total
    result = {}
    for mal in mal_ids:
        c = al_cache.get(str(mal))
        if c:
            recs = [(e[1], e[2]) for e in c["n"] if e[1] != mal]
            result[mal] = (recs, len(recs))
    return result


# --------------------------------------------------------------------------- per-title sources

def fetch_per_title(name, http, mal_ids, cache_key, ttl_days, parse, cache, args, stats):
    now = time.time()
    deadline = time.monotonic() + args.max_minutes * 60
    c = cache.setdefault(cache_key, {})
    order = sorted(mal_ids, key=lambda m: (c.get(str(m), {}).get("t", -1), m))
    ttl = ttl_days * DAY
    fetched = errors = 0
    for idx, mal in enumerate(order):
        entry = c.get(str(mal))
        if entry and now - entry.get("t", 0) < ttl:
            continue
        if time.monotonic() > deadline:
            log("%s: time budget hit at %d/%d" % (name, idx, len(order)))
            break
        try:
            d = http(mal)
        except (SourceFailed, urllib.error.HTTPError) as e:
            errors += 1
            if errors >= 20 and errors > fetched:
                raise SourceFailed("%s: too many errors (%s)" % (name, e))
            continue
        c[str(mal)] = {"t": now, "n": parse(d)}
        fetched += 1
        if fetched % 200 == 0:
            log("%s: %d fetched" % (name, fetched))
    stats[name] = {"fetched": fetched, "errors": errors}
    log("%s: done, %d fetched, %d errors" % (name, fetched, errors))
    result = {}
    for mal in mal_ids:
        e = c.get(str(mal))
        if e:
            recs = [r for r in e["n"] if r[0] != mal]
            result[mal] = (recs, len(recs))
    return result


def fetch_shikimori(mal_ids, cache, args, stats):
    http = Http("shikimori", 0.75)  # 5 rps / 90 rpm

    def get(mal):
        return http.request(SHIKIMORI_URL.format(id=mal), not_found_ok=True) or []

    def parse(d):
        out, seen = [], set()
        for a in d:
            if a.get("id") and a["id"] not in seen:
                seen.add(a["id"])
                out.append([int(a["id"]), 0])
        return out

    return fetch_per_title("shikimori", get, mal_ids, "sh", args.sh_ttl_days, parse, cache, args, stats)


def fetch_mal(mal_ids, cache, args, stats, client_id):
    http = Http("mal", 1.0, headers={"X-MAL-CLIENT-ID": client_id})

    def get(mal):
        return http.request(MAL_URL.format(id=mal), not_found_ok=True) or {}

    def parse(d):
        out, seen = [], set()
        for r in d.get("recommendations") or []:
            node = r.get("node") or {}
            if node.get("id") and node["id"] not in seen:
                seen.add(node["id"])
                out.append([int(node["id"]), int(r.get("num_recommendations") or 0)])
        return out

    return fetch_per_title("mal", get, mal_ids, "mal", args.mal_ttl_days, parse, cache, args, stats)


# --------------------------------------------------------------------------- assemble

def to_rows(per_mal, rel_to_mal, mal_to_rel, with_value):
    """per_mal: mal -> (list of (rec_mal, value), total) -> rid -> (row, total)."""
    rows = {}
    for rid, mal in rel_to_mal.items():
        if not mal or mal not in per_mal:
            continue
        recs, total = per_mal[mal]
        own = set(mal_to_rel.get(mal, []))
        seen, row = set(), []
        for rec_mal, value in recs:
            for other in mal_to_rel.get(rec_mal, []):
                if other == rid or other in own or other in seen:
                    continue
                seen.add(other)
                row.append([other, value] if with_value else other)
        rows[rid] = (row, total)
    return rows


def load_json(path, default):
    if not path or not os.path.exists(path):
        return default
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError) as e:
        log("cannot read %s: %s" % (path, e))
        return default


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default="similar.json")
    ap.add_argument("--previous", help="previous similar.json (fallback for failed sources)")
    ap.add_argument("--cache", default="reco-cache.json", help="raw per-title cache between runs")
    ap.add_argument("--max-minutes", type=float, default=45, help="time budget per source")
    ap.add_argument("--al-tail-ttl-days", type=float, default=28)
    ap.add_argument("--sh-ttl-days", type=float, default=6)
    ap.add_argument("--mal-ttl-days", type=float, default=6)
    ap.add_argument("--sources", default="anilist,shikimori,mal")
    args = ap.parse_args()

    started = time.time()
    rel_to_mal, mal_to_rel = load_catalog()
    mal_ids = sorted(mal_to_rel)
    cache = load_json(args.cache, {})
    previous = load_json(args.previous, {})
    wanted = set(s.strip() for s in args.sources.split(",") if s.strip())
    client_id = os.environ.get("MAL_CLIENT_ID", "").strip()

    jobs = {}
    if "anilist" in wanted:
        jobs["anilist"] = lambda st: fetch_anilist(mal_ids, cache, args, st)
    if "shikimori" in wanted:
        jobs["shikimori"] = lambda st: fetch_shikimori(mal_ids, cache, args, st)
    if "mal" in wanted and client_id:
        jobs["mal"] = lambda st: fetch_mal(mal_ids, cache, args, st, client_id)
    elif "mal" in wanted:
        log("mal: MAL_CLIENT_ID is not set, skipped")

    results, stats, failures = {}, {}, {}

    def run(name):
        st = {}
        try:
            results[name] = jobs[name](st)
        except Exception as e:  # noqa: BLE001 - any failure -> fallback to previous data
            failures[name] = repr(e)[:300]
            log("%s FAILED: %r" % (name, e))
        stats[name] = st.get(name, {})

    threads = [threading.Thread(target=run, args=(n,), daemon=True) for n in jobs]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    now_iso = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    items = {}
    sources = {}
    prev_items = previous.get("items") or {}
    prev_sources = previous.get("sources") or {}
    for name, key, with_value in (("anilist", "al", True), ("shikimori", "sh", False), ("mal", "mal", True)):
        if name in results:
            rows = to_rows(results[name], rel_to_mal, mal_to_rel, with_value)
            n = 0
            for rid, (row, total) in rows.items():
                if total <= 0:
                    continue
                it = items.setdefault(str(rid), {})
                it[key] = row
                it[key + "_total"] = total
                n += 1
            sources[name] = dict(stats.get(name, {}), ok=True, updated_at=now_iso, titles=n)
            continue
        # failed or skipped: keep previous data for releases still in the catalog
        reason = failures.get(name) or ("skipped" if name not in jobs else "not run")
        kept = 0
        for rid, it in prev_items.items():
            if key in it and int(rid) in rel_to_mal:
                row = [r for r in it[key] if (r[0] if with_value else r) in rel_to_mal]
                items.setdefault(rid, {})[key] = row
                items[rid][key + "_total"] = it.get(key + "_total", len(row))
                kept += 1
        prev = prev_sources.get(name) or {}
        sources[name] = {"ok": False, "error": reason, "updated_at": prev.get("updated_at"), "titles": kept}

    out = {"v": 1, "generated_at": now_iso, "sources": sources,
           "items": dict(sorted(items.items(), key=lambda kv: int(kv[0])))}
    blob = json.dumps(out, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    tmp = args.out + ".tmp"
    with open(tmp, "wb") as f:
        f.write(blob)
    os.replace(tmp, args.out)
    if args.cache:
        with open(args.cache + ".tmp", "w", encoding="utf-8") as f:
            json.dump(cache, f, separators=(",", ":"))
        os.replace(args.cache + ".tmp", args.cache)

    # ---- stats
    print()
    print("catalog releases: %d, with MAL id: %d, unique MAL ids: %d"
          % (len(rel_to_mal), sum(1 for m in rel_to_mal.values() if m), len(mal_to_rel)))
    for name, key in (("anilist", "al"), ("shikimori", "sh"), ("mal", "mal")):
        s = sources[name]
        with_row = sum(1 for it in items.values() if it.get(key))
        with_data = sum(1 for it in items.values() if key in it)
        cells = sum(len(it.get(key, [])) for it in items.values())
        print("%-9s ok=%-5s releases with row: %4d (%.1f%%), with data: %4d, row cells: %6d, %s"
              % (name, s.get("ok"), with_row, 100.0 * with_row / max(1, len(rel_to_mal)), with_data, cells,
                 {k: v for k, v in s.items() if k not in ("ok", "titles", "updated_at")}))
    print("releases with any row: %d" % sum(1 for it in items.values()
                                            if it.get("al") or it.get("sh") or it.get("mal")))
    print("size: %d bytes, gzip: %d bytes" % (len(blob), len(gzip.compress(blob, 9))))
    print("elapsed: %.1f min" % ((time.time() - started) / 60))
    if len(failures) == len(jobs) and jobs:
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
